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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * 继承 BaseJdbcLogger ，并封装了 Connection 对象，并同时实现了 InvocationHandler 接口
 * 代理 Connection 对象，并进行日志增强
 * Connection proxy to add logging.
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public final class ConnectionLogger extends BaseJdbcLogger implements InvocationHandler {

    private final Connection connection;

    /**
     * 构造方法
     *
     * @param conn         连接
     * @param statementLog 日志
     * @param queryStack   查询堆
     */
    private ConnectionLogger(Connection conn, Log statementLog, int queryStack) {
        super(statementLog, queryStack);
        this.connection = conn;
    }

    /**
     * ConnectionLogger 的 invoke 方法是代理对象的核心方法
     * 它打印日志的同时，为 preparedStatement 、 prepareCall 、 createStatement 等方法提供了创建代理方法
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
                // 如果是 Object 的方法，则直接执行
                return method.invoke(this, params);
            }
            if ("prepareStatement".equals(method.getName())) {
                // 如果是预编译方法
                if (isDebugEnabled()) {
                    debug(" Preparing: " + removeBreakingWhitespace((String) params[0]), true);
                }
                // 执行预编译
                PreparedStatement stmt = (PreparedStatement) method.invoke(connection, params);
                // 生成一个日志版本的预编译类，并返回
                stmt = PreparedStatementLogger.newInstance(stmt, statementLog, queryStack);
                return stmt;
            } else if ("prepareCall".equals(method.getName())) {
                if (isDebugEnabled()) {
                    debug(" Preparing: " + removeBreakingWhitespace((String) params[0]), true);
                }
                PreparedStatement stmt = (PreparedStatement) method.invoke(connection, params);
                stmt = PreparedStatementLogger.newInstance(stmt, statementLog, queryStack);
                return stmt;
            } else if ("createStatement".equals(method.getName())) {
                Statement stmt = (Statement) method.invoke(connection, params);
                stmt = StatementLogger.newInstance(stmt, statementLog, queryStack);
                return stmt;
            } else {
                // 不是以上几个方法，则直接执行
                return method.invoke(connection, params);
            }
        } catch (Throwable t) {
            throw ExceptionUtil.unwrapThrowable(t);
        }
    }

    /**
     * Creates a logging version of a connection.
     * 创建增强日志的 Connection 动态代理对象
     *
     * @param conn - the original connection
     * @return - the connection with logging
     */
    public static Connection newInstance(Connection conn, Log statementLog, int queryStack) {
        InvocationHandler handler = new ConnectionLogger(conn, statementLog, queryStack);
        ClassLoader cl = Connection.class.getClassLoader();
        return (Connection) Proxy.newProxyInstance(cl, new Class[]{Connection.class}, handler);
    }

    /**
     * 返回增强的 connection
     * return the wrapped connection.
     *
     * @return the connection
     */
    public Connection getConnection() {
        return connection;
    }

}
