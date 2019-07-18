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
package org.apache.ibatis.logging.jdbc;

import org.apache.ibatis.logging.Log;
import org.apache.ibatis.reflection.ExceptionUtil;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashSet;
import java.util.Set;

/**
 * ResultSet proxy to add logging.
 * ResultSet 日志增强
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public final class ResultSetLogger extends BaseJdbcLogger implements InvocationHandler {

    /**
     * 记录超大长度类型
     */
    private static Set<Integer> BLOB_TYPES = new HashSet<>();
    /**
     * 是否是 ResultSet 结果集的第一行
     */
    private boolean first = true;
    /**
     * 统计行数
     */
    private int rows;
    /**
     * 代理的 ResultSet
     */
    private final ResultSet rs;
    /**
     * 记录超大字段的列编号
     */
    private final Set<Integer> blobColumns = new HashSet<>();

    static {
        BLOB_TYPES.add(Types.BINARY);
        BLOB_TYPES.add(Types.BLOB);
        BLOB_TYPES.add(Types.CLOB);
        BLOB_TYPES.add(Types.LONGNVARCHAR);
        BLOB_TYPES.add(Types.LONGVARBINARY);
        BLOB_TYPES.add(Types.LONGVARCHAR);
        BLOB_TYPES.add(Types.NCLOB);
        BLOB_TYPES.add(Types.VARBINARY);
    }

    private ResultSetLogger(ResultSet rs, Log statementLog, int queryStack) {
        super(statementLog, queryStack);
        this.rs = rs;
    }

    /**
     * 执行代理方法
     *
     * @param proxy
     * @param method
     * @param params
     * @return
     * @throws Throwable
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] params) throws Throwable {
        try {
            if (Object.class.equals(method.getDeclaringClass())) {
                // Object 的方法还是直接执行
                return method.invoke(this, params);
            }
            //
            Object o = method.invoke(rs, params);
            if ("next".equals(method.getName())) {
                // ResultSet::next 方法的处理
                if ((Boolean) o) {
                    // 方法以及执行过了，校验下，是否还有下一行数据
                    rows++;
                    // 是否启动追踪
                    if (isTraceEnabled()) {
                        // 获取结果期数据
                        ResultSetMetaData rsmd = rs.getMetaData();
                        // 过去结果集的列数
                        final int columnCount = rsmd.getColumnCount();
                        if (first) {
                            // 如果是第一行
                            first = false;
                            // 输出表头，填充 blobColumnCount 集合，记录超大类型的列数
                            printColumnHeaders(rsmd, columnCount);
                        }
                        // 输出该行记录，注意会过滤掉 blobColumns 中记录的列，这些列的数据较大，不会输出到日志
                        printColumnValues(columnCount);
                    }
                } else {
                    debug("     Total: " + rows, false);
                }
            }
            // 执行完要清理下 BaseJdbcLogger 的那三个缓存
            clearColumnInfo();
            return o;
        } catch (Throwable t) {
            throw ExceptionUtil.unwrapThrowable(t);
        }
    }

    private void printColumnHeaders(ResultSetMetaData rsmd, int columnCount) throws SQLException {
        StringBuilder row = new StringBuilder();
        row.append("   Columns: ");
        for (int i = 1; i <= columnCount; i++) {
            if (BLOB_TYPES.contains(rsmd.getColumnType(i))) {
                blobColumns.add(i);
            }
            String colname = rsmd.getColumnLabel(i);
            row.append(colname);
            if (i != columnCount) {
                row.append(", ");
            }
        }
        trace(row.toString(), false);
    }

    private void printColumnValues(int columnCount) {
        StringBuilder row = new StringBuilder();
        row.append("       Row: ");
        for (int i = 1; i <= columnCount; i++) {
            String colname;
            try {
                if (blobColumns.contains(i)) {
                    colname = "<<BLOB>>";
                } else {
                    colname = rs.getString(i);
                }
            } catch (SQLException e) {
                // generally can't call getString() on a BLOB column
                colname = "<<Cannot Display>>";
            }
            row.append(colname);
            if (i != columnCount) {
                row.append(", ");
            }
        }
        trace(row.toString(), false);
    }

    /**
     * Creates a logging version of a ResultSet.
     * 创建代理对象
     *
     * @param rs - the ResultSet to proxy
     * @return - the ResultSet with logging
     */
    public static ResultSet newInstance(ResultSet rs, Log statementLog, int queryStack) {
        InvocationHandler handler = new ResultSetLogger(rs, statementLog, queryStack);
        ClassLoader cl = ResultSet.class.getClassLoader();
        return (ResultSet) Proxy.newProxyInstance(cl, new Class[]{ResultSet.class}, handler);
    }

    /**
     * Get the wrapped result set.
     * 获取代理对象
     *
     * @return the resultSet
     */
    public ResultSet getRs() {
        return rs;
    }

}
