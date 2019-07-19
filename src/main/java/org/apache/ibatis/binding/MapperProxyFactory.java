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
package org.apache.ibatis.binding;

import org.apache.ibatis.session.SqlSession;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * mapper Proxy 的工厂类
 *
 * @author Lasse Voss
 */
public class MapperProxyFactory<T> {

    /**
     * mapper 接口
     */
    private final Class<T> mapperInterface;
    /**
     * mapper 接口的原生方法和 MapperMethod 的映射
     */
    private final Map<Method, MapperMethod> methodCache = new ConcurrentHashMap<>();

    /**
     * 构造方法，指定代理的接口
     *
     * @param mapperInterface
     */
    public MapperProxyFactory(Class<T> mapperInterface) {
        this.mapperInterface = mapperInterface;
    }

    /**
     * 获取代理的接口
     *
     * @return
     */
    public Class<T> getMapperInterface() {
        return mapperInterface;
    }

    /**
     * 获取 mapper 方法的缓存
     *
     * @return
     */
    public Map<Method, MapperMethod> getMethodCache() {
        return methodCache;
    }

    /**
     * 创建 mapper 的代理
     *
     * @param mapperProxy
     * @return
     */
    @SuppressWarnings("unchecked")
    protected T newInstance(MapperProxy<T> mapperProxy) {
        return (T) Proxy.newProxyInstance(mapperInterface.getClassLoader(), new Class[]{mapperInterface}, mapperProxy);
    }

    /**
     * 根据 sqlSession 先创建 mapperProxy， 再调用上边的方法创建 mapper Proxy 对象
     *
     * @param sqlSession
     * @return
     */
    public T newInstance(SqlSession sqlSession) {
        final MapperProxy<T> mapperProxy = new MapperProxy<>(sqlSession, mapperInterface, methodCache);
        return newInstance(mapperProxy);
    }
}
