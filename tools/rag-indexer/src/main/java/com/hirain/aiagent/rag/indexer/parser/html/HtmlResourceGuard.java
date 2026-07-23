package com.hirain.aiagent.rag.indexer.parser.html;

import org.jsoup.nodes.Element;

/** 静态 HTML 复杂度硬上限，防止异常 DOM、超长文本或巨型表格耗尽离线构建资源。 */
final class HtmlResourceGuard {
    private static final int MAX_NODES = 100_000;
    private static final int MAX_DEPTH = 100;
    private static final int MAX_TEXT_CHARACTERS = 1_000_000;
    private static final int MAX_TABLE_CELLS = 10_000;

    void validate(Element root) {
        int nodes = root.getAllElements().size();
        if (nodes > MAX_NODES) {
            throw new IllegalArgumentException("HTML_DOM_NODE_LIMIT_EXCEEDED");
        }
        if (depth(root, 1) > MAX_DEPTH) {
            throw new IllegalArgumentException("HTML_DOM_DEPTH_LIMIT_EXCEEDED");
        }
        if (root.text().length() > MAX_TEXT_CHARACTERS) {
            throw new IllegalArgumentException("HTML_TEXT_LIMIT_EXCEEDED");
        }
        if (root.select("table td,table th").size() > MAX_TABLE_CELLS) {
            throw new IllegalArgumentException("HTML_TABLE_CELL_LIMIT_EXCEEDED");
        }
    }

    private int depth(Element element, int current) {
        int deepest = current;
        for (Element child : element.children()) {
            deepest = Math.max(deepest, depth(child, current + 1));
        }
        return deepest;
    }
}
