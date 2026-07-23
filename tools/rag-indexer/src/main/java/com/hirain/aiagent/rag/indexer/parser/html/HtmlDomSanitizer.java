package com.hirain.aiagent.rag.indexer.parser.html;

import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * 清洗本地已构造 DOM。此类从不接收 URL，也不调用 Jsoup.connect，因此不能触发网络或本地子资源读取。
 */
final class HtmlDomSanitizer {
    private final java.util.List<String> excludeSelectors;

    HtmlDomSanitizer() {
        this(java.util.List.of());
    }

    HtmlDomSanitizer(java.util.List<String> excludeSelectors) {
        this.excludeSelectors = java.util.List.copyOf(excludeSelectors);
    }

    Document sanitize(Document document) {
        document.select(String.join(",", HtmlSecurityPolicy.REMOVED_TAGS)).remove();
        for (String selector : excludeSelectors) {
            document.select(selector).remove();
        }
        for (Element element : document.getAllElements()) {
            element.attributes().asList().stream().map(Attribute::getKey)
                    .filter(key -> key.toLowerCase(java.util.Locale.ROOT).startsWith("on"))
                    .toList().forEach(element::removeAttr);
            element.removeAttr("src");
            element.removeAttr("srcset");
            element.removeAttr("style");
            if (element.tagName().equals("a")) {
                element.removeAttr("href");
            }
        }
        return document;
    }
}
