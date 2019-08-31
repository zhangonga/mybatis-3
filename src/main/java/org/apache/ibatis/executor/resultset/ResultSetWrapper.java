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
package org.apache.ibatis.executor.resultset;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.*;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.*;

/**
 * JDBC ResultSet 增强
 *
 * @author Iwao AVE!
 */
public class ResultSetWrapper {

    /**
     * java sql ResultSet
     */
    private final ResultSet resultSet;
    private final TypeHandlerRegistry typeHandlerRegistry;
    /**
     * 字段的名字的数组
     */
    private final List<String> columnNames = new ArrayList<>();
    /**
     * 字段的 Java Type 的数组
     */
    private final List<String> classNames = new ArrayList<>();
    /**
     * 字段的 JdbcType 的数组
     */
    private final List<JdbcType> jdbcTypes = new ArrayList<>();
    /**
     * key 字段名称
     * value java 属性类型
     */
    private final Map<String, Map<Class<?>, TypeHandler<?>>> typeHandlerMap = new HashMap<>();
    /**
     * 有 mapped 的字段的名字的映射
     * key ： 通过 getMapKey 获得 key 一般为 resultMap 的 id
     */
    private final Map<String, List<String>> mappedColumnNamesMap = new HashMap<>();
    /**
     * 无 mapped 的字段的名字的映射
     */
    private final Map<String, List<String>> unMappedColumnNamesMap = new HashMap<>();

    /**
     * 构造方法
     *
     * @param rs
     * @param configuration
     * @throws SQLException
     */
    public ResultSetWrapper(ResultSet rs, Configuration configuration) throws SQLException {
        super();
        this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
        this.resultSet = rs;
        final ResultSetMetaData metaData = rs.getMetaData();
        final int columnCount = metaData.getColumnCount();
        //  遍历 ResultSetMetaData 的字段们，解析出 columnNames、jdbcTypes、classNames 属性
        for (int i = 1; i <= columnCount; i++) {
            columnNames.add(configuration.isUseColumnLabel() ? metaData.getColumnLabel(i) : metaData.getColumnName(i));
            jdbcTypes.add(JdbcType.forCode(metaData.getColumnType(i)));
            classNames.add(metaData.getColumnClassName(i));
        }
    }

    public ResultSet getResultSet() {
        return resultSet;
    }

    public List<String> getColumnNames() {
        return this.columnNames;
    }

    public List<String> getClassNames() {
        return Collections.unmodifiableList(classNames);
    }

    public List<JdbcType> getJdbcTypes() {
        return jdbcTypes;
    }

    /**
     * 通过字段名，获取jdbc 类型
     *
     * @param columnName
     * @return
     */
    public JdbcType getJdbcType(String columnName) {
        for (int i = 0; i < columnNames.size(); i++) {
            if (columnNames.get(i).equalsIgnoreCase(columnName)) {
                return jdbcTypes.get(i);
            }
        }
        return null;
    }

    /**
     * Gets the type handler to use when reading the result set.
     * Tries to get from the TypeHandlerRegistry by searching for the property type.
     * If not found it gets the column JDBC type and tries to get a handler for it.
     * <p>
     * 获得指定字段名的指定 JavaType 类型的 TypeHandler 对象
     *
     * @param propertyType
     * @param columnName
     * @return
     */
    public TypeHandler<?> getTypeHandler(Class<?> propertyType, String columnName) {
        TypeHandler<?> handler = null;
        // 先从缓存中获取
        Map<Class<?>, TypeHandler<?>> columnHandlers = typeHandlerMap.get(columnName);
        if (columnHandlers == null) {
            // 没有就新建一个
            columnHandlers = new HashMap<>();
            typeHandlerMap.put(columnName, columnHandlers);
        } else {
            // 如果不为空，则直接获取到
            handler = columnHandlers.get(propertyType);
        }

        if (handler == null) {
            // 如果 handler 为空

            // 获得字段的java 类型
            JdbcType jdbcType = getJdbcType(columnName);
            // 从 typeHandlerRegistry 中获得 typeHandler
            handler = typeHandlerRegistry.getTypeHandler(propertyType, jdbcType);
            // Replicate logic of UnknownTypeHandler#resolveTypeHandler
            // See issue #59 comment 10
            if (handler == null || handler instanceof UnknownTypeHandler) {
                // 如果处理器为空，或者是未知类型处理器，则根据字段类型，获得系统默认对象的类型处理器
                final int index = columnNames.indexOf(columnName);
                final Class<?> javaType = resolveClass(classNames.get(index));
                if (javaType != null && jdbcType != null) {
                    handler = typeHandlerRegistry.getTypeHandler(javaType, jdbcType);
                } else if (javaType != null) {
                    handler = typeHandlerRegistry.getTypeHandler(javaType);
                } else if (jdbcType != null) {
                    handler = typeHandlerRegistry.getTypeHandler(jdbcType);
                }
            }
            // 如果经过上边处理，还是为空或者是未知的属性处理器，则设置成 ObjectTypeHandler
            if (handler == null || handler instanceof UnknownTypeHandler) {
                handler = new ObjectTypeHandler();
            }
            columnHandlers.put(propertyType, handler);
        }

        return handler;
    }

    /**
     * 通过类名解析类型
     *
     * @param className
     * @return
     */
    private Class<?> resolveClass(String className) {
        try {
            // #699 className could be null
            if (className != null) {
                return Resources.classForName(className);
            }
        } catch (ClassNotFoundException e) {
            // ignore
        }
        return null;
    }

    /**
     * 初始化有 mapped 和无 mapped 的字段的名字数组
     *
     * @param resultMap
     * @param columnPrefix
     * @throws SQLException
     */
    private void loadMappedAndUnmappedColumnNames(ResultMap resultMap, String columnPrefix) throws SQLException {
        List<String> mappedColumnNames = new ArrayList<>();
        List<String> unmappedColumnNames = new ArrayList<>();

        // 如果不为空，则变成大写
        final String upperColumnPrefix = columnPrefix == null ? null : columnPrefix.toUpperCase(Locale.ENGLISH);
        // mappedColumns = 前缀加 resultMapped 的 列名
        final Set<String> mappedColumns = prependPrefixes(resultMap.getMappedColumns(), upperColumnPrefix);
        // 遍历 ResultSet 中获取的字段列表columnNames，如果字段列表的字段在 resultMap 中有，则加入到mappedColumnNamesMap 否则加入 unMappedColumnNamesMap
        for (String columnName : columnNames) {
            final String upperColumnName = columnName.toUpperCase(Locale.ENGLISH);
            if (mappedColumns.contains(upperColumnName)) {
                mappedColumnNames.add(upperColumnName);
            } else {
                unmappedColumnNames.add(columnName);
            }
        }
        mappedColumnNamesMap.put(getMapKey(resultMap, columnPrefix), mappedColumnNames);
        unMappedColumnNamesMap.put(getMapKey(resultMap, columnPrefix), unmappedColumnNames);
    }

    /**
     * 获取 ResultMap 中映射的字段名
     *
     * @param resultMap
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    public List<String> getMappedColumnNames(ResultMap resultMap, String columnPrefix) throws SQLException {
        List<String> mappedColumnNames = mappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
        if (mappedColumnNames == null) {
            // 如果为空，则加载
            loadMappedAndUnmappedColumnNames(resultMap, columnPrefix);
            mappedColumnNames = mappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
        }
        return mappedColumnNames;
    }

    /**
     * 获取 ResultMap 中没有映射的字段
     *
     * @param resultMap
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    public List<String> getUnmappedColumnNames(ResultMap resultMap, String columnPrefix) throws SQLException {
        List<String> unMappedColumnNames = unMappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
        if (unMappedColumnNames == null) {
            // 如果unMappedColumnNames为空，则再加载一次
            loadMappedAndUnmappedColumnNames(resultMap, columnPrefix);
            unMappedColumnNames = unMappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
        }
        return unMappedColumnNames;
    }

    private String getMapKey(ResultMap resultMap, String columnPrefix) {
        return resultMap.getId() + ":" + columnPrefix;
    }

    /**
     * 预处理前缀
     *
     * @param columnNames
     * @param prefix
     * @return
     */
    private Set<String> prependPrefixes(Set<String> columnNames, String prefix) {
        // 如果校验不通过，直接返回即可
        if (columnNames == null || columnNames.isEmpty() || prefix == null || prefix.length() == 0) {
            return columnNames;
        }
        // 遍历处理，前缀加字段名
        final Set<String> prefixed = new HashSet<>();
        for (String columnName : columnNames) {
            prefixed.add(prefix + columnName);
        }
        return prefixed;
    }

}
