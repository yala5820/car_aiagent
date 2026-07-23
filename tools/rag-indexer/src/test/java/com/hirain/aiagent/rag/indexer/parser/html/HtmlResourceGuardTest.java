package com.hirain.aiagent.rag.indexer.parser.html;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class HtmlResourceGuardTest {
    @Test
    void shouldRejectExcessiveDomDepth() {
        StringBuilder html = new StringBuilder("<main>");
        for (int index = 0; index < 101; index++) {
            html.append("<div>");
        }
        html.append("text");
        for (int index = 0; index < 101; index++) {
            html.append("</div>");
        }
        html.append("</main>");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new HtmlResourceGuard().validate(Jsoup.parse(html.toString()).body()));
        assertEquals("HTML_DOM_DEPTH_LIMIT_EXCEEDED", exception.getMessage());
    }
}
