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
package org.apache.ibatis.cache.decorators;

import org.apache.ibatis.cache.Cache;

import java.util.Deque;
import java.util.LinkedList;
import java.util.concurrent.locks.ReadWriteLock;

/**
 * 基于先进先出的淘汰机制的 Cache 实现类
 * 可以理解为一种固定长度的缓存
 * 当长度够了之后
 * 每新增一个缓存，就淘汰掉一个最老的缓存
 * FIFO (first in, first out) cache decorator.
 * <p>
 * <p>
 * FifoCache 的逻辑实现上，有一定的问题，主要有两点。
 * 1，两个一模一样的缓存，会占用 list 两个位置
 * 2，移除一个缓存，还占用 list 一个位置
 *
 * @author Clinton Begin
 */
public class FifoCache implements Cache {

    /**
     * 修饰的缓存
     */
    private final Cache delegate;
    /**
     * 存放 key 的双向队列
     */
    private final Deque<Object> keyList;
    /**
     * 长度
     */
    private int size;

    public FifoCache(Cache delegate) {
        this.delegate = delegate;
        this.keyList = new LinkedList<>();
        this.size = 1024;
    }

    @Override
    public String getId() {
        return delegate.getId();
    }

    @Override
    public int getSize() {
        return delegate.getSize();
    }

    @Override
    public Object getObject(Object key) {
        return delegate.getObject(key);
    }

    @Override
    public Object removeObject(Object key) {
        return delegate.removeObject(key);
    }

    @Override
    public void clear() {
        delegate.clear();
        keyList.clear();
    }

    @Override
    public void putObject(Object key, Object value) {
        cycleKeyList(key);
        delegate.putObject(key, value);
    }

    private void cycleKeyList(Object key) {
        keyList.addLast(key);
        if (keyList.size() > size) {
            Object oldestKey = keyList.removeFirst();
            delegate.removeObject(oldestKey);
        }
    }

    /**
     * 也可以设置长度，不适用默认长度
     *
     * @param size
     */
    public void setSize(int size) {
        this.size = size;
    }

    @Override
    public ReadWriteLock getReadWriteLock() {
        return null;
    }
}
