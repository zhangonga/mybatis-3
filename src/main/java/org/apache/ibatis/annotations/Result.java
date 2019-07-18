/**
 * Copyright 2009-2016 the original author or authors.
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
package org.apache.ibatis.annotations;

import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.UnknownTypeHandler;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 结果字段的注解
 *
 * @author Clinton Begin
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface Result {
    /**
     * 是否是id
     *
     * @return
     */
    boolean id() default false;

    /**
     * 数据库列名
     *
     * @return
     */
    String column() default "";

    /**
     * java entity 的属性
     *
     * @return
     */
    String property() default "";

    /**
     * Java entity 的属性的类型
     *
     * @return
     */
    Class<?> javaType() default void.class;

    /**
     * 数据库字段的类型
     *
     * @return
     */
    JdbcType jdbcType() default JdbcType.UNDEFINED;

    /**
     * 指定类型转换器，默认是 UnknownTypeHandler , 不过 UnknownTypeHandler 会根据数据类型转换成功具体类型的转换器
     *
     * @return
     */
    Class<? extends TypeHandler> typeHandler() default UnknownTypeHandler.class;

    /**
     * 多对一的注解
     *
     * @return
     */
    One one() default @One;

    /**
     * 一对多的注解
     *
     * @return
     */
    Many many() default @Many;
}
