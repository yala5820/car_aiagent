package com.hirain.aiagent.rag.indexer.parser.pdf;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PdfReadingOrderResolverTest {
    @Test
    void shouldReadEachColumnCompletelyBeforeMovingToTheNextColumn() {
        List<PdfTextLine> lines = new PdfReadingOrderResolver().resolve(List.of(
                glyph("L1", 20, 100), glyph("R1", 300, 100),
                glyph("L2", 20, 120), glyph("R2", 300, 120)
        ));

        assertEquals(List.of("L1", "L2", "R1", "R2"), lines.stream().map(PdfTextLine::text).toList());
    }

    private PdfGlyph glyph(String text, float x, float y) {
        return new PdfGlyph(text, x, y, 10, 10, 12);
    }
}
