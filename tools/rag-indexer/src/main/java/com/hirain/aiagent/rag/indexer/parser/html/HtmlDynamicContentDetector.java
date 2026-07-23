package com.hirain.aiagent.rag.indexer.parser.html;

import org.jsoup.nodes.Element;

/** 脚本等内容清洗后正文不足时，明确判为不受支持的动态页面，而非把导航或空页面当知识。 */
final class HtmlDynamicContentDetector {
    boolean isUnsupported(Element contentRoot) {
        return contentRoot.text().trim().length() < HtmlSecurityPolicy.MINIMUM_VISIBLE_TEXT;
    }
}
