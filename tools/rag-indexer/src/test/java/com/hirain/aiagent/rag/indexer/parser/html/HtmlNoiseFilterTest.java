package com.hirain.aiagent.rag.indexer.parser.html;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HtmlNoiseFilterTest {
    @Test
    void shouldRemoveOnlyStructuralNoiseAndKeepOrdinaryClassContent() {
        var document = Jsoup.parse("<nav>menu</nav><div class='sidebar'>real manual text</div><main>main body</main>");

        var filtered = new HtmlNoiseFilter().removeStructuralNoise(document);

        assertFalse(filtered.text().contains("menu"));
        assertTrue(filtered.text().contains("real manual text"));
    }
}
