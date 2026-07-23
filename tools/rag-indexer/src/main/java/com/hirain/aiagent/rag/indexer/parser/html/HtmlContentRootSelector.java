package com.hirain.aiagent.rag.indexer.parser.html;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** 内容根优先使用已审核的简单 Selector，未配置时按 main、article、body 的固定顺序选择。 */
final class HtmlContentRootSelector {
    Element select(Document document, String configuredSelector) {
        if (configuredSelector != null && !configuredSelector.isBlank()) {
            if (configuredSelector.length() > 100 || !configuredSelector.matches("[A-Za-z0-9_.#-]+")) {
                throw new IllegalArgumentException("HTML_CONTENT_ROOT_SELECTOR_INVALID");
            }
            var candidates = document.select(configuredSelector);
            if (candidates.size() != 1) {
                throw new IllegalArgumentException("HTML_CONTENT_ROOT_SELECTOR_AMBIGUOUS");
            }
            return candidates.first();
        }
        Element main = document.selectFirst("main");
        if (main != null) {
            return main;
        }
        Element article = document.selectFirst("article");
        return article != null ? article : document.body();
    }
}
