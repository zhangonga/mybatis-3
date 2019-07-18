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
package org.apache.ibatis.logging.slf4j;

import org.apache.ibatis.logging.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.spi.LocationAwareLogger;

/**
 * 实现 Log 接口，是 Slf4j 的实现类
 * 执行 mybatis 的 log.info 的时候，会根据不同的 slf4j 的版本，去执行不同的 slf4jLoggerImpl 或者 slf4jLocationAwareImpl 的 info 方法
 * 而最终 slf4jLoggerImpl 或者 Slf4jLocationAwareImpl 去执行 slf4j 的不同版本的 Logger
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class Slf4jImpl implements Log {

    private Log log;

    public Slf4jImpl(String clazz) {
        // 首先获取 slf4j 的 Log 对象
        Logger logger = LoggerFactory.getLogger(clazz);

        // 如果 slf4j >= 1.6 版本，使用 Slf4jLocationAwareLoggerImpl
        // 否则使用 Slf4jLoggerImpl
        if (logger instanceof LocationAwareLogger) {
            try {
                // check for slf4j >= 1.6 method signature
                // 这里其实是获取一下 method 来校验是否大于 1.6 版本，如果不是，就抛异常了。
                logger.getClass().getMethod("log", Marker.class, String.class, int.class, String.class, Object[].class, Throwable.class);
                log = new Slf4jLocationAwareLoggerImpl((LocationAwareLogger) logger);
                return;
            } catch (SecurityException e) {
                // fail-back to Slf4jLoggerImpl
            } catch (NoSuchMethodException e) {
                // fail-back to Slf4jLoggerImpl
            }
        }

        // Logger is not LocationAwareLogger or slf4j version < 1.6
        log = new Slf4jLoggerImpl(logger);
    }

    @Override
    public boolean isDebugEnabled() {
        return log.isDebugEnabled();
    }

    @Override
    public boolean isTraceEnabled() {
        return log.isTraceEnabled();
    }

    @Override
    public void error(String s, Throwable e) {
        log.error(s, e);
    }

    @Override
    public void error(String s) {
        log.error(s);
    }

    @Override
    public void debug(String s) {
        log.debug(s);
    }

    @Override
    public void trace(String s) {
        log.trace(s);
    }

    @Override
    public void warn(String s) {
        log.warn(s);
    }

}
