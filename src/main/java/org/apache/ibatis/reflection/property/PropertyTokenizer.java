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
package org.apache.ibatis.reflection.property;

import java.util.Iterator;

/**
 * 属性分词器
 * "order[0].item[0].name" 时，我们希望拆分成 "order[0]"、"item[0]"、"name" 三段，就可以用这个类
 *
 * @author Clinton Begin
 */
public class PropertyTokenizer implements Iterator<PropertyTokenizer> {
    /**
     * 当前字符串
     */
    private String name;
    /**
     * 索引的 {@link #name} ，因为 {@link #name} 如果存在 {@link #index} 会被更改
     */
    private final String indexedName;
    /**
     * 编号
     * 对于数组name[0] index = 0;
     * 对于数组name[key, value] index = key;
     */
    private String index;
    /**
     * 剩余字符串
     */
    private final String children;

    public PropertyTokenizer(String fullName) {
        int delim = fullName.indexOf('.');
        if (delim > -1) {
            name = fullName.substring(0, delim);
            children = fullName.substring(delim + 1);
        } else {
            name = fullName;
            children = null;
        }
        indexedName = name;
        delim = name.indexOf('[');
        if (delim > -1) {
            index = name.substring(delim + 1, name.length() - 1);
            name = name.substring(0, delim);
        }
    }

    public String getName() {
        return name;
    }

    public String getIndex() {
        return index;
    }

    public String getIndexedName() {
        return indexedName;
    }

    public String getChildren() {
        return children;
    }

    /**
     * 是否有下一个元素
     *
     * @return
     */
    @Override
    public boolean hasNext() {
        return children != null;
    }

    /**
     * 迭代获取下一个PropertyTokenizer对象
     *
     * @return
     */
    @Override
    public PropertyTokenizer next() {
        return new PropertyTokenizer(children);
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException("Remove is not supported, as it has no meaning in the context of properties.");
    }
}
