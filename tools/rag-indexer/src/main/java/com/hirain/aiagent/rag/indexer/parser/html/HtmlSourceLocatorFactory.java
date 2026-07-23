package com.hirain.aiagent.rag.indexer.parser.html;

import com.hirain.aiagent.rag.indexer.model.SourceFormat;
import com.hirain.aiagent.rag.indexer.model.SourceLocator;
import org.jsoup.nodes.Element;

/** 仅保留文档中真实且唯一的 id；行号不可由 Jsoup 可靠提供，按协议记为 0。 */
final class HtmlSourceLocatorFactory {
    SourceLocator create(Element element, String headingPath, int ordinal) {
        String id = element.id();
        String candidateId = id;
        if (candidateId.isBlank() || element.ownerDocument().getAllElements().stream().filter(candidate -> candidateId.equals(candidate.id())).count() != 1) {
            id = null;
        }
        return new SourceLocator(SourceFormat.STATIC_HTML, 0, 0, id, 0, 0, headingPath, ordinal);
    }
}
