package com.hirain.aiagent.rag.indexer.parser.pdf;

import com.hirain.aiagent.rag.indexer.model.BlockType;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class PdfBlockAssemblerTest {
    @Test
    void shouldKeepOneVisualLinePerBlockAndExposeColumnMetadata() {
        List<PdfTextLine> lines = List.of(
                new PdfTextLine("1 维护", new com.hirain.aiagent.rag.indexer.model.BoundingBox(54, 50, 120, 64), 20, 0, false, 0),
                new PdfTextLine("检查制动液位", new com.hirain.aiagent.rag.indexer.model.BoundingBox(54, 80, 180, 92), 10, 0, false, 1),
                new PdfTextLine("右列内容", new com.hirain.aiagent.rag.indexer.model.BoundingBox(322, 80, 400, 92), 10, 1, false, 0));
        PdfPageLayout page = new PdfPageLayout(1, 612, PdfColumnLayout.infer(612, lines), lines);

        var blocks = new PdfBlockAssembler().assemble(List.of(page), Set.of());

        assertEquals(3, blocks.size());
        assertEquals(BlockType.HEADING, blocks.get(0).type());
        assertEquals(BlockType.PARAGRAPH, blocks.get(1).type());
        assertEquals(0, blocks.get(1).structure().columnIndex());
        assertNotNull(blocks.get(1).pdfLineMetadata());
        assertEquals(1, blocks.get(2).structure().columnIndex());
    }
}
