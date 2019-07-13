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
package org.apache.ibatis.reflection;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class DefaultReflectorFactory implements ReflectorFactory {

    /**
     * 默认可以缓存类
     */
    private boolean classCacheEnabled = true;
    /**
     * 所有的反射，key 反射的类， value 生成的反射类， classCacheEnabled = true 放入该map
     */
    private final ConcurrentMap<Class<?>, Reflector> reflectorMap = new ConcurrentHashMap<>();

    public DefaultReflectorFactory() {
    }

    @Override
    public boolean isClassCacheEnabled() {
        return classCacheEnabled;
    }

    @Override
    public void setClassCacheEnabled(boolean classCacheEnabled) {
        this.classCacheEnabled = classCacheEnabled;
    }

    @Override
    public Reflector findForClass(Class<?> type) {
        if (classCacheEnabled) {
            // synchronized (type) removed see issue #461
            // 两个参数，第一个是一个变量，第二个是一个方法，变量是方法的入参。
            // 通过方法的计算，得到一个返回值，并通过变量判断map中是否有这个值
            // 如果没有则key 为 type， value为返回值，放入map中。
            // JDK 1.8 新增的方法
            return reflectorMap.computeIfAbsent(type, Reflector::new);
        } else {
            // 如果不允许缓存，直接返回
            return new Reflector(type);
        }
    }

}
