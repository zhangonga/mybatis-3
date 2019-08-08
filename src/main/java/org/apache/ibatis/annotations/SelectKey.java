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

import org.apache.ibatis.mapping.StatementType;

import java.lang.annotation.*;

/**
 * 通过 SQL 语句获得主键的注解
 *
 * @author Clinton Begin
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SelectKey {
    /**
     * @return 获取数据库主键值得语句
     */
    String[] statement();

    /**
     * @return Java 对象对应的数据库主键的属性
     */
    String keyProperty();

    /**
     * @return 数据库主键字段
     */
    String keyColumn() default "";

    /**
     * @return 在插入语句执行前，还是执行后设置主键
     */
    boolean before();

    /**
     * @return 返回类型
     */
    Class<?> resultType();

    /**
     * @return {@link #statement()} 的类型
     */
    StatementType statementType() default StatementType.PREPARED;
}
