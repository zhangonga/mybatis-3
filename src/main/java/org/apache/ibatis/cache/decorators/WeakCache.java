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

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Deque;
import java.util.LinkedList;
import java.util.concurrent.locks.ReadWriteLock;

/**
 * 基于 java.lang.ref.WeakReference 的 Cache 实现类
 * 弱引用缓存
 * <p>
 * <p>
 * Weak Reference cache decorator.
 * Thanks to Dr. Heinz Kabutz for his guidance here.
 * <p>
 * Garbage 垃圾，废物
 *
 * @author Clinton Begin
 */
public class WeakCache implements Cache {

    /**
     * 强引用的键的队列
     */
    private final Deque<Object> hardLinksToAvoidGarbageCollection;
    /**
     * 被 GC 回收的 WeakEntry 集合， 避免被 GC
     */
    private final ReferenceQueue<Object> queueOfGarbageCollectedEntries;
    /**
     * 修饰的缓存对象
     */
    private final Cache delegate;
    /**
     * {@link #hardLinksToAvoidGarbageCollection} 的大小
     */
    private int numberOfHardLinks;

    public WeakCache(Cache delegate) {
        this.delegate = delegate;
        this.numberOfHardLinks = 256;
        this.hardLinksToAvoidGarbageCollection = new LinkedList<>();
        this.queueOfGarbageCollectedEntries = new ReferenceQueue<>();
    }

    @Override
    public int getSize() {
        // 移除已经被 GC 回收的 WeakEntry
        removeGarbageCollectedItems();
        return delegate.getSize();
    }

    @Override
    public void putObject(Object key, Object value) {
        // 移除已经被 GC 回收的 WeakEntry
        removeGarbageCollectedItems();
        // 添加到 delegate 中
        delegate.putObject(key, new WeakEntry(key, value, queueOfGarbageCollectedEntries));
    }

    @Override
    public Object getObject(Object key) {
        Object result = null;
        //  获得值的 WeakReference 对象
        // assumed delegate cache is totally managed by this cache
        @SuppressWarnings("unchecked")
        WeakReference<Object> weakReference = (WeakReference<Object>) delegate.getObject(key);
        if (weakReference != null) {
            // 获得值
            result = weakReference.get();
            if (result == null) {
                // 为空，从 delegate 中移除 。为空的原因是，意味着已经被 GC 回收
                delegate.removeObject(key);
            } else {
                // 默认缓存为弱连接嘛，如果发现没有被 GC 掉，就说明引用比较强，就放入强引用中
                // 添加到 hardLinksToAvoidGarbageCollection 的队头
                // 另外，这里添加到 hardLinksToAvoidGarbageCollection 队头应该是有问题的。因为，可能存在重复添加，如果获取相同的键。
                hardLinksToAvoidGarbageCollection.addFirst(result);
                if (hardLinksToAvoidGarbageCollection.size() > numberOfHardLinks) {
                    // 超过上限，移除 hardLinksToAvoidGarbageCollection 的队尾，避免无线大
                    hardLinksToAvoidGarbageCollection.removeLast();
                }
            }
        }
        return result;
    }

    @Override
    public Object removeObject(Object key) {
        // 移除已经被 GC 回收的 WeakEntry
        removeGarbageCollectedItems();
        return delegate.removeObject(key);
    }

    @Override
    public void clear() {
        hardLinksToAvoidGarbageCollection.clear();
        removeGarbageCollectedItems();
        delegate.clear();
    }

    private void removeGarbageCollectedItems() {
        WeakEntry sv;
        // 投票投出来一个对象，然后删除
        while ((sv = (WeakEntry) queueOfGarbageCollectedEntries.poll()) != null) {
            delegate.removeObject(sv.key);
        }
    }

    /**
     * 一个内部静态类
     * 只是包含了一个缓存的 key
     */
    private static class WeakEntry extends WeakReference<Object> {
        private final Object key;

        // WeakReference 的特性， 如果对象被GC， 则放入到 garbageCollectionQueue 中
        private WeakEntry(Object key, Object value, ReferenceQueue<Object> garbageCollectionQueue) {
            super(value, garbageCollectionQueue);
            this.key = key;
        }
    }

    public void setSize(int size) {
        this.numberOfHardLinks = size;
    }

    @Override
    public String getId() {
        return delegate.getId();
    }

    @Override
    public ReadWriteLock getReadWriteLock() {
        return null;
    }
}
