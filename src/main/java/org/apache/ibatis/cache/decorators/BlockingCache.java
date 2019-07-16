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
package org.apache.ibatis.cache.decorators;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheException;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 阻塞的 Cache 实现类。
 * Simple blocking decorator
 * <p>
 * 这里的阻塞比较特殊，当线程去获取缓存值时，如果不存在，则会阻塞后续的其他线程去获取该缓存。
 * 为什么这么有这样的设计呢？因为当线程 A 在获取不到缓存值时，一般会去设置对应的缓存值，这样就避免其他也需要该缓存的线程 B、C 等，重复添加缓存。
 *
 * <p>
 * Simple and inefficient version of EhCache's BlockingCache decorator.
 * It sets a lock over a cache key when the element is not found in cache.
 * This way, other threads will wait until this element is filled instead of hitting the database.
 *
 * @author Eduardo Macarron
 */
public class BlockingCache implements Cache {

    /**
     * 加锁的超时时间
     */
    private long timeout;
    private final Cache delegate;
    /**
     * 缓存键与 ReentrantLock 对象的映射。
     */
    private final ConcurrentHashMap<Object, ReentrantLock> locks;

    public BlockingCache(Cache delegate) {
        // 被装饰的类
        this.delegate = delegate;
        // 所有的锁，一个缓存一个锁，代价有点高啊。。。
        this.locks = new ConcurrentHashMap<>();
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
    public void putObject(Object key, Object value) {
        try {
            delegate.putObject(key, value);
        } finally {
            // 设置了值之后，会立即释放锁
            releaseLock(key);
        }
    }

    @Override
    public Object getObject(Object key) {
        // 加锁
        acquireLock(key);
        Object value = delegate.getObject(key);
        if (value != null) {
            // 获取到值释放锁，否则不释放锁，设置了值之后，会立即释放锁，或者设置超时时间，等超时。
            releaseLock(key);
        }
        return value;
    }

    @Override
    public Object removeObject(Object key) {
        // despite of its name, this method is called only to release locks
        // 没有remove 只有释放锁
        releaseLock(key);
        return null;
    }

    private void acquireLock(Object key) {
        Lock lock = getLockForKey(key);
        if (timeout > 0) {
            try {
                boolean acquired = lock.tryLock(timeout, TimeUnit.MILLISECONDS);
                if (!acquired) {
                    // 加锁失败，抛出异常
                    throw new CacheException("Couldn't get a lock in " + timeout + " for the key " + key + " at the cache " + delegate.getId());
                }
            } catch (InterruptedException e) {
                throw new CacheException("Got interrupted while trying to acquire lock for key " + key, e);
            }
        } else {
            // timeout <= 0 说明没有设置超时时间，直接加锁，小心死锁哦。
            lock.lock();
        }
    }

    private ReentrantLock getLockForKey(Object key) {
        return locks.computeIfAbsent(key, k -> new ReentrantLock());
    }

    /**
     * 释放锁
     *
     * @param key
     */
    private void releaseLock(Object key) {
        ReentrantLock lock = locks.get(key);
        if (lock.isHeldByCurrentThread()) {
            // 如果当前线程持有，则释放锁
            lock.unlock();
        }
    }


    @Override
    public void clear() {
        delegate.clear();
    }

    @Override
    public ReadWriteLock getReadWriteLock() {
        return null;
    }

    public long getTimeout() {
        return timeout;
    }

    public void setTimeout(long timeout) {
        this.timeout = timeout;
    }
}
