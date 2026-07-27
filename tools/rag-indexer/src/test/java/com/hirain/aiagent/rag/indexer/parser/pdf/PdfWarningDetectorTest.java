package com.hirain.aiagent.rag.indexer.parser.pdf;

import com.hirain.aiagent.rag.indexer.model.BlockType;
import com.hirain.aiagent.rag.indexer.model.ExtractionConfidence;
import com.hirain.aiagent.rag.indexer.model.SourceFormat;
import com.hirain.aiagent.rag.indexer.model.SourceLocator;
import com.hirain.aiagent.rag.indexer.model.StructuredBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PdfWarningDetectorTest {
    @Test
    void shouldMarkExplicitWarningTextWithoutUsingColor() {
        StructuredBlock source = new StructuredBlock(BlockType.PARAGRAPH, "警告：制动液不足时禁止行驶",
                new SourceLocator(SourceFormat.PDF, 1, 1, null, 0, 0, null, 1), null, ExtractionConfidence.MEDIUM);

        assertEquals(BlockType.WARNING, new PdfWarningDetector().markWarnings(List.of(source)).get(0).type());
    }

    @Test
    void shouldKeepImmediateSamePageConditionsWithWarning() {
        SourceLocator locator = new SourceLocator(SourceFormat.PDF, 1, 1, null, 0, 0, null, 1);
        List<StructuredBlock> source = List.of(
                new StructuredBlock(BlockType.PARAGRAPH, "警告：制动液不足时禁止行驶", locator, null, ExtractionConfidence.MEDIUM),
                new StructuredBlock(BlockType.PARAGRAPH, "请先补充至规定液位", locator, null, ExtractionConfidence.MEDIUM),
                new StructuredBlock(BlockType.HEADING, "维护说明", locator, null, ExtractionConfidence.MEDIUM));

        List<StructuredBlock> result = new PdfWarningDetector().markWarnings(source);

        assertEquals(3, result.size());
        assertEquals(BlockType.WARNING, result.get(0).type());
        assertEquals("警告：制动液不足时禁止行驶", result.get(0).text());
        assertEquals(BlockType.WARNING, result.get(1).type());
        assertEquals("请先补充至规定液位", result.get(1).text());
    }

    @Test
    void shouldNotTreatAnOrdinaryDirectoryEntryContainingWarningWordAsWarning() {
        StructuredBlock source = new StructuredBlock(BlockType.PARAGRAPH, "辅助驾驶限制和警告................................114",
                new SourceLocator(SourceFormat.PDF, 3, 3, null, 0, 0, null, 1), null, ExtractionConfidence.MEDIUM);

        assertEquals(BlockType.PARAGRAPH, new PdfWarningDetector().markWarnings(List.of(source)).get(0).type());
    }

    @Test
    void shouldNotAbsorbLaterSamePageParagraphAfterCompletedWarningSentence() {
        SourceLocator locator = new SourceLocator(SourceFormat.PDF, 1, 1, null, 0, 0, null, 1);
        List<StructuredBlock> source = List.of(
                new StructuredBlock(BlockType.PARAGRAPH, "警告：请勿在行驶时操作触摸屏。", locator, null, ExtractionConfidence.MEDIUM),
                new StructuredBlock(BlockType.PARAGRAPH, "后续普通说明不属于警告。", locator, null, ExtractionConfidence.MEDIUM));

        List<StructuredBlock> result = new PdfWarningDetector().markWarnings(source);

        assertEquals(2, result.size());
        assertEquals("警告：请勿在行驶时操作触摸屏。", result.get(0).text());
        assertEquals(BlockType.PARAGRAPH, result.get(1).type());
    }
}
