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
package org.apache.ibatis.reflection.factory;

import org.apache.ibatis.reflection.ReflectionException;
import org.apache.ibatis.reflection.Reflector;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.util.*;

/**
 * @author Clinton Begin
 */
public class DefaultObjectFactory implements ObjectFactory, Serializable {

    private static final long serialVersionUID = -8855120656740914948L;

    @Override
    public <T> T create(Class<T> type) {
        return create(type, null, null);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T create(Class<T> type, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
        // 获取类
        Class<?> classToCreate = resolveInterface(type);
        // we know types are assignable
        return (T) instantiateClass(classToCreate, constructorArgTypes, constructorArgs);
    }

    @Override
    public void setProperties(Properties properties) {
        // no props for default
    }

    /**
     * 创建实例
     *
     * @param type                类型
     * @param constructorArgTypes 构造参数类型
     * @param constructorArgs     构造参数值
     * @param <T>
     * @return
     */
    private <T> T instantiateClass(Class<T> type, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
        try {
            Constructor<T> constructor;
            if (constructorArgTypes == null || constructorArgs == null) {
                // 如果参数类型或者参数为空，则使用默认的构造函数
                constructor = type.getDeclaredConstructor();
                try {
                    return constructor.newInstance();
                } catch (IllegalAccessException e) {
                    if (Reflector.canControlMemberAccessible()) {
                        constructor.setAccessible(true);
                        return constructor.newInstance();
                    } else {
                        throw e;
                    }
                }
            }
            // 使用带参数的构造方法
            constructor = type.getDeclaredConstructor(constructorArgTypes.toArray(new Class[constructorArgTypes.size()]));
            try {
                return constructor.newInstance(constructorArgs.toArray(new Object[constructorArgs.size()]));
            } catch (IllegalAccessException e) {
                if (Reflector.canControlMemberAccessible()) {
                    constructor.setAccessible(true);
                    return constructor.newInstance(constructorArgs.toArray(new Object[constructorArgs.size()]));
                } else {
                    throw e;
                }
            }
        } catch (Exception e) {
            // 组织异常信息并抛出
            StringBuilder argTypes = new StringBuilder();
            if (constructorArgTypes != null && !constructorArgTypes.isEmpty()) {
                for (Class<?> argType : constructorArgTypes) {
                    argTypes.append(argType.getSimpleName());
                    argTypes.append(",");
                }
                argTypes.deleteCharAt(argTypes.length() - 1); // remove trailing ,
            }
            StringBuilder argValues = new StringBuilder();
            if (constructorArgs != null && !constructorArgs.isEmpty()) {
                for (Object argValue : constructorArgs) {
                    argValues.append(String.valueOf(argValue));
                    argValues.append(",");
                }
                argValues.deleteCharAt(argValues.length() - 1); // remove trailing ,
            }
            throw new ReflectionException("Error instantiating " + type + " with invalid types (" + argTypes + ") or values (" + argValues + "). Cause: " + e, e);
        }
    }

    /**
     * 解决类是接口类的问题
     *
     * @param type
     * @return
     */
    protected Class<?> resolveInterface(Class<?> type) {
        Class<?> classToCreate;
        // 先是判断了一些特别的类，如果都不是就是默认的类
        if (type == List.class || type == Collection.class || type == Iterable.class) {
            classToCreate = ArrayList.class;
        } else if (type == Map.class) {
            classToCreate = HashMap.class;
        } else if (type == SortedSet.class) { // issue #510 Collections Support
            classToCreate = TreeSet.class;
        } else if (type == Set.class) {
            classToCreate = HashSet.class;
        } else {
            classToCreate = type;
        }
        return classToCreate;
    }

    @Override
    public <T> boolean isCollection(Class<T> type) {
        return Collection.class.isAssignableFrom(type);
    }

}
