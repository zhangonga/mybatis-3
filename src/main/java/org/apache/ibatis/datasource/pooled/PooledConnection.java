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
package org.apache.ibatis.datasource.pooled;

import org.apache.ibatis.reflection.ExceptionUtil;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 实现 InvocationHandler 池化的 Connection 对象
 *
 * @author Clinton Begin
 */
class PooledConnection implements InvocationHandler {
    /**
     * 连接关闭方法名，拦截执行
     */
    private static final String CLOSE = "close";
    /**
     * 连接的接口
     */
    private static final Class<?>[] IFACES = new Class<?>[]{Connection.class};
    /**
     * 当前对象的 hashCode
     */
    private final int hashCode;
    /**
     * 所属的连接池
     */
    private final PooledDataSource dataSource;
    /**
     * 真实的数据库连接
     */
    private final Connection realConnection;
    /**
     * 代理的 Connection 连接，即 {@link PooledConnection} 这个动态代理的 Connection 对象
     */
    private final Connection proxyConnection;
    /**
     * 获取到连接的时间
     */
    private long checkoutTimestamp;
    /**
     * 创建连接的时间
     */
    private long createdTimestamp;
    /**
     * 最后使用的时间
     */
    private long lastUsedTimestamp;
    /**
     * 连接的标识，即 {@link PooledDataSource#expectedConnectionTypeCode}
     */
    private int connectionTypeCode;
    /**
     * 是否可用
     */
    private boolean valid;

    /**
     * 构造方法
     * 设置hashcode，连接，数据库，创建时间，最后使用时间，设置连接可用，生成代理连接
     * Constructor for SimplePooledConnection that uses the Connection and PooledDataSource passed in.
     *
     * @param connection - the connection that is to be presented as a pooled connection
     * @param dataSource - the dataSource that the connection is from
     */
    public PooledConnection(Connection connection, PooledDataSource dataSource) {
        this.hashCode = connection.hashCode();
        this.realConnection = connection;
        this.dataSource = dataSource;
        this.createdTimestamp = System.currentTimeMillis();
        this.lastUsedTimestamp = System.currentTimeMillis();
        this.valid = true;
        // 基于 JDK Proxy 创建 Connection 对象，并且 handler 对象就是 this ，也就是自己。那意味着什么？
        // 意味着后续对 proxyConnection 的所有方法调用，都会委托给 PooledConnection#invoke(Object proxy, Method method, Object[] args)
        this.proxyConnection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), IFACES, this);
    }

    /**
     * 根据前面 this.proxyConnection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), IFACES, this);
     * 所以通过代理连接访问连接，都会调用到本方法，例如 proxyConnection.close();
     * Required for InvocationHandler implementation.
     *
     * @param proxy  - not used
     * @param method - the method to be executed
     * @param args   - the parameters to be passed to the method
     * @see java.lang.reflect.InvocationHandler#invoke(Object, java.lang.reflect.Method, Object[])
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String methodName = method.getName();
        // 代理关闭方法
        if (CLOSE.hashCode() == methodName.hashCode() && CLOSE.equals(methodName)) {
            dataSource.pushConnection(this);
            return null;
        }
        try {
            if (!Object.class.equals(method.getDeclaringClass())) {
                // issue #579 toString() should never fail
                // throw an SQLException instead of a Runtime
                // 如果方法声明不是在 Object.class 中。
                // method.getDeclaringClass() 获取方法声明所在的类，例如 toString 方法就是在 Object 中的，所以 如果method 是 toString ， 那么 getDeclaringClass 就是 Object.class 。
                // 这个方法的意思就是如果是 Object 类中的方法直接执行就可以了，如果是非 Object 的方法就要校验下链接，SQLException instead of a Runtime
                checkConnection();
            }
            return method.invoke(realConnection, args);
        } catch (Throwable t) {
            throw ExceptionUtil.unwrapThrowable(t);
        }
    }

    /**
     * 设置连接失效
     * Invalidates the connection.
     */
    public void invalidate() {
        valid = false;
    }

    /**
     * 检查连接是否有效
     * Method to see if the connection is usable.
     *
     * @return True if the connection is usable
     */
    public boolean isValid() {
        return valid && realConnection != null && dataSource.pingConnection(this);
    }

    /**
     * Getter for the *real* connection that this wraps.
     * 获取真实的连接
     *
     * @return The connection
     */
    public Connection getRealConnection() {
        return realConnection;
    }

    /**
     * Getter for the proxy for the connection.
     * 获取代理连接
     *
     * @return The proxy
     */
    public Connection getProxyConnection() {
        return proxyConnection;
    }

    /**
     * Gets the hashcode of the real connection (or 0 if it is null).
     * 获取真实连接的hashCode
     *
     * @return The hashcode of the real connection (or 0 if it is null)
     */
    public int getRealHashCode() {
        return realConnection == null ? 0 : realConnection.hashCode();
    }

    /**
     * Getter for the connection type (based on url + user + password).
     * 获取连接类型，(based on url + user + password).
     * 每个数据源有统一的连接类型
     *
     * @return The connection type
     */
    public int getConnectionTypeCode() {
        return connectionTypeCode;
    }

    /**
     * Setter for the connection type.
     *
     * @param connectionTypeCode - the connection type
     */
    public void setConnectionTypeCode(int connectionTypeCode) {
        this.connectionTypeCode = connectionTypeCode;
    }

    /**
     * Getter for the time that the connection was created.
     *
     * @return The creation timestamp
     */
    public long getCreatedTimestamp() {
        return createdTimestamp;
    }

    /**
     * Setter for the time that the connection was created.
     *
     * @param createdTimestamp - the timestamp
     */
    public void setCreatedTimestamp(long createdTimestamp) {
        this.createdTimestamp = createdTimestamp;
    }

    /**
     * Getter for the time that the connection was last used.
     *
     * @return - the timestamp
     */
    public long getLastUsedTimestamp() {
        return lastUsedTimestamp;
    }

    /**
     * Setter for the time that the connection was last used.
     *
     * @param lastUsedTimestamp - the timestamp
     */
    public void setLastUsedTimestamp(long lastUsedTimestamp) {
        this.lastUsedTimestamp = lastUsedTimestamp;
    }

    /**
     * Getter for the time since this connection was last used.
     * 获取连接使用了多久了
     *
     * @return - the time since the last use
     */
    public long getTimeElapsedSinceLastUse() {
        return System.currentTimeMillis() - lastUsedTimestamp;
    }

    /**
     * Getter for the age of the connection.
     * 获取连接存在了多久了
     *
     * @return the age
     */
    public long getAge() {
        return System.currentTimeMillis() - createdTimestamp;
    }

    /**
     * Getter for the timestamp that this connection was checked out.
     * 获取检出连接的时间
     *
     * @return the timestamp
     */
    public long getCheckoutTimestamp() {
        return checkoutTimestamp;
    }

    /**
     * Setter for the timestamp that this connection was checked out.
     *
     * @param timestamp the timestamp
     */
    public void setCheckoutTimestamp(long timestamp) {
        this.checkoutTimestamp = timestamp;
    }

    /**
     * Getter for the time that this connection has been checked out.
     * 获取连接检出了多久了
     *
     * @return the time
     */
    public long getCheckoutTime() {
        return System.currentTimeMillis() - checkoutTimestamp;
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    /**
     * Allows comparing this connection to another.
     * 重写等于方法
     * 如果是PooledConnection 则校验包含的 realConnection 的 hashcode
     * 如果是真实连接，直接比较 hashcode
     *
     * @param obj - the other connection to test for equality
     * @see Object#equals(Object)
     */
    @Override
    public boolean equals(Object obj) {
        if (obj instanceof PooledConnection) {
            return realConnection.hashCode() == ((PooledConnection) obj).realConnection.hashCode();
        } else if (obj instanceof Connection) {
            return hashCode == obj.hashCode();
        } else {
            return false;
        }
    }

    /**
     * 检查连接是否可用
     *
     * @throws SQLException
     */
    private void checkConnection() throws SQLException {
        if (!valid) {
            throw new SQLException("Error accessing PooledConnection. Connection is invalid.");
        }
    }

}
