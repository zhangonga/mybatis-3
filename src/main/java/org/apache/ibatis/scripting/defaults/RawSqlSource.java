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
package org.apache.ibatis.scripting.defaults;

import org.apache.ibatis.builder.SqlSourceBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.scripting.xmltags.DynamicContext;
import org.apache.ibatis.scripting.xmltags.DynamicSqlSource;
import org.apache.ibatis.scripting.xmltags.SqlNode;
import org.apache.ibatis.session.Configuration;

import java.util.HashMap;

/**
 * Static SqlSource. It is faster than {@link DynamicSqlSource} because mappings are
 * calculated during startup.
 * 实现 SqlSource 接口，原始的 SqlSource 实现类
 * 适用于仅使用 #{} 表达式
 * 或者不使用任何表达式的情况，所以它是静态的，仅需要在构造方法中，直接生成对应的 SQL
 *
 * @author Eduardo Macarron
 * @since 3.2.0
 */
public class RawSqlSource implements SqlSource {

    private final SqlSource sqlSource;

    public RawSqlSource(Configuration configuration, SqlNode rootSqlNode, Class<?> parameterType) {
        this(configuration, getSql(configuration, rootSqlNode), parameterType);
    }

    public RawSqlSource(Configuration configuration, String sql, Class<?> parameterType) {
        // 获取 SqlSourceBuilder
        SqlSourceBuilder sqlSourceParser = new SqlSourceBuilder(configuration);
        Class<?> clazz = parameterType == null ? Object.class : parameterType;
        // 构建 SqlSource 对象
        sqlSource = sqlSourceParser.parse(sql, clazz, new HashMap<>());
    }

    /**
     * 获取 Sql
     *
     * @param configuration
     * @param rootSqlNode
     * @return
     */
    private static String getSql(Configuration configuration, SqlNode rootSqlNode) {
        // 创建 DynamicContext 对象
        DynamicContext context = new DynamicContext(configuration, null);
        // 解析出 SqlSource 对象
        rootSqlNode.apply(context);
        // 获取 SQL
        return context.getSql();
    }

    @Override
    public BoundSql getBoundSql(Object parameterObject) {
        return sqlSource.getBoundSql(parameterObject);
    }

}
