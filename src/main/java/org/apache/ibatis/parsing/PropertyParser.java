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
package org.apache.ibatis.parsing;

import java.util.Properties;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class PropertyParser {

    private static final String KEY_PREFIX = "org.apache.ibatis.parsing.PropertyParser.";
    /**
     * The special property key that indicate whether enable a default value on placeholder.
     * <p>
     * The default value is {@code false} (indicate disable a default value on placeholder)
     * If you specify the {@code true}, you can specify key and default value on placeholder (e.g. {@code ${db.username:postgres}}).
     * </p>
     *
     * @since 3.4.2
     */
    public static final String KEY_ENABLE_DEFAULT_VALUE = KEY_PREFIX + "enable-default-value";

    /**
     * The special property key that specify a separator for key and default value on placeholder.
     * <p>
     * The default separator is {@code ":"}.
     * </p>
     *
     * @since 3.4.2
     */
    public static final String KEY_DEFAULT_VALUE_SEPARATOR = KEY_PREFIX + "default-value-separator";

    private static final String ENABLE_DEFAULT_VALUE = "false";
    private static final String DEFAULT_VALUE_SEPARATOR = ":";

    private PropertyParser() {
        // Prevent Instantiation
    }

    public static String parse(String string, Properties variables) {
        // 创建一个 tokenHandler 对象
        VariableTokenHandler handler = new VariableTokenHandler(variables);
        // 创建一个通用的解析器 GenericTokenParser 并把 tokenHandler 传递给它
        // 竟然写死了
        GenericTokenParser parser = new GenericTokenParser("${", "}", handler);
        // 执行解析
        return parser.parse(string);
    }

    private static class VariableTokenHandler implements TokenHandler {
        private final Properties variables;
        private final boolean enableDefaultValue;
        private final String defaultValueSeparator;

        private VariableTokenHandler(Properties variables) {
            this.variables = variables;
            // 是否开启默认值，可以配置
            this.enableDefaultValue = Boolean.parseBoolean(getPropertyValue(KEY_ENABLE_DEFAULT_VALUE, ENABLE_DEFAULT_VALUE));
            // 指定默认分隔符，可以配置
            this.defaultValueSeparator = getPropertyValue(KEY_DEFAULT_VALUE_SEPARATOR, DEFAULT_VALUE_SEPARATOR);
        }

        private String getPropertyValue(String key, String defaultValue) {
            return (variables == null) ? defaultValue : variables.getProperty(key, defaultValue);
        }

        @Override
        public String handleToken(String content) {
            if (variables != null) {
                String key = content;
                if (enableDefaultValue) {
                    // 开启默认值，则先从 content 里获取默认值
                    final int separatorIndex = content.indexOf(defaultValueSeparator);
                    String defaultValue = null;
                    if (separatorIndex >= 0) {
                        key = content.substring(0, separatorIndex);
                        defaultValue = content.substring(separatorIndex + defaultValueSeparator.length());
                    }
                    // 有默认值，则进行替换，不存在配置值则返回默认值，如果没有默认值，则往下执行。
                    if (defaultValue != null) {
                        return variables.getProperty(key, defaultValue);
                    }
                }
                // 未开启默认值，则直接替换
                if (variables.containsKey(key)) {
                    return variables.getProperty(key);
                }
            }
            // 解析不出来就还加上开头和结尾，相当于没处理呗
            return "${" + content + "}";
        }
    }

}
