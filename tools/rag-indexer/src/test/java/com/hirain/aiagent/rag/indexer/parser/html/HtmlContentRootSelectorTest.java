package com.hirain.aiagent.rag.indexer.parser.html;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class HtmlContentRootSelectorTest {
    @Test
    void shouldRequireExactlyOneSafeConfiguredRoot() {
        HtmlContentRootSelector selector = new HtmlContentRootSelector();

        assertEquals("article", selector.select(Jsoup.parse("<article class='content'>ok</article>"), ".content").tagName());
        assertThrows(IllegalArgumentException.class, () -> selector.select(Jsoup.parse("<main></main><main></main>"), "main"));
        assertThrows(IllegalArgumentException.class, () -> selector.select(Jsoup.parse("<main></main>"), "main > p"));
    }
}
