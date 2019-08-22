/**
 * Copyright 2009-2017 the original author or authors.
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
package org.apache.ibatis.scripting.xmltags;

import org.apache.ibatis.builder.SqlSourceBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.session.Configuration;

import java.util.Map;

/**
 * 实现 SqlSource 接口，动态的 SqlSource 实现类
 * 适用于使用了 ${} 表达式的 SQL
 *
 * @author Clinton Begin
 */
public class DynamicSqlSource implements SqlSource {

    private final Configuration configuration;
    /**
     * 根 SqlNode 对象
     */
    private final SqlNode rootSqlNode;

    public DynamicSqlSource(Configuration configuration, SqlNode rootSqlNode) {
        this.configuration = configuration;
        this.rootSqlNode = rootSqlNode;
    }

    /**
     * 获取 BoundSql
     *
     * @param parameterObject 参数值
     * @return
     */
    @Override
    public BoundSql getBoundSql(Object parameterObject) {
        // 应用 rootSqlNode
        DynamicContext context = new DynamicContext(configuration, parameterObject);
        rootSqlNode.apply(context);
        // 创建 SqlSourceBuilder 对象
        SqlSourceBuilder sqlSourceParser = new SqlSourceBuilder(configuration);
        // 获取参数类型
        Class<?> parameterType = parameterObject == null ? Object.class : parameterObject.getClass();
        // 构建 SqlSource，这里解析出来的，就是 staticSqlSource 了
        SqlSource sqlSource = sqlSourceParser.parse(context.getSql(), parameterType, context.getBindings());
        // 获取 BoundSql
        BoundSql boundSql = sqlSource.getBoundSql(parameterObject);
        for (Map.Entry<String, Object> entry : context.getBindings().entrySet()) {
            // 附加参数到 boundSql 中
            boundSql.setAdditionalParameter(entry.getKey(), entry.getValue());
        }
        return boundSql;
    }

}
