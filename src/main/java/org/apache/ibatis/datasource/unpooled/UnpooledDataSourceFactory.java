/**
 * Copyright 2009-2015 the original author or authors.
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

import org.apache.ibatis.datasource.DataSourceException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;

import javax.sql.DataSource;
import java.util.Properties;

/**
 * 非池化数据源工厂
 *
 * @author Clinton Begin
 */
public class UnpooledDataSourceFactory implements DataSourceFactory {

    private static final String DRIVER_PROPERTY_PREFIX = "driver.";
    private static final int DRIVER_PROPERTY_PREFIX_LENGTH = DRIVER_PROPERTY_PREFIX.length();

    protected DataSource dataSource;

    /**
     * 构造方法
     */
    public UnpooledDataSourceFactory() {
        // 创建一个数据源
        this.dataSource = new UnpooledDataSource();
    }

    /**
     * 返回数据源
     *
     * @return
     */
    @Override
    public DataSource getDataSource() {
        return dataSource;
    }

    /**
     * 主要是解析属性
     * 并把数据源的属性和数据库驱动的属性分开
     * 并把数据库的驱动值放到数据源的 driverProperties 中
     *
     * @param properties
     */
    @Override
    public void setProperties(Properties properties) {
        Properties driverProperties = new Properties();
        // 创建 dataSource 的对象元数据
        MetaObject metaDataSource = SystemMetaObject.forObject(dataSource);
        // 遍历属性， 并初始化到 driverProperties 和 metaDataSource 中
        for (Object key : properties.keySet()) {
            String propertyName = (String) key;
            if (propertyName.startsWith(DRIVER_PROPERTY_PREFIX)) {
                // driver. 开头，数据库驱动相关的配置
                String value = properties.getProperty(propertyName);
                driverProperties.setProperty(propertyName.substring(DRIVER_PROPERTY_PREFIX_LENGTH), value);
            } else if (metaDataSource.hasSetter(propertyName)) {
                // hasSetter 判断，最终还是通过反射获取到的
                String value = (String) properties.get(propertyName);
                Object convertedValue = convertValue(metaDataSource, propertyName, value);
                metaDataSource.setValue(propertyName, convertedValue);
            } else {
                // 未知的属性，抛出异常
                throw new DataSourceException("Unknown DataSource property: " + propertyName);
            }
        }
        if (driverProperties.size() > 0) {
            metaDataSource.setValue("driverProperties", driverProperties);
        }
    }

    /**
     * 值转换
     * 其实就是通过判断属性的setter 方法的类型，来转成相对于的值而已
     *
     * @param metaDataSource
     * @param propertyName
     * @param value
     * @return
     */
    private Object convertValue(MetaObject metaDataSource, String propertyName, String value) {
        Object convertedValue = value;
        Class<?> targetType = metaDataSource.getSetterType(propertyName);
        if (targetType == Integer.class || targetType == int.class) {
            convertedValue = Integer.valueOf(value);
        } else if (targetType == Long.class || targetType == long.class) {
            convertedValue = Long.valueOf(value);
        } else if (targetType == Boolean.class || targetType == boolean.class) {
            convertedValue = Boolean.valueOf(value);
        }
        return convertedValue;
    }
}
