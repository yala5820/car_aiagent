package com.hirain.aiagent.rag.indexer.parser.html;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class HtmlSourceLocatorFactoryTest {
    @Test
    void shouldKeepOnlyUniqueRealElementIds() {
        var unique = Jsoup.parse("<main><p id='one'>text</p></main>").selectFirst("p");
        var duplicate = Jsoup.parse("<main><p id='same'>a</p><p id='same'>b</p></main>").selectFirst("p");
        HtmlSourceLocatorFactory factory = new HtmlSourceLocatorFactory();

        assertEquals("one", factory.create(unique, "H", 1).htmlElementId());
        assertNull(factory.create(duplicate, "H", 1).htmlElementId());
    }
}
