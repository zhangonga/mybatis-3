/**
 * Copyright 2009-2018 the original author or authors.
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
package org.apache.ibatis.reflection;

import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.property.PropertyTokenizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;

/**
 * 类的元数据
 * 基于Reflector 和 PropertyTokenizer 提供对指定类的各种骚操作
 *
 * @author Clinton Begin
 */
public class MetaClass {

    private final ReflectorFactory reflectorFactory;
    private final Reflector reflector;

    /**
     * 构造方法
     *
     * @param type             一个类对应一个MetaClass
     * @param reflectorFactory 反射工厂
     */
    private MetaClass(Class<?> type, ReflectorFactory reflectorFactory) {
        this.reflectorFactory = reflectorFactory;
        this.reflector = reflectorFactory.findForClass(type);
    }

    /**
     * 静态方法，供外部调用创建MetaClass 类
     *
     * @param type
     * @param reflectorFactory
     * @return
     */
    public static MetaClass forClass(Class<?> type, ReflectorFactory reflectorFactory) {
        return new MetaClass(type, reflectorFactory);
    }

    /**
     * 创建类的指定属性的类的 MetaClass 对象
     *
     * @param name
     * @return
     */
    public MetaClass metaClassForProperty(String name) {
        Class<?> propType = reflector.getGetterType(name);
        return MetaClass.forClass(propType, reflectorFactory);
    }

    /**
     * 获取属性
     *
     * @param name
     * @param useCamelCaseMapping 是否下划线转驼峰
     * @return
     */
    public String findProperty(String name, boolean useCamelCaseMapping) {
        if (useCamelCaseMapping) {
            // 无需担心大小写问题Reflector 中属性Key 全大写的
            name = name.replace("_", "");
        }
        return findProperty(name);
    }

    /**
     * 获取属性
     *
     * @param name
     * @return
     */
    public String findProperty(String name) {
        StringBuilder prop = buildProperty(name, new StringBuilder());
        return prop.length() > 0 ? prop.toString() : null;
    }

    /**
     * 获取所有可读属性
     *
     * @return
     */
    public String[] getGetterNames() {
        return reflector.getGetablePropertyNames();
    }

    /**
     * 获取所有可写属性
     *
     * @return
     */
    public String[] getSetterNames() {
        return reflector.getSetablePropertyNames();
    }

    /**
     * 根据属性名，获取属性的类型
     *
     * @param name
     * @return
     */
    public Class<?> getSetterType(String name) {
        PropertyTokenizer prop = new PropertyTokenizer(name);
        if (prop.hasNext()) {
            MetaClass metaProp = metaClassForProperty(prop.getName());
            return metaProp.getSetterType(prop.getChildren());
        } else {
            return reflector.getSetterType(prop.getName());
        }
    }

    public Class<?> getGetterType(String name) {
        PropertyTokenizer prop = new PropertyTokenizer(name);
        if (prop.hasNext()) {
            MetaClass metaProp = metaClassForProperty(prop);
            return metaProp.getGetterType(prop.getChildren());
        }
        // issue #506. Resolve the type inside a Collection Object
        return getGetterType(prop);
    }

    private MetaClass metaClassForProperty(PropertyTokenizer prop) {
        Class<?> propType = getGetterType(prop);
        return MetaClass.forClass(propType, reflectorFactory);
    }

    private Class<?> getGetterType(PropertyTokenizer prop) {
        Class<?> type = reflector.getGetterType(prop.getName());
        if (prop.getIndex() != null && Collection.class.isAssignableFrom(type)) {
            Type returnType = getGenericGetterType(prop.getName());
            if (returnType instanceof ParameterizedType) {
                Type[] actualTypeArguments = ((ParameterizedType) returnType).getActualTypeArguments();
                if (actualTypeArguments != null && actualTypeArguments.length == 1) {
                    returnType = actualTypeArguments[0];
                    if (returnType instanceof Class) {
                        type = (Class<?>) returnType;
                    } else if (returnType instanceof ParameterizedType) {
                        type = (Class<?>) ((ParameterizedType) returnType).getRawType();
                    }
                }
            }
        }
        return type;
    }

    private Type getGenericGetterType(String propertyName) {
        try {
            // 获取执行器对象
            Invoker invoker = reflector.getGetInvoker(propertyName);
            if (invoker instanceof MethodInvoker) {
                // 如果是方法执行器，说明是getting方法，解析方法返回值
                Field _method = MethodInvoker.class.getDeclaredField("method");
                _method.setAccessible(true);
                Method method = (Method) _method.get(invoker);
                return TypeParameterResolver.resolveReturnType(method, reflector.getType());
            } else if (invoker instanceof GetFieldInvoker) {
                // 如果是 GetFieldInvoker 执行器，说明是field ，直接访问
                Field _field = GetFieldInvoker.class.getDeclaredField("field");
                _field.setAccessible(true);
                Field field = (Field) _field.get(invoker);
                return TypeParameterResolver.resolveFieldType(field, reflector.getType());
            }
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
        }
        return null;
    }

    /**
     * 判断方法是否有 setter 方法
     *
     * @param name
     * @return
     */
    public boolean hasSetter(String name) {
        PropertyTokenizer prop = new PropertyTokenizer(name);
        if (prop.hasNext()) {
            if (reflector.hasSetter(prop.getName())) {
                MetaClass metaProp = metaClassForProperty(prop.getName());
                return metaProp.hasSetter(prop.getChildren());
            } else {
                return false;
            }
        } else {
            return reflector.hasSetter(prop.getName());
        }
    }

    /**
     * 判断方法是否有 getter 方法
     *
     * @param name
     * @return
     */
    public boolean hasGetter(String name) {
        PropertyTokenizer prop = new PropertyTokenizer(name);
        if (prop.hasNext()) {
            if (reflector.hasGetter(prop.getName())) {
                MetaClass metaProp = metaClassForProperty(prop);
                return metaProp.hasGetter(prop.getChildren());
            } else {
                return false;
            }
        } else {
            return reflector.hasGetter(prop.getName());
        }
    }

    /**
     * 获取属性的 getter 执行对象
     *
     * @param name
     * @return
     */
    public Invoker getGetInvoker(String name) {
        return reflector.getGetInvoker(name);
    }

    /**
     * 获取属性的 setter 执行对象
     *
     * @param name
     * @return
     */
    public Invoker getSetInvoker(String name) {
        return reflector.getSetInvoker(name);
    }

    /**
     * 构建属性
     *
     * @param name
     * @param builder 传进来builder是为了递归使用同一个builder
     * @return
     */
    private StringBuilder buildProperty(String name, StringBuilder builder) {
        PropertyTokenizer prop = new PropertyTokenizer(name);
        if (prop.hasNext()) {
            String propertyName = reflector.findPropertyName(prop.getName());
            if (propertyName != null) {
                builder.append(propertyName);
                builder.append(".");
                MetaClass metaProp = metaClassForProperty(propertyName);
                metaProp.buildProperty(prop.getChildren(), builder);
            }
        } else {
            // 解决大小写问题
            String propertyName = reflector.findPropertyName(name);
            if (propertyName != null) {
                builder.append(propertyName);
            }
        }
        return builder;
    }

    /**
     * 是否有默认构造函数
     *
     * @return
     */
    public boolean hasDefaultConstructor() {
        return reflector.hasDefaultConstructor();
    }

}
