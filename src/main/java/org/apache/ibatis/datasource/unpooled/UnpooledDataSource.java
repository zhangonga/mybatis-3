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
package org.apache.ibatis.datasource.unpooled;

import org.apache.ibatis.io.Resources;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.*;
import java.util.Enumeration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 非池化数据源
 * <p/>
 * unpooled 数据源实现知识每次请求时打开和关闭连接
 * 虽然有些慢，但对于在数据库连接可用性方面没有太高要求的简单的应用来说，是一个很好的选择。
 * 不同的数据库在性能方面表现是不一样的，对于某些数据库来说，使用连接池并不重要，这种情形就很适合。
 * unpooled 数据源仅仅需要配置5个属性即可
 * 1，driver
 * 2，url
 * 3，username
 * 4，password
 * 5，defaultTransactionIsolationLevel 默认的事务隔离级别
 * 作为可选项，也可以传递属性给数据库的驱动，例如 driver.encoding=UTF8
 * 将通过DriverManager.getConnection(url, driverProperties) 方法将值传递给数据库驱动
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class UnpooledDataSource implements DataSource {

    /**
     * 数据库驱动类加载器
     */
    private ClassLoader driverClassLoader;
    /**
     * 数据库驱动属性
     */
    private Properties driverProperties;
    /**
     * 已注册数据库驱动 key 驱动类名， value 驱动对象
     */
    private static Map<String, Driver> registeredDrivers = new ConcurrentHashMap<>();

    /**
     * 数据库驱动类全路径
     */
    private String driver;
    /**
     * 数据库地址
     */
    private String url;
    /**
     * 数据库登录用户
     */
    private String username;
    /**
     * 数据库登录密码
     */
    private String password;

    /**
     * 是否自动提交事务
     */
    private Boolean autoCommit;
    /**
     * 默认的数据库事务隔离级别
     */
    private Integer defaultTransactionIsolationLevel;

    static {
        // 初始化 registeredDrivers
        // 遍历所有注册到驱动管理器的驱动
        // Class.forName，会把类加载到 jvm 中
        // com.mysql.jdbc.Driver 有静态代码，就是往 DriverManager 中注册驱动，即把驱动放入 DriverManager 中的 registeredDrivers 中
        // 所以这里就能拿到注册过的驱动
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            Driver driver = drivers.nextElement();
            registeredDrivers.put(driver.getClass().getName(), driver);
        }
    }

    /**
     * 以下几个都是各种构造方法
     */
    public UnpooledDataSource() {
    }

    public UnpooledDataSource(String driver, String url, String username, String password) {
        this.driver = driver;
        this.url = url;
        this.username = username;
        this.password = password;
    }

    public UnpooledDataSource(String driver, String url, Properties driverProperties) {
        this.driver = driver;
        this.url = url;
        this.driverProperties = driverProperties;
    }

    public UnpooledDataSource(ClassLoader driverClassLoader, String driver, String url, String username, String password) {
        this.driverClassLoader = driverClassLoader;
        this.driver = driver;
        this.url = url;
        this.username = username;
        this.password = password;
    }

    public UnpooledDataSource(ClassLoader driverClassLoader, String driver, String url, Properties driverProperties) {
        this.driverClassLoader = driverClassLoader;
        this.driver = driver;
        this.url = url;
        this.driverProperties = driverProperties;
    }

    /**
     * 获取 connection 连接，最终调用 doGetConnection
     *
     * @return
     * @throws SQLException
     */
    @Override
    public Connection getConnection() throws SQLException {
        return doGetConnection(username, password);
    }

    /**
     * 获取 connection 连接， 最终调用 doGetConnection
     *
     * @param username
     * @param password
     * @return
     * @throws SQLException
     */
    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return doGetConnection(username, password);
    }

    /**
     * 获取 Connection
     *
     * @param username
     * @param password
     * @return
     * @throws SQLException
     */
    private Connection doGetConnection(String username, String password) throws SQLException {
        Properties props = new Properties();
        if (driverProperties != null) {
            props.putAll(driverProperties);
        }
        if (username != null) {
            props.setProperty("user", username);
        }
        if (password != null) {
            props.setProperty("password", password);
        }
        return doGetConnection(props);
    }

    /**
     * 获取 Connection
     *
     * @param properties
     * @return
     * @throws SQLException
     */
    private Connection doGetConnection(Properties properties) throws SQLException {
        // 初始化驱动
        initializeDriver();
        Connection connection = DriverManager.getConnection(url, properties);
        configureConnection(connection);
        return connection;
    }

    /**
     * 初始化驱动
     * 如果 registeredDrivers 中没有配置的驱动，则去初始化，有的话，说明初始化过了。
     *
     * @throws SQLException
     */
    private synchronized void initializeDriver() throws SQLException {
        if (!registeredDrivers.containsKey(driver)) {
            Class<?> driverType;
            try {
                // 加载驱动
                if (driverClassLoader != null) {
                    driverType = Class.forName(driver, true, driverClassLoader);
                } else {
                    driverType = Resources.classForName(driver);
                }
                // DriverManager requires the driver to be loaded via the system ClassLoader.
                // http://www.kfu.com/~nsayer/Java/dyn-jdbc.html
                // 创建驱动实例，并创建驱动实例的代理，并注册到驱动管理器中
                Driver driverInstance = (Driver) driverType.newInstance();
                DriverManager.registerDriver(new DriverProxy(driverInstance));
                // 这里执行了替换
                registeredDrivers.put(driver, driverInstance);
            } catch (Exception e) {
                throw new SQLException("Error setting driver on UnpooledDataSource. Cause: " + e);
            }
        }
    }

    /**
     * 配置获取到的数据库连接
     *
     * @param conn
     * @throws SQLException
     */
    private void configureConnection(Connection conn) throws SQLException {
        if (autoCommit != null && autoCommit != conn.getAutoCommit()) {
            conn.setAutoCommit(autoCommit);
        }
        if (defaultTransactionIsolationLevel != null) {
            conn.setTransactionIsolation(defaultTransactionIsolationLevel);
        }
    }

    /**
     * 数据库驱动代理内部类，应该叫增强更好一点
     * 为啥往 DriverManager 注册，不直接注册 Driver 而是注册 DriverProxy 呢？
     * 估计是为了 getParentLogger ， 因为其他的方法，都是调用 driver 的方法啊。
     */
    private static class DriverProxy implements Driver {
        private Driver driver;

        DriverProxy(Driver d) {
            this.driver = d;
        }

        @Override
        public boolean acceptsURL(String u) throws SQLException {
            return this.driver.acceptsURL(u);
        }

        @Override
        public Connection connect(String u, Properties p) throws SQLException {
            return this.driver.connect(u, p);
        }

        @Override
        public int getMajorVersion() {
            return this.driver.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return this.driver.getMinorVersion();
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String u, Properties p) throws SQLException {
            return this.driver.getPropertyInfo(u, p);
        }

        @Override
        public boolean jdbcCompliant() {
            return this.driver.jdbcCompliant();
        }

        // @Override only valid jdk7+
        public Logger getParentLogger() {
            return Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        }
    }


    // -------------------------- CommonDataSource 和 Wrapper 两个接口的方法实现---------------------------------
    @Override
    public void setLoginTimeout(int loginTimeout) {
        DriverManager.setLoginTimeout(loginTimeout);
    }

    @Override
    public int getLoginTimeout() {
        return DriverManager.getLoginTimeout();
    }

    @Override
    public void setLogWriter(PrintWriter logWriter) {
        DriverManager.setLogWriter(logWriter);
    }

    @Override
    public PrintWriter getLogWriter() {
        return DriverManager.getLogWriter();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        throw new SQLException(getClass().getName() + " is not a wrapper.");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return false;
    }

    // @Override only valid jdk7+
    public Logger getParentLogger() {
        // requires JDK version 1.6
        return Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
    }


    //-------------------------------------------getter / setter 方法----------------------------------------------------
    public ClassLoader getDriverClassLoader() {
        return driverClassLoader;
    }

    public void setDriverClassLoader(ClassLoader driverClassLoader) {
        this.driverClassLoader = driverClassLoader;
    }

    public Properties getDriverProperties() {
        return driverProperties;
    }

    public void setDriverProperties(Properties driverProperties) {
        this.driverProperties = driverProperties;
    }

    public String getDriver() {
        return driver;
    }

    public synchronized void setDriver(String driver) {
        this.driver = driver;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Boolean isAutoCommit() {
        return autoCommit;
    }

    public void setAutoCommit(Boolean autoCommit) {
        this.autoCommit = autoCommit;
    }

    public Integer getDefaultTransactionIsolationLevel() {
        return defaultTransactionIsolationLevel;
    }

    public void setDefaultTransactionIsolationLevel(Integer defaultTransactionIsolationLevel) {
        this.defaultTransactionIsolationLevel = defaultTransactionIsolationLevel;
    }
}
