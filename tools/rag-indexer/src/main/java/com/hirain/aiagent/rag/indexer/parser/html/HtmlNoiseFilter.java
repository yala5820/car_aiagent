package com.hirain.aiagent.rag.indexer.parser.html;

import org.jsoup.nodes.Document;

/** 固定、可审计的结构性噪声规则；禁止根据任意 class/id 名称盲删可能属于正文的元素。 */
final class HtmlNoiseFilter {
    Document removeStructuralNoise(Document document) {
        document.select("nav,header,footer,aside,[role=navigation]").remove();
        return document;
    }
}
