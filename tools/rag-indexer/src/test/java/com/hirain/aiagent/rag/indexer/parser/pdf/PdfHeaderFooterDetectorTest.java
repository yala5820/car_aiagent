package com.hirain.aiagent.rag.indexer.parser.pdf;

import com.hirain.aiagent.rag.indexer.model.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PdfHeaderFooterDetectorTest {
    @Test
    void shouldOnlyTreatRepeatedEdgeTextAsHeaderOrFooter() {
        List<PdfPageLayout> pages = List.of(
                page(1, "车辆手册", "正文一", "1"),
                page(2, "车辆手册", "正文二", "2")
        );

        assertEquals(java.util.Set.of("车辆手册"), new PdfHeaderFooterDetector().detectRepeatedEdgeLines(pages));
    }

    private PdfPageLayout page(int number, String... lines) {
        return new PdfPageLayout(number, java.util.Arrays.stream(lines)
                .map(text -> new PdfTextLine(text, new BoundingBox(0, 0, 10, 10), 12)).toList());
    }
}
