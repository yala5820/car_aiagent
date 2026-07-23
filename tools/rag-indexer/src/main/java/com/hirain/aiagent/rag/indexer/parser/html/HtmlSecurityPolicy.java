package com.hirain.aiagent.rag.indexer.parser.html;

import java.util.Set;

/** 静态 HTML V1 的固定安全边界：只处理本地 DOM，永不访问 URI、执行脚本或加载子资源。 */
final class HtmlSecurityPolicy {
    static final Set<String> REMOVED_TAGS = Set.of(
            "script", "style", "noscript", "template", "iframe", "canvas", "svg",
            "form", "input", "button", "select", "textarea", "object", "embed", "video", "audio");
    static final int MINIMUM_VISIBLE_TEXT = 20;

    private HtmlSecurityPolicy() {
    }
}
