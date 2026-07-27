package com.hirain.aiagent.rag.indexer.parser.pdf;

import com.hirain.aiagent.rag.indexer.model.BoundingBox;
import com.hirain.aiagent.rag.indexer.model.BlockType;
import com.hirain.aiagent.rag.indexer.model.StructuredBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PdfTocReferenceExtractorTest {
    @Test
    void shouldExtractMultiPageTocAndExcludeTocPagesFromBlocks() {
        PdfPageLayout toc1 = page(3, List.of(
                line("目录", 20, 20),
                line("1 车辆概述........3", 20, 40),
                line("1.1 安全........4", 40, 60),
                line("2 车辆设置........8", 20, 80)));
        PdfPageLayout toc2 = page(4, List.of(
                line("1.2 维护........5", 40, 40),
                line("1.3 车控........6", 40, 60)));
        PdfPageLayout body = page(5, List.of(
                line("1 车辆概述", 20, 20),
                line("1.1 安全", 40, 55),
                line("安全说明正文。", 40, 90)));

        PdfTocReference reference = new PdfTocReferenceExtractor().extract(List.of(toc1, toc2, body));

        assertEquals(Set.of(3, 4), reference.tocPages());
        assertEquals(5, reference.entries().size());
        assertEquals(2, reference.match(body.lines().get(1), 5).level());

        List<StructuredBlock> blocks = new PdfBlockAssembler().assemble(List.of(toc1, toc2, body), Set.of(), reference);
        assertEquals(3, blocks.size());
        assertTrue(blocks.stream().allMatch(block -> block.locator().pdfPageStart() == 5));
        assertEquals(List.of(1, 2, 0), blocks.stream().map(block -> block.structure().headingLevel()).toList());
        assertEquals("1 车辆概述 > 1.1 安全", blocks.get(2).structure().sectionPath());
    }

    private PdfPageLayout page(int number, List<PdfTextLine> lines) {
        return new PdfPageLayout(number, 600, PdfColumnLayout.infer(600, lines), lines);
    }

    private PdfTextLine line(String text, float left, float top) {
        return new PdfTextLine(text, new BoundingBox(left, top, left + 300, top + 10), 12, -1, true, (int) top);
    }
}
