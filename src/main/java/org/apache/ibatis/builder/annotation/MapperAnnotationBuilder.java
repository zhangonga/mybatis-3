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
package org.apache.ibatis.builder.annotation;

import org.apache.ibatis.annotations.*;
import org.apache.ibatis.annotations.Options.FlushCachePolicy;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.binding.BindingException;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.CacheRefResolver;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.executor.keygen.SelectKeyGenerator;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.reflection.TypeParameterResolver;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.UnknownTypeHandler;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;

/**
 * 扫描 mapper 的时候，解析 mapper 上的注解
 *
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class MapperAnnotationBuilder {

    /**
     * SQL 操作注解类型集合
     */
    private static final Set<Class<? extends Annotation>> SQL_ANNOTATION_TYPES = new HashSet<>();
    /**
     * SQL Provider 注解类型集合
     */
    private static final Set<Class<? extends Annotation>> SQL_PROVIDER_ANNOTATION_TYPES = new HashSet<>();

    private final Configuration configuration;
    private final MapperBuilderAssistant assistant;
    /**
     * 解析的注解类
     */
    private final Class<?> type;

    /**
     * 初始化，就这8种
     */
    static {
        SQL_ANNOTATION_TYPES.add(Select.class);
        SQL_ANNOTATION_TYPES.add(Insert.class);
        SQL_ANNOTATION_TYPES.add(Update.class);
        SQL_ANNOTATION_TYPES.add(Delete.class);

        SQL_PROVIDER_ANNOTATION_TYPES.add(SelectProvider.class);
        SQL_PROVIDER_ANNOTATION_TYPES.add(InsertProvider.class);
        SQL_PROVIDER_ANNOTATION_TYPES.add(UpdateProvider.class);
        SQL_PROVIDER_ANNOTATION_TYPES.add(DeleteProvider.class);
    }

    /**
     * 构造方法，传入 configuration 和 mapper 接口
     *
     * @param configuration
     * @param type
     */
    public MapperAnnotationBuilder(Configuration configuration, Class<?> type) {
        // 接口地址
        String resource = type.getName().replace('.', '/') + ".java (best guess)";
        // 创建 MapperBuilderAssistant
        this.assistant = new MapperBuilderAssistant(configuration, resource);
        this.configuration = configuration;
        this.type = type;
    }

    /**
     * 解析注解
     */
    public void parse() {
        // interface:xxx.xxx.mapper
        String resource = type.toString();
        // 如果没有加载过，才去加载
        if (!configuration.isResourceLoaded(resource)) {
            // 加载对应的 xml Mapper
            loadXmlResource();
            // 标记为加载过
            configuration.addLoadedResource(resource);
            // 设置当前命名空间
            assistant.setCurrentNamespace(type.getName());
            // 解析 CacheNamespace 注解
            parseCache();
            // 解析 CacheNamespaceRef 注解
            parseCacheRef();
            // 遍历所有方法
            Method[] methods = type.getMethods();
            for (Method method : methods) {
                try {
                    // issue #237
                    if (!method.isBridge()) {
                        // 执行解析
                        parseStatement(method);
                    }
                } catch (IncompleteElementException e) {
                    // 解析失败，放入 configuration 的 incompleteMethods 集合中
                    // 最终执行，会在当前类 parsePendingMethods 方法中调用 this 对象的 parseStatement 方法解析 接口的的方法
                    configuration.addIncompleteMethod(new MethodResolver(this, method));
                }
            }
        }
        // 解析待定的方法
        parsePendingMethods();
    }

    /**
     * 处理待定的 method
     */
    private void parsePendingMethods() {
        // 获取之前未处理的 method
        Collection<MethodResolver> incompleteMethods = configuration.getIncompleteMethods();
        synchronized (incompleteMethods) {
            // 加锁迭代
            Iterator<MethodResolver> iter = incompleteMethods.iterator();
            while (iter.hasNext()) {
                try {
                    // 重新解析，然后移除
                    iter.next().resolve();
                    iter.remove();
                } catch (IncompleteElementException e) {
                    // This method is still missing a resource
                }
            }
        }
    }

    /**
     * 加载对应的 xml mapper
     */
    private void loadXmlResource() {
        // Spring may not know the real resource name so we check a flag
        // to prevent loading again a resource twice
        // this flag is set at XMLMapperBuilder#bindMapperForNamespace

        // 如果没有加载过，才往下执行
        // namespace:interface:xxx.xxx.xxx.mapper
        if (!configuration.isResourceLoaded("namespace:" + type.getName())) {
            // 资源地址，默认加载的是和 mapper 接口在同一路径的 mapper.xml
            String xmlResource = type.getName().replace('.', '/') + ".xml";
            // #1347
            // 读取 xml 为 InputStream
            InputStream inputStream = type.getResourceAsStream("/" + xmlResource);
            if (inputStream == null) {
                // Search XML mapper that is not in the module but in the classpath.
                // 这是避免 xml 文件不在当前 module 中，但是在当前 classpath，例如其他 jar 包的当前路径下
                try {
                    inputStream = Resources.getResourceAsStream(type.getClassLoader(), xmlResource);
                } catch (IOException e2) {
                    // ignore, resource is not required
                }
            }
            if (inputStream != null) {
                // 创建 XMLMapperBuilder 解析 xml mapper
                XMLMapperBuilder xmlParser = new XMLMapperBuilder(inputStream, assistant.getConfiguration(), xmlResource, configuration.getSqlFragments(), type.getName());
                xmlParser.parse();
            }
        }
    }

    /**
     * 解析 CacheNamespace 注解
     */
    private void parseCache() {
        // 获取注解
        CacheNamespace cacheDomain = type.getAnnotation(CacheNamespace.class);
        if (cacheDomain != null) {
            // 如果设置的缓存的大小为 0 ，则设置 size 为 null
            Integer size = cacheDomain.size() == 0 ? null : cacheDomain.size();
            // 缓存刷新间隔同理
            Long flushInterval = cacheDomain.flushInterval() == 0 ? null : cacheDomain.flushInterval();
            Properties props = convertToProperties(cacheDomain.properties());
            // 创建新的 cache 并放入 configuration 中， key 为当前的 namespace value 为当前缓存
            assistant.useNewCache(cacheDomain.implementation(), cacheDomain.eviction(), flushInterval, size, cacheDomain.readWrite(), cacheDomain.blocking(), props);
        }
    }

    /**
     * 解析出 "${", "}"  的内容，放入到 Properties 中
     *
     * @param properties
     * @return
     */
    private Properties convertToProperties(Property[] properties) {
        if (properties.length == 0) {
            return null;
        }
        Properties props = new Properties();
        for (Property property : properties) {
            // 通过 PropertyParser 解析出 "${", "}" 里的内容
            props.setProperty(property.name(), PropertyParser.parse(property.value(), configuration.getVariables()));
        }
        return props;
    }

    /**
     * 处理 CacheNamespaceRef 注解
     */
    private void parseCacheRef() {
        // 获取注解，为空的话还有撒好说的
        CacheNamespaceRef cacheDomainRef = type.getAnnotation(CacheNamespaceRef.class);
        if (cacheDomainRef != null) {
            // 获取引用的类
            Class<?> refType = cacheDomainRef.value();
            // 获取引用的类的命名空间
            String refName = cacheDomainRef.name();
            // 看下边的判断，refType 和 refName 不能共存啊
            if (refType == void.class && refName.isEmpty()) {
                throw new BuilderException("Should be specified either value() or name() attribute in the @CacheNamespaceRef");
            }
            if (refType != void.class && !refName.isEmpty()) {
                throw new BuilderException("Cannot use both value() and name() attribute in the @CacheNamespaceRef");
            }
            // 如果 refType 不等于 void 的话，就使用 refType 的 name， 否则就使用 refName
            String namespace = (refType != void.class) ? refType.getName() : refName;
            try {
                // 获得指向的 Cache 对象
                assistant.useCacheRef(namespace);
            } catch (IncompleteElementException e) {
                // 如果抛异常，就放入 incompleteCacheRefs 也许是引用的类还没有加载呢，等以后再解析
                configuration.addIncompleteCacheRef(new CacheRefResolver(assistant, namespace));
            }
        }
    }

    /**
     * 通过 method 一些注解 生成 resultMapId
     *
     * @param method
     * @return
     */
    private String parseResultMap(Method method) {
        // 获取方法的返回值类型
        Class<?> returnType = getReturnType(method);
        // 获取 ConstructorArgs 注解
        ConstructorArgs args = method.getAnnotation(ConstructorArgs.class);
        // 获取 Results 注解
        Results results = method.getAnnotation(Results.class);
        // 获取 TypeDiscriminator 注解
        TypeDiscriminator typeDiscriminator = method.getAnnotation(TypeDiscriminator.class);
        // 生成 resultMapId
        String resultMapId = generateResultMapName(method);
        // 生成 ResultMap 对象
        applyResultMap(resultMapId, returnType, argsIf(args), resultsIf(results), typeDiscriminator);
        return resultMapId;
    }

    /**
     * 生成 resultMapId
     *
     * @param method
     * @return
     */
    private String generateResultMapName(Method method) {
        // 如果有 @Result 注解，返回 接口名 + results.id
        Results results = method.getAnnotation(Results.class);
        if (results != null && !results.id().isEmpty()) {
            return type.getName() + "." + results.id();
        }

        // 如果没有 @Result 注解，返回 接口名 + 方法名 + 参数列表
        StringBuilder suffix = new StringBuilder();
        for (Class<?> c : method.getParameterTypes()) {
            suffix.append("-");
            suffix.append(c.getSimpleName());
        }
        if (suffix.length() < 1) {
            suffix.append("-void");
        }
        return type.getName() + "." + method.getName() + suffix;
    }

    /**
     * 生成 ResultMap 对象
     *
     * @param resultMapId
     * @param returnType
     * @param args
     * @param results
     * @param discriminator
     */
    private void applyResultMap(String resultMapId, Class<?> returnType, Arg[] args, Result[] results, TypeDiscriminator discriminator) {
        // 创建 resultMapping 数组
        List<ResultMapping> resultMappings = new ArrayList<>();
        // 将 @Arg 注解的数组，解析成对应的 ResultMapping 对象，并放入 resultMappings 中
        applyConstructorArgs(args, returnType, resultMappings);
        // 将 @Result 注解数组，解析成对应的 ResultMapping 对象，并放入 resultMappings 中
        applyResults(results, returnType, resultMappings);
        // 创建 Discriminator 对象
        Discriminator disc = applyDiscriminator(resultMapId, returnType, discriminator);
        // TODO add AutoMappingBehaviour
        // 创建 ResultMap 对象
        assistant.addResultMap(resultMapId, returnType, null, disc, resultMappings, null);
        // 创建 Discriminator 的 ResultMap 对象
        createDiscriminatorResultMaps(resultMapId, returnType, discriminator);
    }

    /**
     * 创建 Discriminator 的 ResultMap
     *
     * @param resultMapId
     * @param resultType
     * @param discriminator
     */
    private void createDiscriminatorResultMaps(String resultMapId, Class<?> resultType, TypeDiscriminator discriminator) {
        if (discriminator != null) {
            for (Case c : discriminator.cases()) {
                // 遍历 Case

                // 获取 caseResultMapId
                String caseResultMapId = resultMapId + "-" + c.value();
                // 创建 resultMapping 数组
                List<ResultMapping> resultMappings = new ArrayList<>();
                // issue #136
                // 将 @Arg[] 注解数组，解析成对应的 ResultMapping 对象们，并添加到 resultMappings 中
                applyConstructorArgs(c.constructArgs(), resultType, resultMappings);
                // 将 @Result[] 注解数组，解析成对应的 ResultMapping 对象们，并添加到 resultMappings 中
                applyResults(c.results(), resultType, resultMappings);
                // TODO add AutoMappingBehaviour
                // 创建 ResultMap 对象
                assistant.addResultMap(caseResultMapId, c.type(), resultMapId, null, resultMappings, null);
            }
        }
    }

    /**
     * 创建鉴别器对象
     *
     * @param resultMapId
     * @param resultType
     * @param discriminator
     * @return
     */
    private Discriminator applyDiscriminator(String resultMapId, Class<?> resultType, TypeDiscriminator discriminator) {
        // 有鉴别器才有梦想，否则直接返回空
        if (discriminator != null) {
            // 鉴别器的类值
            String column = discriminator.column();
            // 字段对应的 java 类型
            Class<?> javaType = discriminator.javaType() == void.class ? String.class : discriminator.javaType();
            // 字段对应的 jdbc 类型
            JdbcType jdbcType = discriminator.jdbcType() == JdbcType.UNDEFINED ? null : discriminator.jdbcType();
            // 获取 typeHandler
            @SuppressWarnings("unchecked")
            Class<? extends TypeHandler<?>> typeHandler = (Class<? extends TypeHandler<?>>)
                    (discriminator.typeHandler() == UnknownTypeHandler.class ? null : discriminator.typeHandler());
            // 获取所有的 case
            Case[] cases = discriminator.cases();
            Map<String, String> discriminatorMap = new HashMap<>();
            for (Case c : cases) {
                String value = c.value();
                // 获取 case 的 id
                String caseResultMapId = resultMapId + "-" + value;
                // 保存起来
                discriminatorMap.put(value, caseResultMapId);
            }
            // 构建 discriminator
            return assistant.buildDiscriminator(resultType, column, javaType, jdbcType, typeHandler, discriminatorMap);
        }
        return null;
    }

    /**
     * 解析接口的方法上的 insert update delete select 注解
     *
     * @param method
     */
    void parseStatement(Method method) {
        // 获得参数类型， 如果是单参数，返回参数类型，如果是多参数，返回 ParamMap
        Class<?> parameterTypeClass = getParameterType(method);
        // 获得 LanguageDriver
        LanguageDriver languageDriver = getLanguageDriver(method);
        // 从注解中获得 SqlSource 对象
        SqlSource sqlSource = getSqlSourceFromAnnotations(method, parameterTypeClass, languageDriver);
        // sqlSource 不为空才有一切
        if (sqlSource != null) {
            // 获取 Options 注解， 方法的一些可选项
            Options options = method.getAnnotation(Options.class);
            // 获得 mappedStatementId = 接口名 + 方法名
            final String mappedStatementId = type.getName() + "." + method.getName();
            // 获取各个属性
            Integer fetchSize = null;
            Integer timeout = null;
            StatementType statementType = StatementType.PREPARED;
            ResultSetType resultSetType = null;
            SqlCommandType sqlCommandType = getSqlCommandType(method);
            // 是否是 select
            boolean isSelect = sqlCommandType == SqlCommandType.SELECT;
            boolean flushCache = !isSelect;
            boolean useCache = isSelect;

            // 获得 KeyGenerator 对象
            KeyGenerator keyGenerator;
            String keyProperty = null;
            String keyColumn = null;
            if (SqlCommandType.INSERT.equals(sqlCommandType) || SqlCommandType.UPDATE.equals(sqlCommandType)) {
                // first check for SelectKey annotation - that overrides everything else
                // 获取 selectKey 注解
                SelectKey selectKey = method.getAnnotation(SelectKey.class);
                if (selectKey != null) {
                    // 处理 SelectKey 注解，生成 SelectKeyGenerator 获取主键值得对象
                    keyGenerator = handleSelectKeyAnnotation(selectKey, mappedStatementId, getParameterType(method), languageDriver);
                    keyProperty = selectKey.keyProperty();
                } else if (options == null) {
                    // 如果 selectKey 为空 options 为空， 判断configuration 是否使用自增主键，如果使用，则使用 Jdbc3KeyGenerator 主键生成器
                    keyGenerator = configuration.isUseGeneratedKeys() ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;
                } else {
                    // selectKey 为空 options 不为空， 从options 的值来获取不同的主键生成器，和主键属性，主键列
                    keyGenerator = options.useGeneratedKeys() ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;
                    keyProperty = options.keyProperty();
                    keyColumn = options.keyColumn();
                }
            } else {
                keyGenerator = NoKeyGenerator.INSTANCE;
            }

            // 获取 options 参数
            if (options != null) {
                if (FlushCachePolicy.TRUE.equals(options.flushCache())) {
                    flushCache = true;
                } else if (FlushCachePolicy.FALSE.equals(options.flushCache())) {
                    flushCache = false;
                }
                useCache = options.useCache();
                fetchSize = options.fetchSize() > -1 || options.fetchSize() == Integer.MIN_VALUE ? options.fetchSize() : null; //issue #348
                timeout = options.timeout() > -1 ? options.timeout() : null;
                statementType = options.statementType();
                resultSetType = options.resultSetType();
            }

            // 初始化 resultMap id
            String resultMapId = null;
            ResultMap resultMapAnnotation = method.getAnnotation(ResultMap.class);
            // 如果方法有 ResultMap 注解，则解析注解的值，生成 resultMapId
            if (resultMapAnnotation != null) {
                String[] resultMaps = resultMapAnnotation.value();
                StringBuilder sb = new StringBuilder();
                for (String resultMap : resultMaps) {
                    if (sb.length() > 0) {
                        sb.append(",");
                    }
                    sb.append(resultMap);
                }
                resultMapId = sb.toString();
            } else if (isSelect) {
                // 如果没有 resultMap 注解， 则通过method 解析其他注解生成 resultMapId
                resultMapId = parseResultMap(method);
            }

            // 创建 mappedStatement 对象
            assistant.addMappedStatement(
                    mappedStatementId,
                    sqlSource,
                    statementType,
                    sqlCommandType,
                    fetchSize,
                    timeout,
                    // ParameterMapID
                    null,
                    parameterTypeClass,
                    resultMapId,
                    // 获得返回类型
                    getReturnType(method),
                    resultSetType,
                    flushCache,
                    useCache,
                    // TODO gcode issue #577
                    false,
                    keyGenerator,
                    keyProperty,
                    keyColumn,
                    // DatabaseID
                    null,
                    languageDriver,
                    // ResultSets
                    options != null ? nullOrEmpty(options.resultSets()) : null);
        }
    }

    /**
     * 解析 Lang 注解，获取 LanguageDriver 的类型， 然后从 assistant 的缓存中获取
     *
     * @param method
     * @return
     */
    private LanguageDriver getLanguageDriver(Method method) {
        Lang lang = method.getAnnotation(Lang.class);
        Class<? extends LanguageDriver> langClass = null;
        if (lang != null) {
            langClass = lang.value();
        }
        // 如果为空，获取默认的 languageDriver
        return assistant.getLanguageDriver(langClass);
    }

    /**
     * 获取参数类型，如果就一个参数，直接返回类型，如果多个返回 ParamMap.class
     *
     * @param method
     * @return
     */
    private Class<?> getParameterType(Method method) {
        Class<?> parameterType = null;
        Class<?>[] parameterTypes = method.getParameterTypes();
        // 遍历所有的参数类型
        for (Class<?> currentParameterType : parameterTypes) {
            // 既不是 RowBounds 的子类，也不是 ResultHandler 的子类
            if (!RowBounds.class.isAssignableFrom(currentParameterType) && !ResultHandler.class.isAssignableFrom(currentParameterType)) {
                if (parameterType == null) {
                    // 如果为空，则为当前的参数类型
                    parameterType = currentParameterType;
                } else {
                    // issue #135
                    // 如果不为空，则说明有多个参数类型，则返回 ParamMap.class
                    parameterType = ParamMap.class;
                }
            }
        }
        return parameterType;
    }

    /**
     * 获得方法的返回值类型
     *
     * @param method
     * @return
     */
    private Class<?> getReturnType(Method method) {
        // 获得方法的返回类型
        Class<?> returnType = method.getReturnType();
        // 解析对应的 Type
        Type resolvedReturnType = TypeParameterResolver.resolveReturnType(method, type);
        if (resolvedReturnType instanceof Class) {
            // 如果是普通类
            returnType = (Class<?>) resolvedReturnType;
            if (returnType.isArray()) {
                // 如果是数组类型，返回数组的类型
                returnType = returnType.getComponentType();
            }
            // gcode issue #508
            // 如果返回的 void， 且 ResultType 不为空， 则返回 rt 的 value
            if (void.class.equals(returnType)) {
                ResultType rt = method.getAnnotation(ResultType.class);
                if (rt != null) {
                    returnType = rt.value();
                }
            }
        } else if (resolvedReturnType instanceof ParameterizedType) {
            // 如果是泛型
            ParameterizedType parameterizedType = (ParameterizedType) resolvedReturnType;
            // 获得泛型原始类型， 例如 List， Array
            Class<?> rawType = (Class<?>) parameterizedType.getRawType();
            if (Collection.class.isAssignableFrom(rawType) || Cursor.class.isAssignableFrom(rawType)) {
                // 如果是 Collection 类型 或者 Cursor 类型

                // 获得 <> 实际的类型
                Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
                if (actualTypeArguments != null && actualTypeArguments.length == 1) {
                    // 如果实际类型不为空，且参数列表为1

                    // 获取实际的参数类型
                    Type returnTypeParameter = actualTypeArguments[0];
                    if (returnTypeParameter instanceof Class<?>) {
                        // 如果是普通类，则直接使用
                        returnType = (Class<?>) returnTypeParameter;
                    } else if (returnTypeParameter instanceof ParameterizedType) {
                        // (gcode issue #443) actual type can be a also a parameterized type
                        // 如果还是泛型，获取原始类型，返回
                        returnType = (Class<?>) ((ParameterizedType) returnTypeParameter).getRawType();
                    } else if (returnTypeParameter instanceof GenericArrayType) {
                        // 如果是数组，获取数组的参数类型
                        Class<?> componentType = (Class<?>) ((GenericArrayType) returnTypeParameter).getGenericComponentType();
                        // (gcode issue #525) support List<byte[]>
                        // 返回 Array 类型
                        returnType = Array.newInstance(componentType, 0).getClass();
                    }
                }
            } else if (method.isAnnotationPresent(MapKey.class) && Map.class.isAssignableFrom(rawType)) {
                // 如果是 Map 类型， 且有 MapKey 注解

                // (gcode issue 504) Do not look into Maps if there is not MapKey annotation
                // 获取Map 的 <> 的类型
                Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
                if (actualTypeArguments != null && actualTypeArguments.length == 2) {
                    // 获取的类型不为空，且数量为 2
                    // 获取 value 的类型
                    Type returnTypeParameter = actualTypeArguments[1];
                    if (returnTypeParameter instanceof Class<?>) {
                        // 如果 Map 的 value 类型是普通类，则直接返回
                        returnType = (Class<?>) returnTypeParameter;
                    } else if (returnTypeParameter instanceof ParameterizedType) {
                        // (gcode issue 443) actual type can be a also a parameterized type
                        // 如果 Map 的 value 的类型还是泛型，则获取泛型的 type，返回
                        returnType = (Class<?>) ((ParameterizedType) returnTypeParameter).getRawType();
                    }
                }
            } else if (Optional.class.equals(rawType)) {
                // 如果泛型的实际类型是 Optional
                // 获取 <> 中的实际类型
                Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
                // 因为是 Optional<T> 类型，所以 actualTypeArguments 数组大小是一
                Type returnTypeParameter = actualTypeArguments[0];
                if (returnTypeParameter instanceof Class<?>) {
                    // 如果是普通类，直接返回，看样子 Optional 不支持 泛型参数啊
                    returnType = (Class<?>) returnTypeParameter;
                }
            }
        }

        return returnType;
    }

    /**
     * 获得 SqlSource 对象
     *
     * @param method         解析的接口的方法
     * @param parameterType  参数类型
     * @param languageDriver 语言驱动
     * @return
     */
    private SqlSource getSqlSourceFromAnnotations(Method method, Class<?> parameterType, LanguageDriver languageDriver) {
        try {
            // 获取 sql 类型
            Class<? extends Annotation> sqlAnnotationType = getSqlAnnotationType(method);
            // 获取 sql provider 类型
            Class<? extends Annotation> sqlProviderAnnotationType = getSqlProviderAnnotationType(method);
            if (sqlAnnotationType != null) {
                if (sqlProviderAnnotationType != null) {
                    // 都不为空，则抛出异常
                    throw new BindingException("You cannot supply both a static SQL and SqlProvider to method named " + method.getName());
                }

                // 如果 sqlAnnotationType 不为空，sqlProviderAnnotationType 为空
                Annotation sqlAnnotation = method.getAnnotation(sqlAnnotationType);
                // 获得 value 属性，就是 Sql 语句
                final String[] strings = (String[]) sqlAnnotation.getClass().getMethod("value").invoke(sqlAnnotation);
                // 创建 sqlSource
                return buildSqlSourceFromStrings(strings, parameterType, languageDriver);
            } else if (sqlProviderAnnotationType != null) {
                // 如果 sqlAnnotationType 为空，sqlProviderAnnotationType 不为空

                // 获取注解
                Annotation sqlProviderAnnotation = method.getAnnotation(sqlProviderAnnotationType);
                // 创建 ProviderSqlSource 并返回
                return new ProviderSqlSource(assistant.getConfiguration(), sqlProviderAnnotation, type, method);
            }
            return null;
        } catch (Exception e) {
            throw new BuilderException("Could not find value method on SQL annotation.  Cause: " + e, e);
        }
    }

    /**
     * 创建 SqlSource
     *
     * @param strings            sql 语句
     * @param parameterTypeClass 参数类型，如果是多个参数，则为 ParamMap 类型
     * @param languageDriver     驱动
     * @return
     */
    private SqlSource buildSqlSourceFromStrings(String[] strings, Class<?> parameterTypeClass, LanguageDriver languageDriver) {
        final StringBuilder sql = new StringBuilder();
        // 拼接 SQL
        for (String fragment : strings) {
            sql.append(fragment);
            sql.append(" ");
        }
        // 创建 SqlSource
        return languageDriver.createSqlSource(configuration, sql.toString().trim(), parameterTypeClass);
    }

    /**
     * 获取 sql 类型
     *
     * @param method
     * @return
     */
    private SqlCommandType getSqlCommandType(Method method) {
        // 获取注解的类型
        Class<? extends Annotation> type = getSqlAnnotationType(method);

        if (type == null) {
            // 如果为空，获取 SqlProvider 类型
            // sql 注解 和 sqlProvider 注解，是必须有一个，且不能都有的
            type = getSqlProviderAnnotationType(method);

            if (type == null) {
                // 如果都没有，返回默认的未知类型
                return SqlCommandType.UNKNOWN;
            }

            // 一系列判断
            if (type == SelectProvider.class) {
                type = Select.class;
            } else if (type == InsertProvider.class) {
                type = Insert.class;
            } else if (type == UpdateProvider.class) {
                type = Update.class;
            } else if (type == DeleteProvider.class) {
                type = Delete.class;
            }
        }

        // 获取 SQL 类型枚举
        return SqlCommandType.valueOf(type.getSimpleName().toUpperCase(Locale.ENGLISH));
    }

    /**
     * 获取 SQL 注解的类型
     *
     * @param method
     * @return
     */
    private Class<? extends Annotation> getSqlAnnotationType(Method method) {
        return chooseAnnotationType(method, SQL_ANNOTATION_TYPES);
    }

    /**
     * 获取 SQL Provider 注解的类型
     *
     * @param method
     * @return
     */
    private Class<? extends Annotation> getSqlProviderAnnotationType(Method method) {
        return chooseAnnotationType(method, SQL_PROVIDER_ANNOTATION_TYPES);
    }

    /**
     * 从传入的类型列表中选择一种类型
     * 注意，获取的是注解的类型，不是注解
     *
     * @param method
     * @param types
     * @return
     */
    private Class<? extends Annotation> chooseAnnotationType(Method method, Set<Class<? extends Annotation>> types) {
        // 遍历传入的类型，如果有当前方法有，则返回，如果没有则返回 null
        for (Class<? extends Annotation> type : types) {
            Annotation annotation = method.getAnnotation(type);
            if (annotation != null) {
                return type;
            }
        }
        return null;
    }

    /**
     * 将 @Results 的 value @Result[] 转换成 ResultMapping
     *
     * @param results
     * @param resultType
     * @param resultMappings
     */
    private void applyResults(Result[] results, Class<?> resultType, List<ResultMapping> resultMappings) {
        for (Result result : results) {
            // 创建 ResultFlag 列表
            List<ResultFlag> flags = new ArrayList<>();
            if (result.id()) {
                flags.add(ResultFlag.ID);
            }

            // 获得 TypeHandler
            @SuppressWarnings("unchecked")
            Class<? extends TypeHandler<?>> typeHandler = (Class<? extends TypeHandler<?>>)
                    ((result.typeHandler() == UnknownTypeHandler.class) ? null : result.typeHandler());

            // 构建 ResultMapping
            ResultMapping resultMapping = assistant.buildResultMapping(
                    resultType,
                    nullOrEmpty(result.property()),
                    nullOrEmpty(result.column()),
                    result.javaType() == void.class ? null : result.javaType(),
                    result.jdbcType() == JdbcType.UNDEFINED ? null : result.jdbcType(),
                    hasNestedSelect(result) ? nestedSelectId(result) : null,
                    null,
                    null,
                    null,
                    typeHandler,
                    flags,
                    null,
                    null,
                    isLazy(result));
            resultMappings.add(resultMapping);
        }
    }

    /**
     * 获得内嵌查询的ID
     *
     * @param result
     * @return
     */
    private String nestedSelectId(Result result) {
        // 先获得 one 注解
        String nestedSelect = result.one().select();
        if (nestedSelect.length() < 1) {
            // 如果没有 one ， 就是 many 喽
            nestedSelect = result.many().select();
        }
        // 获得内嵌编号
        if (!nestedSelect.contains(".")) {
            nestedSelect = type.getName() + "." + nestedSelect;
        }
        return nestedSelect;
    }

    /**
     * 是否懒加载
     *
     * @param result
     * @return
     */
    private boolean isLazy(Result result) {
        // 判断是否开启了懒加载
        boolean isLazy = configuration.isLazyLoadingEnabled();
        // 如果有 one ，判断是否懒加载
        if (result.one().select().length() > 0 && FetchType.DEFAULT != result.one().fetchType()) {
            isLazy = result.one().fetchType() == FetchType.LAZY;
        } else if (result.many().select().length() > 0 && FetchType.DEFAULT != result.many().fetchType()) {
            // 如果有 many ，判断是否懒加载
            isLazy = result.many().fetchType() == FetchType.LAZY;
        }
        // 如果都没有，使用配置的是否开启懒加载
        return isLazy;
    }

    /**
     * 是否有内嵌查询
     *
     * @param result
     * @return
     */
    private boolean hasNestedSelect(Result result) {
        // one 和  many 不能同时存在
        if (result.one().select().length() > 0 && result.many().select().length() > 0) {
            throw new BuilderException("Cannot use both @One and @Many annotations in the same @Result");
        }
        // 判断有 one 或者 many
        return result.one().select().length() > 0 || result.many().select().length() > 0;
    }

    /**
     * 把 @ConstructorArgs 的 value @Arg[] 解析成 ResultMapping
     *
     * @param args
     * @param resultType
     * @param resultMappings
     */
    private void applyConstructorArgs(Arg[] args, Class<?> resultType, List<ResultMapping> resultMappings) {
        for (Arg arg : args) {
            // 创建 ResultFlag 集合
            List<ResultFlag> flags = new ArrayList<>();
            flags.add(ResultFlag.CONSTRUCTOR);
            if (arg.id()) {
                // 如果参数类型是 id
                flags.add(ResultFlag.ID);
            }

            // 获得 TypeHandler 如果 arg.typeHandler 是 UnknownTypeHandler 则为空
            @SuppressWarnings("unchecked")
            Class<? extends TypeHandler<?>> typeHandler = (Class<? extends TypeHandler<?>>)
                    (arg.typeHandler() == UnknownTypeHandler.class ? null : arg.typeHandler());

            // 构建 ResultMapping 对象
            ResultMapping resultMapping = assistant.buildResultMapping(
                    resultType,
                    nullOrEmpty(arg.name()),
                    nullOrEmpty(arg.column()),
                    arg.javaType() == void.class ? null : arg.javaType(),
                    arg.jdbcType() == JdbcType.UNDEFINED ? null : arg.jdbcType(),
                    nullOrEmpty(arg.select()),
                    nullOrEmpty(arg.resultMap()),
                    null,
                    nullOrEmpty(arg.columnPrefix()),
                    typeHandler,
                    flags,
                    null,
                    null,
                    false);
            resultMappings.add(resultMapping);
        }
    }

    private String nullOrEmpty(String value) {
        return value == null || value.trim().length() == 0 ? null : value;
    }

    /**
     * 如果 result 注解为空，则返回一个空数组
     *
     * @param results
     * @return
     */
    private Result[] resultsIf(Results results) {
        return results == null ? new Result[0] : results.value();
    }

    /**
     * 如果 args 为空则返回 一个空的 Arg 数组
     *
     * @param args
     * @return
     */
    private Arg[] argsIf(ConstructorArgs args) {
        return args == null ? new Arg[0] : args.value();
    }

    /**
     * 处理 SelectKey 注解，生成 SelectKeyGenerator 对象
     *
     * @param selectKeyAnnotation
     * @param baseStatementId
     * @param parameterTypeClass
     * @param languageDriver
     * @return
     */
    private KeyGenerator handleSelectKeyAnnotation(SelectKey selectKeyAnnotation, String baseStatementId, Class<?> parameterTypeClass, LanguageDriver languageDriver) {
        // 获取 selectKeyAnnotation 的各种属性
        // baseStatementId + !selectKey
        String id = baseStatementId + SelectKeyGenerator.SELECT_KEY_SUFFIX;
        Class<?> resultTypeClass = selectKeyAnnotation.resultType();
        StatementType statementType = selectKeyAnnotation.statementType();
        String keyProperty = selectKeyAnnotation.keyProperty();
        String keyColumn = selectKeyAnnotation.keyColumn();
        boolean executeBefore = selectKeyAnnotation.before();

        // defaults
        // 创建 MappedStatement 需要用到的默认值
        boolean useCache = false;
        KeyGenerator keyGenerator = NoKeyGenerator.INSTANCE;
        Integer fetchSize = null;
        Integer timeout = null;
        boolean flushCache = false;
        String parameterMap = null;
        String resultMap = null;
        ResultSetType resultSetTypeEnum = null;

        // 创建获取主键值得 sqlSource
        SqlSource sqlSource = buildSqlSourceFromStrings(selectKeyAnnotation.statement(), parameterTypeClass, languageDriver);
        SqlCommandType sqlCommandType = SqlCommandType.SELECT;

        // 创建获取主键值得 mappedStatement 对象
        assistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType, fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass, resultSetTypeEnum,
                flushCache, useCache, false,
                keyGenerator, keyProperty, keyColumn, null, languageDriver, null);

        id = assistant.applyCurrentNamespace(id, false);

        // 获得 mapperStatement
        MappedStatement keyStatement = configuration.getMappedStatement(id, false);

        // 获取 SelectKeyGenerator 对象，添加到 configuration 中，并返回
        SelectKeyGenerator answer = new SelectKeyGenerator(keyStatement, executeBefore);
        configuration.addKeyGenerator(id, answer);
        return answer;
    }

}
