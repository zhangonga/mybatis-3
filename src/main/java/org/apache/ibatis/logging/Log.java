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
package org.apache.ibatis.logging;

/**
 * @author Clinton Begin
 */
public interface Log {

    /**
     * 是否打印 debug 日志
     *
     * @return
     */
    boolean isDebugEnabled();

    /**
     * 是否可追踪
     *
     * @return
     */
    boolean isTraceEnabled();

    /**
     * 打印错误日志
     *
     * @param s
     * @param e
     */
    void error(String s, Throwable e);

    /**
     * 打印错误日志
     *
     * @param s
     */
    void error(String s);

    /**
     * 打印 debug 日志
     *
     * @param s
     */
    void debug(String s);

    /**
     * 打印追踪日志
     *
     * @param s
     */
    void trace(String s);

    /**
     * 打印警告日志
     *
     * @param s
     */
    void warn(String s);
}
