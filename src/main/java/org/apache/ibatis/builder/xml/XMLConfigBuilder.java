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

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.loader.ProxyFactory;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.type.JdbcType;

import javax.sql.DataSource;
import java.io.InputStream;
import java.io.Reader;
import java.util.Properties;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLConfigBuilder extends BaseBuilder {

    /**
     * 是否已解析
     */
    private boolean parsed;
    /**
     * 解析器
     */
    private final XPathParser parser;
    /**
     * mybatis 环境
     */
    private String environment;
    /**
     * 默认反射工厂
     */
    private final ReflectorFactory localReflectorFactory = new DefaultReflectorFactory();

    public XMLConfigBuilder(Reader reader) {
        this(reader, null, null);
    }

    // ---------------- 以下 XMLConfigBuilder 的各种重载 ------------------------------
    public XMLConfigBuilder(Reader reader, String environment) {
        this(reader, environment, null);
    }

    public XMLConfigBuilder(Reader reader, String environment, Properties props) {
        this(new XPathParser(reader, true, props, new XMLMapperEntityResolver()), environment, props);
    }

    public XMLConfigBuilder(InputStream inputStream) {
        this(inputStream, null, null);
    }

    public XMLConfigBuilder(InputStream inputStream, String environment) {
        this(inputStream, environment, null);
    }

    public XMLConfigBuilder(InputStream inputStream, String environment, Properties props) {
        this(new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()), environment, props);
    }

    private XMLConfigBuilder(XPathParser parser, String environment, Properties props) {
        // 创建 Configuration 对象
        super(new Configuration());
        ErrorContext.instance().resource("SQL Mapper Configuration");
        this.configuration.setVariables(props);
        this.parsed = false;
        this.environment = environment;
        this.parser = parser;
    }

    /**
     * 解析 XML 配置文件
     *
     * @return
     */
    public Configuration parse() {
        if (parsed) {
            // 如果已解析，则抛出异常
            throw new BuilderException("Each XMLConfigBuilder can only be used once.");
        }
        parsed = true;
        // 标记已解析
        parseConfiguration(parser.evalNode("/configuration"));
        return configuration;
    }

    /**
     * 执行 xml Configuration 的解析
     *
     * @param root
     */
    private void parseConfiguration(XNode root) {
        try {
            //issue #117 read properties first
            // 1，解析 <properties> 标签
            propertiesElement(root.evalNode("properties"));
            // 2, 解析 <setting> 标签
            Properties settings = settingsAsProperties(root.evalNode("settings"));
            // 3, 加载自定义 VFS 实现 setting 标签下的 vfsImpl 标签
            loadCustomVfs(settings);
            // 4, 加载自定义 Log 实现 setting 标签下的 logImpl 标签
            loadCustomLogImpl(settings);
            // 5, 解析 typeAliases 标签
            typeAliasesElement(root.evalNode("typeAliases"));
            // 6, 解析 plugins 标签
            pluginElement(root.evalNode("plugins"));
            // 7, 解析 objectFactory 标签
            objectFactoryElement(root.evalNode("objectFactory"));
            // 8, 解析 objectWrapperFactory 标签
            objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));
            // 9, 解析 reflectorFactory 标签
            reflectorFactoryElement(root.evalNode("reflectorFactory"));
            // 10, setting 各种配置设置到 configuration 中
            settingsElement(settings);
            // read it after objectFactory and objectWrapperFactory issue #631
            // 11, 解析 environments 标签
            environmentsElement(root.evalNode("environments"));
            // 12, 解析 databaseIdProvider 标签
            databaseIdProviderElement(root.evalNode("databaseIdProvider"));
            // 13, 解析 typeHandlers 标签
            typeHandlerElement(root.evalNode("typeHandlers"));
            // 14, 解析 mappers 标签
            mapperElement(root.evalNode("mappers"));
        } catch (Exception e) {
            throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
        }
    }

    /**
     * 解析 settings 标签
     *
     * @param context
     * @return
     */
    private Properties settingsAsProperties(XNode context) {
        if (context == null) {
            // 如果 setting 节点为空，则返回一个空的属性，尽量不返回 null 原则？
            return new Properties();
        }
        // 获取 settings 中的 name-value 属性
        Properties props = context.getChildrenAsProperties();
        // Check that all settings are known to the configuration class
        // 获取 Configuration 的类的元数据
        MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);
        // 遍历 settings 的属性，如果这个属性在 Configuration 中没有 setter 方法，说明有问题的配置，抛出异常。
        for (Object key : props.keySet()) {
            if (!metaConfig.hasSetter(String.valueOf(key))) {
                throw new BuilderException("The setting " + key + " is not known.  Make sure you spelled it correctly (case sensitive).");
            }
        }
        // 返回所有的 settings 的属性
        return props;
    }

    /**
     * 加载自定义的 VFS
     *
     * @param props
     * @throws ClassNotFoundException
     */
    private void loadCustomVfs(Properties props) throws ClassNotFoundException {
        // 获取 settings 标签中的 vfsImpl 的值
        String value = props.getProperty("vfsImpl");
        if (value != null) {
            // 如果没有就算了，如果不为空，处理
            // 根据逗号分隔，看是否是多个
            String[] clazzes = value.split(",");
            for (String clazz : clazzes) {
                // 遍历所有的值
                if (!clazz.isEmpty()) {
                    // 最终通过类加载器，加载指定的类
                    @SuppressWarnings("unchecked")
                    Class<? extends VFS> vfsImpl = (Class<? extends VFS>) Resources.classForName(clazz);
                    // 把指定的 VFS 实现类设置到 configuration 中
                    configuration.setVfsImpl(vfsImpl);
                }
            }
        }
    }

    /**
     * 加载 setting 配置中的 logImpl, mybatis 使用指定的日志
     *
     * @param props
     */
    private void loadCustomLogImpl(Properties props) {
        Class<? extends Log> logImpl = resolveClass(props.getProperty("logImpl"));
        configuration.setLogImpl(logImpl);
    }

    /**
     * 解析 typeAliases 标签
     * 将配置类注册到 typeAliasRegistry
     *
     * @param parent
     */
    private void typeAliasesElement(XNode parent) {
        if (parent != null) {
            // 遍历子节点
            for (XNode child : parent.getChildren()) {
                if ("package".equals(child.getName())) {
                    // 如果节点名称是 package 则遍历包下所有的类
                    // 获取包名
                    String typeAliasPackage = child.getStringAttribute("name");
                    // 注册包下所有的类，注意这里是 registerAliases 不是 registerAlias
                    // 最终在 configuration 的 typeAliasRegistry 中，以类的 simpleName-类 的形式保存
                    configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);
                } else {
                    // 如果不是 package 则 获取 alias type
                    String alias = child.getStringAttribute("alias");
                    String type = child.getStringAttribute("type");
                    try {
                        // 加载 type 表示的类，如果加载不到，这里就抛异常了，所以下边注册的时候，都没校验类
                        Class<?> clazz = Resources.classForName(type);
                        // 如果 alias 不为空，则 alias-class 放入 typeAliasRegistry 中，如果 alias 为空，则先判断类上有没有别名注解
                        // 有的话用注解上的 aliasName-class 放入 typeAliasRegistry 中，如果没有注解，则 simpleClassName-class 放入 typeAliasRegistry
                        if (alias == null) {
                            typeAliasRegistry.registerAlias(clazz);
                        } else {
                            typeAliasRegistry.registerAlias(alias, clazz);
                        }
                    } catch (ClassNotFoundException e) {
                        // 这里捕获了加载不到类的异常，抛出了绑定异常
                        throw new BuilderException("Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
                    }
                }
            }
        }
    }

    /**
     * 遍历 plugin 标签
     *
     * @param parent
     * @throws Exception
     */
    private void pluginElement(XNode parent) throws Exception {
        if (parent != null) {
            for (XNode child : parent.getChildren()) {
                // 遍历节点

                // 获取拦截器
                String interceptor = child.getStringAttribute("interceptor");
                // 获取拦截器的 name-value 属性
                Properties properties = child.getChildrenAsProperties();
                // 获取拦截器实例
                Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor).newInstance();
                // 设置拦截器属性
                interceptorInstance.setProperties(properties);
                // 往 configuration 中的拦截器链增加拦截器实例
                configuration.addInterceptor(interceptorInstance);
            }
        }
    }

    /**
     * 解析 objectFactory 标签
     *
     * @param context
     * @throws Exception
     */
    private void objectFactoryElement(XNode context) throws Exception {
        if (context != null) {
            // 获取标签的 type 属性
            String type = context.getStringAttribute("type");
            // 获取标签的 name-value 子节点
            Properties properties = context.getChildrenAsProperties();
            // 解析类，创建 ObjectFactory 子类实例
            ObjectFactory factory = (ObjectFactory) resolveClass(type).newInstance();
            // 设置 factory 的属性
            factory.setProperties(properties);
            // 自定义的 ObjectFactory 替代 DefaultObjectFactory
            configuration.setObjectFactory(factory);
        }
    }

    /**
     * 解析 objectWrapperFactory 标签
     *
     * @param context
     * @throws Exception
     */
    private void objectWrapperFactoryElement(XNode context) throws Exception {
        if (context != null) {
            // 获取标签的 type 属性
            String type = context.getStringAttribute("type");
            // 解析类，创建 ObjectWrapperFactory 子类实例
            ObjectWrapperFactory factory = (ObjectWrapperFactory) resolveClass(type).newInstance();
            // 自定义的 ObjectWrapperFactory 替代 DefaultObjectWrapperFactory
            configuration.setObjectWrapperFactory(factory);
        }
    }

    /**
     * 解析 reflectorFactory 标签， 使用自定义的 reflectorFactory 替换 DefaultReflectFactory
     *
     * @param context
     * @throws Exception
     */
    private void reflectorFactoryElement(XNode context) throws Exception {
        if (context != null) {
            String type = context.getStringAttribute("type");
            ReflectorFactory factory = (ReflectorFactory) resolveClass(type).newInstance();
            configuration.setReflectorFactory(factory);
        }
    }

    /**
     * 解析 mybatis-config.xml 的 properties 标签
     *
     * @param context
     * @throws Exception
     */
    private void propertiesElement(XNode context) throws Exception {
        if (context != null) {
            // 获取 key 为 name， 属性为 value 的属性
            Properties defaults = context.getChildrenAsProperties();
            // 获取 resource 属性
            String resource = context.getStringAttribute("resource");
            // 获取 URL 属性
            String url = context.getStringAttribute("url");
            // resource 和 URL 这俩属性不能共存
            if (resource != null && url != null) {
                throw new BuilderException("The properties element cannot specify both a URL and a resource based property file reference.  Please specify one or the other.");
            }
            if (resource != null) {
                // 通过流获取属性，并 put 到 defaults 中
                defaults.putAll(Resources.getResourceAsProperties(resource));
            } else if (url != null) {
                // 通过流获取属性，并 put 到 defaults 中
                defaults.putAll(Resources.getUrlAsProperties(url));
            }
            // 创建 XMLConfigBuilder 的时候传进来的 props， 当时放入了 configuration 中了。也要放到 defaults 中
            Properties vars = configuration.getVariables();
            if (vars != null) {
                defaults.putAll(vars);
            }
            // 设置到 XPathParser 中 用来替换需要动态配置的属性值
            parser.setVariables(defaults);
            // 再把所有的配置放到 configuration 中
            configuration.setVariables(defaults);
        }
    }

    /**
     * 设置 settings 的属性到 configuration 中
     * 前面解析 settings 属性的时候已经校验过了，设置的值，都是在Configuration 中有 setter 方法的
     *
     * @param props
     */
    private void settingsElement(Properties props) {
        configuration.setAutoMappingBehavior(AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));
        configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior.valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));
        configuration.setCacheEnabled(booleanValueOf(props.getProperty("cacheEnabled"), true));
        configuration.setProxyFactory((ProxyFactory) createInstance(props.getProperty("proxyFactory")));
        configuration.setLazyLoadingEnabled(booleanValueOf(props.getProperty("lazyLoadingEnabled"), false));
        configuration.setAggressiveLazyLoading(booleanValueOf(props.getProperty("aggressiveLazyLoading"), false));
        configuration.setMultipleResultSetsEnabled(booleanValueOf(props.getProperty("multipleResultSetsEnabled"), true));
        configuration.setUseColumnLabel(booleanValueOf(props.getProperty("useColumnLabel"), true));
        configuration.setUseGeneratedKeys(booleanValueOf(props.getProperty("useGeneratedKeys"), false));
        configuration.setDefaultExecutorType(ExecutorType.valueOf(props.getProperty("defaultExecutorType", "SIMPLE")));
        configuration.setDefaultStatementTimeout(integerValueOf(props.getProperty("defaultStatementTimeout"), null));
        configuration.setDefaultFetchSize(integerValueOf(props.getProperty("defaultFetchSize"), null));
        configuration.setMapUnderscoreToCamelCase(booleanValueOf(props.getProperty("mapUnderscoreToCamelCase"), false));
        configuration.setSafeRowBoundsEnabled(booleanValueOf(props.getProperty("safeRowBoundsEnabled"), false));
        configuration.setLocalCacheScope(LocalCacheScope.valueOf(props.getProperty("localCacheScope", "SESSION")));
        configuration.setJdbcTypeForNull(JdbcType.valueOf(props.getProperty("jdbcTypeForNull", "OTHER")));
        configuration.setLazyLoadTriggerMethods(stringSetValueOf(props.getProperty("lazyLoadTriggerMethods"), "equals,clone,hashCode,toString"));
        configuration.setSafeResultHandlerEnabled(booleanValueOf(props.getProperty("safeResultHandlerEnabled"), true));
        configuration.setDefaultScriptingLanguage(resolveClass(props.getProperty("defaultScriptingLanguage")));
        configuration.setDefaultEnumTypeHandler(resolveClass(props.getProperty("defaultEnumTypeHandler")));
        configuration.setCallSettersOnNulls(booleanValueOf(props.getProperty("callSettersOnNulls"), false));
        configuration.setUseActualParamName(booleanValueOf(props.getProperty("useActualParamName"), true));
        configuration.setReturnInstanceForEmptyRow(booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));
        configuration.setLogPrefix(props.getProperty("logPrefix"));
        configuration.setConfigurationFactory(resolveClass(props.getProperty("configurationFactory")));
    }

    /**
     * 解析 environments 标签
     * environments 下 可以有多个 environment 的，所有 environments 标签有个默认的 default 属性
     *
     * @param context
     * @throws Exception
     */
    private void environmentsElement(XNode context) throws Exception {
        if (context != null) {
            if (environment == null) {
                // 如果 environment 为空，则获取 environment 标签的 default 属性，获取默认的环境
                environment = context.getStringAttribute("default");
            }
            // 遍历所有的子节点
            for (XNode child : context.getChildren()) {
                // 获取 environment 的 id 属性
                String id = child.getStringAttribute("id");
                if (isSpecifiedEnvironment(id)) {
                    // 检查是否是默认的环境，如果是则处理

                    // 获取事务管理器子节点，创建事务管理器工厂
                    TransactionFactory txFactory = transactionManagerElement(child.evalNode("transactionManager"));
                    // 获取数据源子节点，创建数据源工厂
                    DataSourceFactory dsFactory = dataSourceElement(child.evalNode("dataSource"));
                    // 创建数据源
                    DataSource dataSource = dsFactory.getDataSource();
                    // 创建 Environment
                    Environment.Builder environmentBuilder = new Environment.Builder(id)
                            .transactionFactory(txFactory)
                            .dataSource(dataSource);
                    // 赋值给 configuration
                    configuration.setEnvironment(environmentBuilder.build());
                }
            }
        }
    }

    /**
     * 解析 databaseIdProvider 标签
     * 数据源都要实现 JDK 的 DatabaseMetaData 接口，所以最终调取这个接口的 getDatabaseProductName 方法获取的数据库 ID
     *
     * @param context
     * @throws Exception
     */
    private void databaseIdProviderElement(XNode context) throws Exception {
        DatabaseIdProvider databaseIdProvider = null;
        if (context != null) {
            // 数据库类型
            String type = context.getStringAttribute("type");
            // awful patch to keep backward compatibility
            // 可怕的补丁，以保持向后兼容性
            if ("VENDOR".equals(type)) {
                type = "DB_VENDOR";
            }
            // 获取 name-value 属性
            Properties properties = context.getChildrenAsProperties();
            databaseIdProvider = (DatabaseIdProvider) resolveClass(type).newInstance();
            databaseIdProvider.setProperties(properties);
        }
        Environment environment = configuration.getEnvironment();
        if (environment != null && databaseIdProvider != null) {
            // 获取 数据库 ID ，并赋值给 configuration
            String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());
            configuration.setDatabaseId(databaseId);
        }
    }

    /**
     * 解析 environment 下的 transactionManager 标签，获取事务工厂
     *
     * @param context
     * @return
     * @throws Exception
     */
    private TransactionFactory transactionManagerElement(XNode context) throws Exception {
        if (context != null) {
            // 获取 type 属性，一般为 JDBC
            String type = context.getStringAttribute("type");
            // 遍历 name-value 属性
            Properties props = context.getChildrenAsProperties();
            // 获取事务工厂
            TransactionFactory factory = (TransactionFactory) resolveClass(type).newInstance();
            // 设置属性并返回
            factory.setProperties(props);
            return factory;
        }
        throw new BuilderException("Environment declaration requires a TransactionFactory.");
    }

    /**
     * 解析 environment 下的 datasource 标签
     *
     * @param context
     * @return
     * @throws Exception
     */
    private DataSourceFactory dataSourceElement(XNode context) throws Exception {
        if (context != null) {
            // 获取数据源类型
            String type = context.getStringAttribute("type");
            // 获取数据源的 name-value 属性
            Properties props = context.getChildrenAsProperties();
            // 创建 DataSourceFactory 实例
            DataSourceFactory factory = (DataSourceFactory) resolveClass(type).newInstance();
            factory.setProperties(props);
            return factory;
        }
        throw new BuilderException("Environment declaration requires a DataSourceFactory.");
    }

    /**
     * 解析 typeHandlers 标签
     *
     * @param parent
     */
    private void typeHandlerElement(XNode parent) {
        if (parent != null) {
            // 遍历子节点
            for (XNode child : parent.getChildren()) {
                if ("package".equals(child.getName())) {
                    // 如果是 package 标签
                    // 则获取 name 属性 包名
                    String typeHandlerPackage = child.getStringAttribute("name");
                    // 把包名下的所有类，都注册到 typeHandlerRegistry 中
                    typeHandlerRegistry.register(typeHandlerPackage);
                } else {
                    // 如果不是 包名， 则获取 javaType jdbcType handler
                    String javaTypeName = child.getStringAttribute("javaType");
                    String jdbcTypeName = child.getStringAttribute("jdbcType");
                    String handlerTypeName = child.getStringAttribute("handler");
                    // 如果 typeAliasRegistry 中有了，这直接过去，如果没有，则通过类加载器，加载进来
                    Class<?> javaTypeClass = resolveClass(javaTypeName);
                    // 获取 JdbcType
                    JdbcType jdbcType = resolveJdbcType(jdbcTypeName);
                    // 获取 handler 类，方法同 javaTypeClass
                    Class<?> typeHandlerClass = resolveClass(handlerTypeName);

                    // 根据不同的值是否存在的情况，进行不同方法的保存
                    // TypeHandler 最终目的是 通过自定义的 TypeHandler 进行 JavaType 和 JdbcType 的互相转换
                    // typeHandlerRegistry 有两个缓存 TYPE_HANDLER_MAP 和 ALL_TYPE_HANDLERS_MAP
                    // TYPE_HANDLER_MAP key = javaType value = map （map 的 key = jdbcType, value = handler）
                    // ALL_TYPE_HANDLERS_MAP key = handler 的类型 value = handler
                    if (javaTypeClass != null) {
                        if (jdbcType == null) {
                            // 如果 JavaType 不为空 JdbcType 为空， 则存放为 null -> (jdbcType -> handler)
                            typeHandlerRegistry.register(javaTypeClass, typeHandlerClass);
                        } else {
                            // 如果 JavaType 不为空 JdbcType 不为空， 则存放为 javaType -> (jdbcType -> handler)
                            typeHandlerRegistry.register(javaTypeClass, jdbcType, typeHandlerClass);
                        }
                    } else {
                        // 如果 JavaType 为空 JdbcType 为空， 则存放为 null -> (null -> handler)
                        // 所以这样的只有一个，后面的会替换前面的
                        typeHandlerRegistry.register(typeHandlerClass);
                    }
                }
            }
        }
    }

    /**
     * 解析 mappers 标签
     *
     * @param parent
     * @throws Exception
     */
    private void mapperElement(XNode parent) throws Exception {
        if (parent != null) {
            for (XNode child : parent.getChildren()) {
                if ("package".equals(child.getName())) {
                    String mapperPackage = child.getStringAttribute("name");
                    // 扫描指定包把 mapper 对象添加 到 configuration 的 mapperRegistry 中
                    configuration.addMappers(mapperPackage);
                } else {
                    // 如果不是包

                    // 获取 resource
                    String resource = child.getStringAttribute("resource");
                    // 获取 url
                    String url = child.getStringAttribute("url");
                    // 获取 class
                    String mapperClass = child.getStringAttribute("class");
                    if (resource != null && url == null && mapperClass == null) {
                        // 如果是 resource
                        ErrorContext.instance().resource(resource);
                        InputStream inputStream = Resources.getResourceAsStream(resource);
                        XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments());
                        mapperParser.parse();
                    } else if (resource == null && url != null && mapperClass == null) {
                        // 如果是 url
                        ErrorContext.instance().resource(url);
                        InputStream inputStream = Resources.getUrlAsStream(url);
                        XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, url, configuration.getSqlFragments());
                        mapperParser.parse();
                    } else if (resource == null && url == null && mapperClass != null) {
                        // 如果是 class 则通过类加载器加载类对象，放入到 configuration 的 mapperRegister 中
                        Class<?> mapperInterface = Resources.classForName(mapperClass);
                        configuration.addMapper(mapperInterface);
                    } else {
                        // mapper 的三个标签只能有一个
                        throw new BuilderException("A mapper element may only specify a url, resource or class, but not more than one.");
                    }
                }
            }
        }
    }

    /**
     * 检查是否是默认的环境
     *
     * @param id
     * @return
     */
    private boolean isSpecifiedEnvironment(String id) {
        if (environment == null) {
            throw new BuilderException("No environment specified.");
        } else if (id == null) {
            throw new BuilderException("Environment requires an id attribute.");
        } else if (environment.equals(id)) {
            return true;
        }
        return false;
    }

}
