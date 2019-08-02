/**
 * Copyright 2009-2019 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.builder.xml;

import org.apache.ibatis.builder.*;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

import java.io.InputStream;
import java.io.Reader;
import java.util.*;

/**
 * 解析 mapper.xml
 *
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLMapperBuilder extends BaseBuilder {

    /**
     * 用来解析 mapper.xml 的 XpathParser 解析器
     */
    private final XPathParser parser;
    /**
     * mapper 构造助手
     */
    private final MapperBuilderAssistant builderAssistant;
    /**
     * 可被其他语句引用的可重用语句块的集合，例如<sql></sql> 语句块
     */
    private final Map<String, XNode> sqlFragments;
    /**
     * 资源引用的地址
     */
    private final String resource;

    // ------------------------------------ 一系列构造方法 -------------------------------------------------------------
    @Deprecated
    public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
        this(reader, configuration, resource, sqlFragments);
        this.builderAssistant.setCurrentNamespace(namespace);
    }

    @Deprecated
    public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
        this(new XPathParser(reader, true, configuration.getVariables(), new XMLMapperEntityResolver()),
                configuration, resource, sqlFragments);
    }

    public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
        this(inputStream, configuration, resource, sqlFragments);
        // 设置构建助手的当前命名空间
        this.builderAssistant.setCurrentNamespace(namespace);
    }

    public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
        // 创建 XML 解析器
        this(new XPathParser(inputStream, true, configuration.getVariables(), new XMLMapperEntityResolver()),
                configuration, resource, sqlFragments);
    }

    private XMLMapperBuilder(XPathParser parser, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
        // 构造父类
        super(configuration);
        this.builderAssistant = new MapperBuilderAssistant(configuration, resource);
        this.parser = parser;
        this.sqlFragments = sqlFragments;
        this.resource = resource;
    }

    /**
     * 解析 mapper XML
     */
    public void parse() {
        if (!configuration.isResourceLoaded(resource)) {
            // 判断资源没有加载过

            // 解析 mapper 标签
            configurationElement(parser.evalNode("/mapper"));
            // 标记为加载过了，放入已加载集合
            configuration.addLoadedResource(resource);
            // 绑定 mapper
            bindMapperForNamespace();
        }

        // 解析待定的 resultMap 标签
        parsePendingResultMaps();
        // 解析待定的 cache-ref 标签
        parsePendingCacheRefs();
        // 解析待定的 SQL 标签
        parsePendingStatements();
    }

    public XNode getSqlFragment(String refid) {
        return sqlFragments.get(refid);
    }

    /**
     * 解析 mapper 标签
     *
     * @param context
     */
    private void configurationElement(XNode context) {
        try {
            // 获得 namespace 属性
            String namespace = context.getStringAttribute("namespace");
            if (namespace == null || namespace.equals("")) {
                // namespace 不能为空，为空则抛出异常
                throw new BuilderException("Mapper's namespace cannot be empty");
            }
            // 设置构建助手的 namespace
            builderAssistant.setCurrentNamespace(namespace);
            // 解析 cache-ref 标签
            cacheRefElement(context.evalNode("cache-ref"));
            // 解析 cache 标签
            cacheElement(context.evalNode("cache"));
            // 解析 parameterMap 标签，但是 map 参数不推荐使用，mybatis 以后可能废除掉 map 参数，使用 bean 传参为好
            parameterMapElement(context.evalNodes("/mapper/parameterMap"));
            // 解析 resultMap 标签
            resultMapElements(context.evalNodes("/mapper/resultMap"));
            // 解析 sql 标签
            sqlElement(context.evalNodes("/mapper/sql"));
            // 解析 select insert update delete 标签
            buildStatementFromContext(context.evalNodes("select|insert|update|delete"));
        } catch (Exception e) {
            throw new BuilderException("Error parsing Mapper XML. The XML location is '" + resource + "'. Cause: " + e, e);
        }
    }

    /**
     * 解析 insert select update delete 标签
     *
     * @param list
     */
    private void buildStatementFromContext(List<XNode> list) {
        if (configuration.getDatabaseId() != null) {
            buildStatementFromContext(list, configuration.getDatabaseId());
        }
        buildStatementFromContext(list, null);
    }

    /**
     * 解析 insert select update delete 标签
     *
     * @param list
     * @param requiredDatabaseId
     */
    private void buildStatementFromContext(List<XNode> list, String requiredDatabaseId) {
        // 遍历所有的 insert select update delete 标签
        for (XNode context : list) {
            // 创建解析器，执行解析
            final XMLStatementBuilder statementParser = new XMLStatementBuilder(configuration, builderAssistant, context, requiredDatabaseId);
            try {
                statementParser.parseStatementNode();
            } catch (IncompleteElementException e) {
                // 解析失败，可能是依赖的数据没有加载完，放入未完成 configuration 中
                configuration.addIncompleteStatement(statementParser);
            }
        }
    }

    /**
     * 解析待解析的 resultMap
     * 这些解析还是可能会解析失败的，但是不要紧，每解析一个 mapper.xml 就会解析一次这些待解析的 resultMap cache-ref statement，最终就全部解析完了。
     */
    private void parsePendingResultMaps() {
        // 不完整的 ResultMaps
        Collection<ResultMapResolver> incompleteResultMaps = configuration.getIncompleteResultMaps();
        synchronized (incompleteResultMaps) {
            Iterator<ResultMapResolver> iter = incompleteResultMaps.iterator();
            while (iter.hasNext()) {
                try {
                    iter.next().resolve();
                    iter.remove();
                } catch (IncompleteElementException e) {
                    // ResultMap is still missing a resource...
                }
            }
        }
    }

    /**
     * 解析待解析的 cache-ref
     */
    private void parsePendingCacheRefs() {
        Collection<CacheRefResolver> incompleteCacheRefs = configuration.getIncompleteCacheRefs();
        synchronized (incompleteCacheRefs) {
            Iterator<CacheRefResolver> iter = incompleteCacheRefs.iterator();
            while (iter.hasNext()) {
                try {
                    iter.next().resolveCacheRef();
                    iter.remove();
                } catch (IncompleteElementException e) {
                    // Cache ref is still missing a resource...
                }
            }
        }
    }

    /**
     * 解析待解析的 statement (insert|update|delete|select)
     */
    private void parsePendingStatements() {
        Collection<XMLStatementBuilder> incompleteStatements = configuration.getIncompleteStatements();
        synchronized (incompleteStatements) {
            Iterator<XMLStatementBuilder> iter = incompleteStatements.iterator();
            while (iter.hasNext()) {
                try {
                    iter.next().parseStatementNode();
                    iter.remove();
                } catch (IncompleteElementException e) {
                    // Statement is still missing a resource...
                }
            }
        }
    }

    /**
     * 解析 cache-ref 标签
     *
     * @param context
     */
    private void cacheRefElement(XNode context) {
        if (context != null) {
            // 把当前 mapper 的 namespace - cache-ref 的 namespace 放入 configuration 的 cacheRefMap 中
            configuration.addCacheRef(builderAssistant.getCurrentNamespace(), context.getStringAttribute("namespace"));
            CacheRefResolver cacheRefResolver = new CacheRefResolver(builderAssistant, context.getStringAttribute("namespace"));
            try {
                // 执行解析
                cacheRefResolver.resolveCacheRef();
            } catch (IncompleteElementException e) {
                // 解析失败，放入 configuration 的 incompleteCacheRefs 中
                // 可能 cache 的对象还未初始化，先保存起来，后边再解析
                configuration.addIncompleteCacheRef(cacheRefResolver);
            }
        }
    }

    /**
     * 解析 cache 标签
     * <p>
     * 使用示例
     * // 使用默认缓存
     * <cache eviction="FIFO" flushInterval="60000"  size="512" readOnly="true"/>
     * <p>
     * // 使用自定义缓存
     * <cache type="com.domain.something.MyCustomCache">
     * <property name="cacheFile" value="/tmp/my-custom-cache.tmp"/>
     * </cache>
     *
     * @param context
     */
    private void cacheElement(XNode context) {
        if (context != null) {
            // 获取负责存储的 cache 实现类， 如果为空，则使用 PERPETUAL 缓存
            String type = context.getStringAttribute("type", "PERPETUAL");
            Class<? extends Cache> typeClass = typeAliasRegistry.resolveAlias(type);

            // 获取负责过期的 cache 实现类， 如果为空，则使用 lruCache
            String eviction = context.getStringAttribute("eviction", "LRU");
            Class<? extends Cache> evictionClass = typeAliasRegistry.resolveAlias(eviction);

            // 获取 flushInterval 的 周期， size， readWrite , blocking 等属性
            Long flushInterval = context.getLongAttribute("flushInterval");
            Integer size = context.getIntAttribute("size");
            boolean readWrite = !context.getBooleanAttribute("readOnly", false);
            boolean blocking = context.getBooleanAttribute("blocking", false);

            // 获取其他 name-value 属性
            Properties props = context.getChildrenAsProperties();

            // 创建 cache
            builderAssistant.useNewCache(typeClass, evictionClass, flushInterval, size, readWrite, blocking, props);
        }
    }

    private void parameterMapElement(List<XNode> list) {
        for (XNode parameterMapNode : list) {
            String id = parameterMapNode.getStringAttribute("id");
            String type = parameterMapNode.getStringAttribute("type");
            Class<?> parameterClass = resolveClass(type);
            List<XNode> parameterNodes = parameterMapNode.evalNodes("parameter");
            List<ParameterMapping> parameterMappings = new ArrayList<>();
            for (XNode parameterNode : parameterNodes) {
                String property = parameterNode.getStringAttribute("property");
                String javaType = parameterNode.getStringAttribute("javaType");
                String jdbcType = parameterNode.getStringAttribute("jdbcType");
                String resultMap = parameterNode.getStringAttribute("resultMap");
                String mode = parameterNode.getStringAttribute("mode");
                String typeHandler = parameterNode.getStringAttribute("typeHandler");
                Integer numericScale = parameterNode.getIntAttribute("numericScale");
                ParameterMode modeEnum = resolveParameterMode(mode);
                Class<?> javaTypeClass = resolveClass(javaType);
                JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
                Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
                ParameterMapping parameterMapping = builderAssistant.buildParameterMapping(parameterClass, property, javaTypeClass, jdbcTypeEnum, resultMap, modeEnum, typeHandlerClass, numericScale);
                parameterMappings.add(parameterMapping);
            }
            builderAssistant.addParameterMap(id, parameterClass, parameterMappings);
        }
    }

    /**
     * 解析 resultMap 标签
     *
     * @param list
     * @throws Exception
     */
    private void resultMapElements(List<XNode> list) throws Exception {
        for (XNode resultMapNode : list) {
            // 遍历所有的 resultMap 标签
            try {
                resultMapElement(resultMapNode);
            } catch (IncompleteElementException e) {
                // ignore, it will be retried
            }
        }
    }

    /**
     * 解析 resultMap 标签
     *
     * @param resultMapNode
     * @return
     * @throws Exception
     */
    private ResultMap resultMapElement(XNode resultMapNode) throws Exception {
        return resultMapElement(resultMapNode, Collections.<ResultMapping>emptyList(), null);
    }

    /**
     * 解析 resultMap 标签
     *
     * @param resultMapNode
     * @param additionalResultMappings
     * @param enclosingType
     * @return
     * @throws Exception
     */
    private ResultMap resultMapElement(XNode resultMapNode, List<ResultMapping> additionalResultMappings, Class<?> enclosingType) throws Exception {
        ErrorContext.instance().activity("processing " + resultMapNode.getValueBasedIdentifier());

        // 获取 type ，如果 type 为空，使用 ofType , 如果还为空，使用 resultType , 还为空 使用 javaType
        String type = resultMapNode.getStringAttribute("type",
                resultMapNode.getStringAttribute("ofType",
                        resultMapNode.getStringAttribute("resultType",
                                resultMapNode.getStringAttribute("javaType"))));

        // 从 typeAliasRegistry 中获取 type 的类，如果没有则加载类
        Class<?> typeClass = resolveClass(type);
        if (typeClass == null) {
            // 如果没有则使用封闭类？
            typeClass = inheritEnclosingType(resultMapNode, enclosingType);
        }
        Discriminator discriminator = null;

        // <2> 创建 ResultMapping 集合
        List<ResultMapping> resultMappings = new ArrayList<>();
        resultMappings.addAll(additionalResultMappings);
        // 遍历 resultMapper 的子节点
        List<XNode> resultChildren = resultMapNode.getChildren();
        for (XNode resultChild : resultChildren) {
            if ("constructor".equals(resultChild.getName())) {
                // 处理 constructor 节点
                processConstructorElement(resultChild, typeClass, resultMappings);
            } else if ("discriminator".equals(resultChild.getName())) {
                // 处理 discriminator 节点
                discriminator = processDiscriminatorElement(resultChild, typeClass, resultMappings);
            } else {
                // 处理其他节点
                List<ResultFlag> flags = new ArrayList<>();
                if ("id".equals(resultChild.getName())) {
                    flags.add(ResultFlag.ID);
                }
                resultMappings.add(buildResultMappingFromContext(resultChild, typeClass, flags));
            }
        }

        // 获得 ID
        String id = resultMapNode.getStringAttribute("id", resultMapNode.getValueBasedIdentifier());
        String extend = resultMapNode.getStringAttribute("extends");
        Boolean autoMapping = resultMapNode.getBooleanAttribute("autoMapping");

        // 创建 ResultMapResolver 对象 执行解析
        ResultMapResolver resultMapResolver = new ResultMapResolver(builderAssistant, id, typeClass, extend, discriminator, resultMappings, autoMapping);
        try {
            return resultMapResolver.resolve();
        } catch (IncompleteElementException e) {
            // 如果解析异常，则放入 IncompleteResultMap 中，如果是依赖的资源不全，后边可以重新成功
            configuration.addIncompleteResultMap(resultMapResolver);
            throw e;
        }
    }

    /**
     * 继承封闭类
     *
     * @param resultMapNode
     * @param enclosingType
     * @return
     */
    protected Class<?> inheritEnclosingType(XNode resultMapNode, Class<?> enclosingType) {
        if ("association".equals(resultMapNode.getName()) && resultMapNode.getStringAttribute("resultMap") == null) {
            // 是关联标签

            // 关联的属性
            String property = resultMapNode.getStringAttribute("property");
            if (property != null && enclosingType != null) {
                MetaClass metaResultType = MetaClass.forClass(enclosingType, configuration.getReflectorFactory());
                return metaResultType.getSetterType(property);
            }
        } else if ("case".equals(resultMapNode.getName()) && resultMapNode.getStringAttribute("resultMap") == null) {
            return enclosingType;
        }
        return null;
    }

    /**
     * 处理 constructor 标签
     *
     * @param resultChild
     * @param resultType
     * @param resultMappings
     * @throws Exception
     */
    private void processConstructorElement(XNode resultChild, Class<?> resultType, List<ResultMapping> resultMappings) throws Exception {
        List<XNode> argChildren = resultChild.getChildren();
        // 遍历 constructor 的子节点
        for (XNode argChild : argChildren) {
            // 获得 ResultFlag 集合
            List<ResultFlag> flags = new ArrayList<>();
            flags.add(ResultFlag.CONSTRUCTOR);
            if ("idArg".equals(argChild.getName())) {
                flags.add(ResultFlag.ID);
            }
            // 将当前子节点构建成 ResultMapping 对象，并添加到 resultMappings 中
            resultMappings.add(buildResultMappingFromContext(argChild, resultType, flags));
        }
    }

    /**
     * 处理 discriminator 鉴别器 标签
     *
     * @param context
     * @param resultType
     * @param resultMappings
     * @return
     * @throws Exception
     */
    private Discriminator processDiscriminatorElement(XNode context, Class<?> resultType, List<ResultMapping> resultMappings) throws Exception {
        // 数据库列名
        String column = context.getStringAttribute("column");
        // 对应的 java 类型
        String javaType = context.getStringAttribute("javaType");
        // 对应的 jdbc 类型
        String jdbcType = context.getStringAttribute("jdbcType");
        // 对应的 typeHandler
        String typeHandler = context.getStringAttribute("typeHandler");

        // 解析 java 类型
        Class<?> javaTypeClass = resolveClass(javaType);
        // 解析 typeHandler 类型
        Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
        // 解析 jdbc 类型
        JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
        Map<String, String> discriminatorMap = new HashMap<>();
        // 遍历 discriminator 的 case 子标签
        for (XNode caseChild : context.getChildren()) {
            // 获取 value 属性， value 是 discriminator 的 column 的值
            String value = caseChild.getStringAttribute("value");
            // 获取 resultMap 属性
            // 该方法，会“递归”调用 #resultMapElement(XNode context, List<ResultMapping> resultMappings) 方法，处理内嵌的 ResultMap 的情况.
            String resultMap = caseChild.getStringAttribute("resultMap", processNestedResultMappings(caseChild, resultMappings, resultType));
            // 放入 discriminatorMap 中
            discriminatorMap.put(value, resultMap);
        }
        // 创建 Discriminator  对象
        return builderAssistant.buildDiscriminator(resultType, column, javaTypeClass, jdbcTypeEnum, typeHandlerClass, discriminatorMap);
    }

    /**
     * 解析所有的 sql 标签
     *
     * @param list
     */
    private void sqlElement(List<XNode> list) {
        if (configuration.getDatabaseId() != null) {
            sqlElement(list, configuration.getDatabaseId());
        }
        sqlElement(list, null);
    }

    /**
     * sql 元素 <sql/> 标签
     *
     * @param list
     * @param requiredDatabaseId
     */
    private void sqlElement(List<XNode> list, String requiredDatabaseId) {
        for (XNode context : list) {
            // 获取 databaseId
            String databaseId = context.getStringAttribute("databaseId");
            // 获取 sql 的id ${namespace}.${id}
            String id = context.getStringAttribute("id");
            id = builderAssistant.applyCurrentNamespace(id, false);
            // 判断 databaseId 是否匹配
            if (databaseIdMatchesCurrent(id, databaseId, requiredDatabaseId)) {
                // 添加到 sqlFragments 中
                sqlFragments.put(id, context);
            }
        }
    }

    /**
     * 判断 databaseId 是否匹配
     *
     * @param id
     * @param databaseId
     * @param requiredDatabaseId
     * @return
     */
    private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
        if (requiredDatabaseId != null) {
            // 如果不相等返回 false
            if (!requiredDatabaseId.equals(databaseId)) {
                return false;
            }
        } else {
            // 如果 requiredDatabaseId 未设置，但是 databaseId 存在，还是不匹配
            if (databaseId != null) {
                return false;
            }
            // skip this fragment if there is a previous one with a not null databaseId
            // 判断是否已存在
            if (this.sqlFragments.containsKey(id)) {
                XNode context = this.sqlFragments.get(id);
                // 若存在，则判断原有的 sqlFragment 是否 databaseId 为空。因为，当前 databaseId 为空，这样两者才能匹配。
                if (context.getStringAttribute("databaseId") != null) {
                    return false;
                }
            }
        }
        // 以上都不符合，则返回匹配
        return true;
    }

    /**
     * 将当前对象，构建成功 ResultMapping 对象
     *
     * @param context
     * @param resultType
     * @param flags
     * @return
     * @throws Exception
     */
    private ResultMapping buildResultMappingFromContext(XNode context, Class<?> resultType, List<ResultFlag> flags) throws Exception {
        String property;
        // 获得各种属性
        if (flags.contains(ResultFlag.CONSTRUCTOR)) {
            property = context.getStringAttribute("name");
        } else {
            property = context.getStringAttribute("property");
        }
        String column = context.getStringAttribute("column");
        String javaType = context.getStringAttribute("javaType");
        String jdbcType = context.getStringAttribute("jdbcType");
        String nestedSelect = context.getStringAttribute("select");
        String nestedResultMap = context.getStringAttribute("resultMap", processNestedResultMappings(context, Collections.<ResultMapping>emptyList(), resultType));
        String notNullColumn = context.getStringAttribute("notNullColumn");
        String columnPrefix = context.getStringAttribute("columnPrefix");
        String typeHandler = context.getStringAttribute("typeHandler");
        String resultSet = context.getStringAttribute("resultSet");
        String foreignColumn = context.getStringAttribute("foreignColumn");
        boolean lazy = "lazy".equals(context.getStringAttribute("fetchType", configuration.isLazyLoadingEnabled() ? "lazy" : "eager"));

        // 获得各个属性对应的类
        Class<?> javaTypeClass = resolveClass(javaType);
        Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
        JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);

        // 构建 resultMapping
        return builderAssistant.buildResultMapping(resultType, property, column, javaTypeClass, jdbcTypeEnum, nestedSelect, nestedResultMap, notNullColumn, columnPrefix, typeHandlerClass, flags, resultSet, foreignColumn, lazy);
    }

    private String processNestedResultMappings(XNode context, List<ResultMapping> resultMappings, Class<?> enclosingType) throws Exception {
        if ("association".equals(context.getName())
                || "collection".equals(context.getName())
                || "case".equals(context.getName())) {
            if (context.getStringAttribute("select") == null) {
                validateCollection(context, enclosingType);
                ResultMap resultMap = resultMapElement(context, resultMappings, enclosingType);
                return resultMap.getId();
            }
        }
        return null;
    }

    protected void validateCollection(XNode context, Class<?> enclosingType) {
        if ("collection".equals(context.getName()) && context.getStringAttribute("resultMap") == null
                && context.getStringAttribute("resultType") == null) {
            MetaClass metaResultType = MetaClass.forClass(enclosingType, configuration.getReflectorFactory());
            String property = context.getStringAttribute("property");
            if (!metaResultType.hasSetter(property)) {
                throw new BuilderException(
                        "Ambiguous collection type for property '" + property + "'. You must specify 'resultType' or 'resultMap'.");
            }
        }
    }

    /**
     * 绑定 Mapper
     */
    private void bindMapperForNamespace() {
        // 获取当前命名空间
        String namespace = builderAssistant.getCurrentNamespace();
        if (namespace != null) {
            Class<?> boundType = null;
            try {
                // 限制类型设置为命名空间
                boundType = Resources.classForName(namespace);
            } catch (ClassNotFoundException e) {
                //ignore, bound type is not required
            }
            if (boundType != null) {
                if (!configuration.hasMapper(boundType)) {
                    // configuration 中的 mapperRegister 不包含 boundType
                    // Spring may not know the real resource name so we set a flag
                    // to prevent loading again this resource from the mapper interface
                    // look at MapperAnnotationBuilder#loadXmlResource
                    // 添加到已加载资源集合中
                    // 标记 namespace 已经添加，避免 MapperAnnotationBuilder#loadXmlResource(...) 重复加载
                    configuration.addLoadedResource("namespace:" + namespace);
                    // 添加到 configuration 的 mapperRegister 中
                    configuration.addMapper(boundType);
                }
            }
        }
        // 如果命名空间为空，则不处理
    }
}
