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

import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.SqlSourceBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.session.Configuration;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/**
 * 实现 SqlSource 接口，基于方法上的 @ProviderXXX 注解的 SqlSource 实现类
 *
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class ProviderSqlSource implements SqlSource {

    /**
     * 配置
     */
    private final Configuration configuration;
    /**
     * SqlSourceBuilder
     */
    private final SqlSourceBuilder sqlSourceParser;
    /**
     * @ProviderXXX 注解的类
     */
    private final Class<?> providerType;
    /**
     * @ProviderXXX 注解对象的方法
     */
    private Method providerMethod;
    /**
     * @ProviderXXX 注解对应的方法的参数名
     */
    private String[] providerMethodArgumentNames;
    /**
     * 参数对应的参数类型
     */
    private Class<?>[] providerMethodParameterTypes;
    /**
     * 如果 providerMethodParameterTypes 中包含 ProviderContext ，则创建 ProviderContext
     */
    private ProviderContext providerContext;
    /**
     * 如果有 ProviderContext ，则 ProviderContext 所在的位置
     */
    private Integer providerContextIndex;

    /**
     * @deprecated Please use the {@link #ProviderSqlSource(Configuration, Object, Class, Method)} instead of this.
     */
    @Deprecated
    public ProviderSqlSource(Configuration configuration, Object provider) {
        this(configuration, provider, null, null);
    }

    /**
     * @since 3.4.5
     */
    public ProviderSqlSource(Configuration configuration, Object provider, Class<?> mapperType, Method mapperMethod) {
        // 方法名
        String providerMethodName;
        try {
            this.configuration = configuration;
            // 创建 SqlSourceBuilder 对象
            this.sqlSourceParser = new SqlSourceBuilder(configuration);
            // 获得 @ProviderXXX 注解的对应的类
            this.providerType = (Class<?>) provider.getClass().getMethod("type").invoke(provider);
            // 提供SQL的方法
            providerMethodName = (String) provider.getClass().getMethod("method").invoke(provider);

            for (Method m : this.providerType.getMethods()) {
                // 遍历 providerType 的方法

                // 如果找到提供Sql 的方法，且返回值是字符串
                if (providerMethodName.equals(m.getName()) && CharSequence.class.isAssignableFrom(m.getReturnType())) {
                    if (providerMethod != null) {
                        throw new BuilderException("Error creating SqlSource for SqlProvider. Method '"
                                + providerMethodName + "' is found multiple in SqlProvider '" + this.providerType.getName()
                                + "'. Sql provider method can not overload.");
                    }
                    this.providerMethod = m;
                    this.providerMethodArgumentNames = new ParamNameResolver(configuration, m).getNames();
                    this.providerMethodParameterTypes = m.getParameterTypes();
                }
            }
        } catch (BuilderException e) {
            throw e;
        } catch (Exception e) {
            throw new BuilderException("Error creating SqlSource for SqlProvider.  Cause: " + e, e);
        }
        if (this.providerMethod == null) {
            throw new BuilderException("Error creating SqlSource for SqlProvider. Method '"
                    + providerMethodName + "' not found in SqlProvider '" + this.providerType.getName() + "'.");
        }

        // 初始化 providerContext 和 providerContextIndex 属性
        for (int i = 0; i < this.providerMethodParameterTypes.length; i++) {
            Class<?> parameterType = this.providerMethodParameterTypes[i];
            if (parameterType == ProviderContext.class) {
                if (this.providerContext != null) {
                    throw new BuilderException("Error creating SqlSource for SqlProvider. ProviderContext found multiple in SqlProvider method ("
                            + this.providerType.getName() + "." + providerMethod.getName()
                            + "). ProviderContext can not define multiple in SqlProvider method argument.");
                }
                this.providerContext = new ProviderContext(mapperType, mapperMethod);
                this.providerContextIndex = i;
            }
        }
    }

    @Override
    public BoundSql getBoundSql(Object parameterObject) {
        // 创建 SqlSource 对象
        SqlSource sqlSource = createSqlSource(parameterObject);
        // 获取 BoundSql
        return sqlSource.getBoundSql(parameterObject);
    }

    /**
     * 创建 SqlSource 对象
     *
     * @param parameterObject
     * @return
     */
    private SqlSource createSqlSource(Object parameterObject) {
        try {
            // 参数数量
            int bindParameterCount = providerMethodParameterTypes.length - (providerContext == null ? 0 : 1);
            // 获得 SQL
            String sql;
            if (providerMethodParameterTypes.length == 0) {
                // 如果没有参数
                sql = invokeProviderMethod();
            } else if (bindParameterCount == 0) {
                // 如果有参数，但是bind参数个数为0，则参数类型为 providerContext
                sql = invokeProviderMethod(providerContext);
            } else if (bindParameterCount == 1
                    && (parameterObject == null || providerMethodParameterTypes[providerContextIndex == null || providerContextIndex == 1 ? 0 : 1].isAssignableFrom(parameterObject.getClass()))) {
                sql = invokeProviderMethod(extractProviderMethodArguments(parameterObject));
            } else if (parameterObject instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> params = (Map<String, Object>) parameterObject;
                sql = invokeProviderMethod(extractProviderMethodArguments(params, providerMethodArgumentNames));
            } else {
                throw new BuilderException("Error invoking SqlProvider method ("
                        + providerType.getName() + "." + providerMethod.getName()
                        + "). Cannot invoke a method that holds "
                        + (bindParameterCount == 1 ? "named argument(@Param)" : "multiple arguments")
                        + " using a specifying parameterObject. In this case, please specify a 'java.util.Map' object.");
            }
            // 获得参数
            Class<?> parameterType = parameterObject == null ? Object.class : parameterObject.getClass();
            // 替换掉 Sql 上的属性，解析出 SqlSource 对象
            return sqlSourceParser.parse(replacePlaceholder(sql), parameterType, new HashMap<>());
        } catch (BuilderException e) {
            throw e;
        } catch (Exception e) {
            throw new BuilderException("Error invoking SqlProvider method ("
                    + providerType.getName() + "." + providerMethod.getName()
                    + ").  Cause: " + e, e);
        }
    }

    /**
     * 获得方法参数
     *
     * @param parameterObject
     * @return
     */
    private Object[] extractProviderMethodArguments(Object parameterObject) {
        if (providerContext != null) {
            Object[] args = new Object[2];
            args[providerContextIndex == 0 ? 1 : 0] = parameterObject;
            args[providerContextIndex] = providerContext;
            return args;
        } else {
            return new Object[]{parameterObject};
        }
    }

    private Object[] extractProviderMethodArguments(Map<String, Object> params, String[] argumentNames) {
        Object[] args = new Object[argumentNames.length];
        for (int i = 0; i < args.length; i++) {
            if (providerContextIndex != null && providerContextIndex == i) {
                args[i] = providerContext;
            } else {
                args[i] = params.get(argumentNames[i]);
            }
        }
        return args;
    }

    /**
     * 调用 SQL 提供者的方法，这些方法返回的 String 就是 SQL
     *
     * @param args
     * @return
     * @throws Exception
     */
    private String invokeProviderMethod(Object... args) throws Exception {
        Object targetObject = null;
        // 检查，是否是静态的，如果是静态的就不用实例化对象了。
        if (!Modifier.isStatic(providerMethod.getModifiers())) {
            targetObject = providerType.newInstance();
        }
        CharSequence sql = (CharSequence) providerMethod.invoke(targetObject, args);
        return sql != null ? sql.toString() : null;
    }

    private String replacePlaceholder(String sql) {
        return PropertyParser.parse(sql, configuration.getVariables());
    }

}
