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
package org.apache.ibatis.scripting.xmltags;

import org.apache.ibatis.parsing.GenericTokenParser;
import org.apache.ibatis.parsing.TokenHandler;
import org.apache.ibatis.scripting.ScriptingException;
import org.apache.ibatis.type.SimpleTypeRegistry;

import java.util.regex.Pattern;

/**
 * 文本 SQL node
 * 相比 StaticTextSqlNode 的实现来说，TextSqlNode 不确定是否为静态文本，所以提供 #isDynamic() 方法，进行判断是否为动态文本
 *
 * @author Clinton Begin
 */
public class TextSqlNode implements SqlNode {
    private final String text;
    private final Pattern injectionFilter;

    /**
     * 构造方法
     *
     * @param text 传入SQL
     */
    public TextSqlNode(String text) {
        this(text, null);
    }

    /**
     * 构造方法
     *
     * @param text            传入SQL
     * @param injectionFilter 暂时无用
     */
    public TextSqlNode(String text, Pattern injectionFilter) {
        this.text = text;
        this.injectionFilter = injectionFilter;
    }

    /**
     * 判断是否是动态SQL
     *
     * @return
     */
    public boolean isDynamic() {
        // 创建 DynamicCheckerTokenParser 对象
        DynamicCheckerTokenParser checker = new DynamicCheckerTokenParser();
        // 创建通用的 GenericTokenParser 对象，其实就是用来解析 ${} 这种字符串的
        GenericTokenParser parser = createParser(checker);
        // 执行解析
        parser.parse(text);
        // 判断
        return checker.isDynamic();
    }

    @Override
    public boolean apply(DynamicContext context) {
        GenericTokenParser parser = createParser(new BindingTokenParser(context, injectionFilter));
        context.appendSql(parser.parse(text));
        return true;
    }

    private GenericTokenParser createParser(TokenHandler handler) {
        return new GenericTokenParser("${", "}", handler);
    }

    private static class BindingTokenParser implements TokenHandler {

        private DynamicContext context;
        private Pattern injectionFilter;

        public BindingTokenParser(DynamicContext context, Pattern injectionFilter) {
            this.context = context;
            this.injectionFilter = injectionFilter;
        }

        @Override
        public String handleToken(String content) {
            Object parameter = context.getBindings().get("_parameter");
            if (parameter == null) {
                context.getBindings().put("value", null);
            } else if (SimpleTypeRegistry.isSimpleType(parameter.getClass())) {
                context.getBindings().put("value", parameter);
            }
            Object value = OgnlCache.getValue(content, context.getBindings());
            String srtValue = value == null ? "" : String.valueOf(value); // issue #274 return "" instead of "null"
            checkInjection(srtValue);
            return srtValue;
        }

        private void checkInjection(String value) {
            if (injectionFilter != null && !injectionFilter.matcher(value).matches()) {
                throw new ScriptingException("Invalid input. Please conform to regex" + injectionFilter.pattern());
            }
        }
    }

    /**
     * 动态 SQL 检查类
     * 实现了 TokenHandler
     * 在 动态 SQL 的解析中，如果找到了 ${xx} 这样的东西，就会调用 handleToken
     * 这里重写了，就设置为包含动态 SQL
     */
    private static class DynamicCheckerTokenParser implements TokenHandler {

        private boolean isDynamic;

        public DynamicCheckerTokenParser() {
            // Prevent Synthetic Access
        }

        public boolean isDynamic() {
            return isDynamic;
        }

        @Override
        public String handleToken(String content) {
            this.isDynamic = true;
            return null;
        }
    }
}
