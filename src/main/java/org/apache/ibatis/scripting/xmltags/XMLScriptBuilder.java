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
package org.apache.ibatis.scripting.xmltags;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.scripting.defaults.RawSqlSource;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 继承BaseBuilder , XML 动态Sql构建器，负责将 SQL 解析成 SqlSource 对象
 *
 * @author Clinton Begin
 */
public class XMLScriptBuilder extends BaseBuilder {

    /**
     * 当前 SQL XML 中的 XNode 对象
     */
    private final XNode context;
    /**
     * 是否动态 SQL
     */
    private boolean isDynamic;
    /**
     * 参数类型
     */
    private final Class<?> parameterType;
    /**
     * NodeHandler 映射，所有的node 处理器类型
     */
    private final Map<String, NodeHandler> nodeHandlerMap = new HashMap<>();

    /**
     * 构造方法
     *
     * @param configuration
     * @param context
     */
    public XMLScriptBuilder(Configuration configuration, XNode context) {
        this(configuration, context, null);
    }

    /**
     * 构造方法
     *
     * @param configuration
     * @param context
     */
    public XMLScriptBuilder(Configuration configuration, XNode context, Class<?> parameterType) {
        super(configuration);
        this.context = context;
        this.parameterType = parameterType;
        initNodeHandlerMap();
    }

    /**
     * 初始化所有的 NodeHandler
     */
    private void initNodeHandlerMap() {
        nodeHandlerMap.put("trim", new TrimHandler());
        nodeHandlerMap.put("where", new WhereHandler());
        nodeHandlerMap.put("set", new SetHandler());
        nodeHandlerMap.put("foreach", new ForEachHandler());
        nodeHandlerMap.put("if", new IfHandler());
        nodeHandlerMap.put("choose", new ChooseHandler());
        nodeHandlerMap.put("when", new IfHandler());
        nodeHandlerMap.put("otherwise", new OtherwiseHandler());
        nodeHandlerMap.put("bind", new BindHandler());
    }

    /**
     * 解析 SQL 生成 SqlSource 对象
     *
     * @return
     */
    public SqlSource parseScriptNode() {
        // 解析 SQL
        MixedSqlNode rootSqlNode = parseDynamicTags(context);
        // 创建 SqlSource 对象
        SqlSource sqlSource = null;
        if (isDynamic) {
            sqlSource = new DynamicSqlSource(configuration, rootSqlNode);
        } else {
            sqlSource = new RawSqlSource(configuration, rootSqlNode, parameterType);
        }
        return sqlSource;
    }

    /**
     * 将动态 SQL 解析成 MixedSqlNode Mixed 混合的
     *
     * @param node
     * @return
     */
    protected MixedSqlNode parseDynamicTags(XNode node) {
        // SqlNode 数组
        List<SqlNode> contents = new ArrayList<>();
        // 遍历 SQL 节点的所有子节点
        NodeList children = node.getNode().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {

            // 当前子节点
            XNode child = node.newXNode(children.item(i));
            if (child.getNode().getNodeType() == Node.CDATA_SECTION_NODE || child.getNode().getNodeType() == Node.TEXT_NODE) {
                // 获得内容
                String data = child.getStringBody("");
                // 创建 TextSqlNode
                TextSqlNode textSqlNode = new TextSqlNode(data);
                if (textSqlNode.isDynamic()) {
                    // 如果是动态 SQL
                    contents.add(textSqlNode);
                    isDynamic = true;
                } else {
                    // 否则创建静态SQl
                    contents.add(new StaticTextSqlNode(data));
                }
            } else if (child.getNode().getNodeType() == Node.ELEMENT_NODE) { // issue #628
                // 如果是元素类型

                // 获得 NodeName
                String nodeName = child.getNode().getNodeName();
                // 查询 handler， 如果handler 为空，说明是位置的 xml 元素类型
                NodeHandler handler = nodeHandlerMap.get(nodeName);
                if (handler == null) {
                    throw new BuilderException("Unknown element <" + nodeName + "> in SQL statement.");
                }
                // 根据上边获取的不同的 nodeHandler 进行处理
                handler.handleNode(child, contents);
                // 设置为动态
                isDynamic = true;
            }
        }
        // 创建 MixedSqlNode
        return new MixedSqlNode(contents);
    }

    /**
     * NodeHandler 对各种 SQL Node 类型进行处理
     * 一种 SQL Node ，对应一种 NodeHandler
     */
    private interface NodeHandler {
        void handleNode(XNode nodeToHandle, List<SqlNode> targetContents);
    }

    /**
     * 处理 bind 标签
     */
    private class BindHandler implements NodeHandler {
        public BindHandler() {
            // Prevent Synthetic Access
        }

        @Override
        public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
            // 解析 name-value 属性
            final String name = nodeToHandle.getStringAttribute("name");
            final String expression = nodeToHandle.getStringAttribute("value");
            // 创建 VarDeclSqlNode
            final VarDeclSqlNode node = new VarDeclSqlNode(name, expression);
            // 添加到 targetContents 中
            targetContents.add(node);
        }
    }

    /**
     * 处理 trim 标签
     */
    private class TrimHandler implements NodeHandler {
        public TrimHandler() {
            // Prevent Synthetic Access
        }

        @Override
        public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
            // 解析 SQL， 生成 MixedSqlNode
            MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
            // 获取 trim 的前缀 例如 (
            String prefix = nodeToHandle.getStringAttribute("prefix");
            // 获取 trim 的 prefixOverrides
            String prefixOverrides = nodeToHandle.getStringAttribute("prefixOverrides");
            // 获取 trim 的前缀 例如 ）
            String suffix = nodeToHandle.getStringAttribute("suffix");
            // 获取 trim 的 suffixOverrides
            String suffixOverrides = nodeToHandle.getStringAttribute("suffixOverrides");
            // 获得 TrimSqlNode 并放入 targetContents 中
            TrimSqlNode trim = new TrimSqlNode(configuration, mixedSqlNode, prefix, prefixOverrides, suffix, suffixOverrides);
            targetContents.add(trim);
        }
    }

    /**
     * 处理 where 标签
     */
    private class WhereHandler implements NodeHandler {
        public WhereHandler() {
            // Prevent Synthetic Access
        }

        @Override
        public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
            // 解析动态标签
            MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
            // 生成 WhereSqlNode
            WhereSqlNode where = new WhereSqlNode(configuration, mixedSqlNode);
            targetContents.add(where);
        }
    }

    /**
     * 解析 Set 标签
     */
    private class SetHandler implements NodeHandler {
        public SetHandler() {
            // Prevent Synthetic Access
        }

        @Override
        public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
            MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
            SetSqlNode set = new SetSqlNode(configuration, mixedSqlNode);
            targetContents.add(set);
        }
    }

    /**
     * 解析 forEach 标签
     */
    private class ForEachHandler implements NodeHandler {
        public ForEachHandler() {
            // Prevent Synthetic Access
        }

        @Override
        public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
            // 解析
            MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
            // 获取各个属性
            String collection = nodeToHandle.getStringAttribute("collection");
            String item = nodeToHandle.getStringAttribute("item");
            String index = nodeToHandle.getStringAttribute("index");
            String open = nodeToHandle.getStringAttribute("open");
            String close = nodeToHandle.getStringAttribute("close");
            String separator = nodeToHandle.getStringAttribute("separator");
            // 构建
            ForEachSqlNode forEachSqlNode = new ForEachSqlNode(configuration, mixedSqlNode, collection, index, item, open, close, separator);
            targetContents.add(forEachSqlNode);
        }
    }

    /**
     * 处理 if 标签
     */
    private class IfHandler implements NodeHandler {
        public IfHandler() {
            // Prevent Synthetic Access
        }

        @Override
        public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
            // 解析，获得 test 属性， 创建 ifSqlNode 放入 targetContents 中
            MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
            String test = nodeToHandle.getStringAttribute("test");
            IfSqlNode ifSqlNode = new IfSqlNode(mixedSqlNode, test);
            targetContents.add(ifSqlNode);
        }
    }

    /**
     * 处理 otherwise 标签
     */
    private class OtherwiseHandler implements NodeHandler {
        public OtherwiseHandler() {
            // Prevent Synthetic Access
        }

        @Override
        public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
            MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
            targetContents.add(mixedSqlNode);
        }
    }

    /**
     * 处理 choose 标签
     */
    private class ChooseHandler implements NodeHandler {
        public ChooseHandler() {
            // Prevent Synthetic Access
        }

        @Override
        public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
            // whenSqlNodes
            List<SqlNode> whenSqlNodes = new ArrayList<>();
            // otherwiseSqlNodes
            List<SqlNode> otherwiseSqlNodes = new ArrayList<>();
            // 解析生成相应的 SqlNode， 并放入相应的 List 中
            handleWhenOtherwiseNodes(nodeToHandle, whenSqlNodes, otherwiseSqlNodes);
            // 如果有多个 SqlNode ， 则抛出异常
            SqlNode defaultSqlNode = getDefaultSqlNode(otherwiseSqlNodes);
            // 创建 ChooseSqlNode 放入 targetContents 中
            ChooseSqlNode chooseSqlNode = new ChooseSqlNode(whenSqlNodes, defaultSqlNode);
            targetContents.add(chooseSqlNode);
        }

        /**
         * 处理 when 和 other node
         *
         * @param chooseSqlNode
         * @param ifSqlNodes
         * @param defaultSqlNodes
         */
        private void handleWhenOtherwiseNodes(XNode chooseSqlNode, List<SqlNode> ifSqlNodes, List<SqlNode> defaultSqlNodes) {
            List<XNode> children = chooseSqlNode.getChildren();
            for (XNode child : children) {
                // 遍历

                // 获取 nodeName
                String nodeName = child.getNode().getNodeName();
                // 获取 handler
                NodeHandler handler = nodeHandlerMap.get(nodeName);
                if (handler instanceof IfHandler) {
                    // 处理，生成 SqlNode
                    handler.handleNode(child, ifSqlNodes);
                } else if (handler instanceof OtherwiseHandler) {
                    // 处理，生成 SqlNode
                    handler.handleNode(child, defaultSqlNodes);
                }
            }
        }

        /**
         * 至多允许一个 SqlNode
         *
         * @param defaultSqlNodes
         * @return
         */
        private SqlNode getDefaultSqlNode(List<SqlNode> defaultSqlNodes) {
            SqlNode defaultSqlNode = null;
            if (defaultSqlNodes.size() == 1) {
                defaultSqlNode = defaultSqlNodes.get(0);
            } else if (defaultSqlNodes.size() > 1) {
                throw new BuilderException("Too many default (otherwise) elements in choose statement.");
            }
            return defaultSqlNode;
        }
    }

}
