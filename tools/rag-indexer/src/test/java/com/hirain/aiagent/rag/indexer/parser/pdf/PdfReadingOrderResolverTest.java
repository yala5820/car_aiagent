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

    @Test
    void shouldPreservePdfBoxTextGroupsWhenColumnsTouchAtTheCenter() {
        List<PdfTextLine> lines = new PdfReadingOrderResolver().resolve(List.of(
                new PdfGlyph("左列点线", 54, 100, 240, 10, 12, 1),
                new PdfGlyph("右列标题", 322, 100, 100, 10, 12, 2),
                new PdfGlyph("左列第二行", 54, 120, 120, 10, 12, 3),
                new PdfGlyph("右列第二行", 322, 120, 120, 10, 12, 4)), 612);

        assertEquals(List.of("左列点线", "左列第二行", "右列标题", "右列第二行"),
                lines.stream().map(PdfTextLine::text).toList());
        assertEquals(List.of(0, 0, 1, 1), lines.stream().map(PdfTextLine::columnIndex).toList());
    }

    private PdfGlyph glyph(String text, float x, float y) {
        return new PdfGlyph(text, x, y, 10, 10, 12);
    }
}
