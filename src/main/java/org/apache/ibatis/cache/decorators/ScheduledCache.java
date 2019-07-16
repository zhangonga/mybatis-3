/**
 * Copyright 2009-2017 the original author or authors.
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

import java.util.concurrent.locks.ReadWriteLock;

/**
 * 定时清空整个容器的 Cache 实现类
 * 其实也不是定时清理啦，是没一次请求，都去检查一次而已
 *
 * @author Clinton Begin
 */
public class ScheduledCache implements Cache {

    /**
     * 装饰的缓存
     */
    private final Cache delegate;
    /**
     * 清理周期
     */
    protected long clearInterval;
    /**
     * 上次清理的时间戳
     */
    protected long lastClear;

    public ScheduledCache(Cache delegate) {
        this.delegate = delegate;
        // 1 hour
        this.clearInterval = 60 * 60 * 1000;
        // 当前时间
        this.lastClear = System.currentTimeMillis();
    }


    @Override
    public String getId() {
        return delegate.getId();
    }

    @Override
    public int getSize() {
        clearWhenStale();
        return delegate.getSize();
    }

    @Override
    public void putObject(Object key, Object object) {
        clearWhenStale();
        delegate.putObject(key, object);
    }

    @Override
    public Object getObject(Object key) {
        return clearWhenStale() ? null : delegate.getObject(key);
    }

    @Override
    public Object removeObject(Object key) {
        clearWhenStale();
        return delegate.removeObject(key);
    }

    /**
     * 检查是否要清理缓存
     *
     * @return
     */
    private boolean clearWhenStale() {
        if (System.currentTimeMillis() - lastClear > clearInterval) {
            clear();
            return true;
        }
        return false;
    }

    /**
     * 更新最后清理时间，并清理缓存
     */
    @Override
    public void clear() {
        lastClear = System.currentTimeMillis();
        delegate.clear();
    }

    @Override
    public ReadWriteLock getReadWriteLock() {
        return null;
    }

    @Override
    public int hashCode() {
        return delegate.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return delegate.equals(obj);
    }

    public void setClearInterval(long clearInterval) {
        this.clearInterval = clearInterval;
    }
}
