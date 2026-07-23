package com.hirain.aiagent.rag.indexer.parser.pdf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PdfPrintedPageLabelResolverTest {
    @Test
    void shouldOnlyReturnUnambiguousNumericLabels() {
        PdfPrintedPageLabelResolver resolver = new PdfPrintedPageLabelResolver();

        assertEquals("12", resolver.resolve(" 12 ").orElseThrow());
        assertTrue(resolver.resolve("第 12 页").isEmpty());
    }
}
