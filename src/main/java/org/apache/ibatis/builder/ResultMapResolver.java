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
package org.apache.ibatis.builder;

import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;

import java.util.List;

/**
 * resultMap 解析器
 *
 * @author Eduardo Macarron
 */
public class ResultMapResolver {
    /**
     * 助手
     */
    private final MapperBuilderAssistant assistant;
    /**
     * resultMap 的 id
     */
    private final String id;
    /**
     * resultMap 的 type
     */
    private final Class<?> type;
    /**
     * 继承自那个 resultMap
     */
    private final String extend;
    /**
     * 鉴别器对象
     */
    private final Discriminator discriminator;
    /**
     * 参数集合 <result> </result> 标签
     */
    private final List<ResultMapping> resultMappings;
    /**
     * 是否自动匹配
     */
    private final Boolean autoMapping;

    /**
     * 构造方法，各种赋值
     *
     * @param assistant
     * @param id
     * @param type
     * @param extend
     * @param discriminator
     * @param resultMappings
     * @param autoMapping
     */
    public ResultMapResolver(MapperBuilderAssistant assistant, String id, Class<?> type, String extend, Discriminator discriminator, List<ResultMapping> resultMappings, Boolean autoMapping) {
        this.assistant = assistant;
        this.id = id;
        this.type = type;
        this.extend = extend;
        this.discriminator = discriminator;
        this.resultMappings = resultMappings;
        this.autoMapping = autoMapping;
    }

    /**
     * 执行解析
     *
     * @return
     */
    public ResultMap resolve() {
        return assistant.addResultMap(this.id, this.type, this.extend, this.discriminator, this.resultMappings, this.autoMapping);
    }

}