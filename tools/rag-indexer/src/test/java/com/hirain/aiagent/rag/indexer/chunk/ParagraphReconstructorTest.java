package com.hirain.aiagent.rag.indexer.chunk;

import com.hirain.aiagent.rag.indexer.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class ParagraphReconstructorTest {
    @Test
    void shouldJoinContinuousSameColumnLinesWithoutAddingCjkSpaces() {
        var blocks = List.of(pdf("第一行内容", 1, 0, 50, 50, 100, 60, 1), pdf("第二行内容。", 1, 0, 64, 50, 100, 74, 2));

        var result = new ParagraphReconstructor().reconstruct(blocks);

        assertEquals(1, result.size());
        assertEquals("第一行内容第二行内容。", result.get(0).text());
        assertEquals(2, result.get(0).blocks().size());
    }

    @Test
    void shouldNotJoinDifferentColumnsOrLargeVerticalGaps() {
        var blocks = List.of(pdf("左列", 1, 0, 50, 50, 100, 60, 1), pdf("右列", 1, 1, 64, 320, 370, 74, 2),
                pdf("新段落", 1, 1, 110, 320, 370, 120, 3));

        var result = new ParagraphReconstructor().reconstruct(blocks);

        assertEquals(3, result.size());
    }

    private StructuredBlock pdf(String text, int page, int column, float top, float left, float right, float bottom, int ordinal) {
        var locator = new SourceLocator(SourceFormat.PDF, page, page, null, 0, 0, "A", ordinal);
        var structure = new BlockStructure(0, "A", null, SequenceType.NONE, 0, false, ordinal, column, false);
        return new StructuredBlock(BlockType.PARAGRAPH, text, locator,
                new BoundingBox(left, top, right, bottom), ExtractionConfidence.HIGH, structure,
                new PdfLineMetadata(page, column, ordinal, false));
    }
}
