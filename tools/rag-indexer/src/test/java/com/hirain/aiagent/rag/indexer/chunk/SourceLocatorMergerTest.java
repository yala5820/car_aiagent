package com.hirain.aiagent.rag.indexer.chunk;

import com.hirain.aiagent.rag.indexer.model.SourceFormat;
import com.hirain.aiagent.rag.indexer.model.SourceLocator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class SourceLocatorMergerTest {
    @Test
    void shouldOnlyMergeContinuousRanges() {
        SourceLocator first = new SourceLocator(SourceFormat.MARKDOWN, 0, 0, null, 1, 1, "H", 1);
        SourceLocator second = new SourceLocator(SourceFormat.MARKDOWN, 0, 0, null, 2, 2, "H", 2);
        SourceLocator gap = new SourceLocator(SourceFormat.MARKDOWN, 0, 0, null, 5, 5, "H", 3);

        assertNotNull(new SourceLocatorMerger().merge(first, second));
        assertNull(new SourceLocatorMerger().merge(first, gap));
    }

    @Test
    void shouldValidateEachAdjacentStaticHtmlSection() {
        SourceLocator first = new SourceLocator(SourceFormat.STATIC_HTML, 0, 0, "first", 0, 0, "H", 1);
        SourceLocator second = new SourceLocator(SourceFormat.STATIC_HTML, 0, 0, "second", 0, 0, "H", 2);
        SourceLocator third = new SourceLocator(SourceFormat.STATIC_HTML, 0, 0, "third", 0, 0, "H", 3);

        SourceLocatorMerger merger = new SourceLocatorMerger();
        assertNotNull(merger.merge(first, second));
        assertNotNull(merger.merge(second, third));
    }
}
