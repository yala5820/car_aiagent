package com.hirain.aiagent.rag.indexer.parser.html;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HtmlDomSanitizerTest {
    @Test
    void shouldRemoveActiveAndExternalElementsWithoutFetchingAnything() {
        Document document = Jsoup.parse("<main><script>fetch('https://bad')</script><p onclick='bad()'>安全文本</p><img src='file:///bad'><a href='https://bad'>链接文本</a></main>");

        Document sanitized = new HtmlDomSanitizer().sanitize(document);

        assertFalse(sanitized.html().contains("script"));
        assertFalse(sanitized.html().contains("onclick"));
        assertFalse(sanitized.html().contains("file:///bad"));
        assertFalse(sanitized.html().contains("https://bad"));
        assertTrue(sanitized.text().contains("安全文本"));
    }
}
