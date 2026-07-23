package com.hirain.aiagent.rag.indexer.parser;

import com.hirain.aiagent.rag.indexer.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 语义 Block 归一化必须恢复句子边界，但不能跨栏或无条件跨页合并。 */
final class SemanticBlockNormalizerTest {
    @Test
    void shouldSplitParagraphAtCompleteSentenceBoundariesAndKeepClosingQuote() {
        SourceLocator locator = locator(SourceFormat.MARKDOWN, 1, 1, 1, "Maintenance");
        ParseResult parsed = new ParseResult(List.of(
                block(BlockType.PARAGRAPH, "第一句完成。第二句也完成。”第三句完成。", locator, null, false)), List.of());

        List<StructuredBlock> result = new SemanticBlockNormalizer().normalize(parsed).blocks();

        assertEquals(List.of("第一句完成。", "第二句也完成。”", "第三句完成。"), result.stream().map(StructuredBlock::text).toList());
    }

    @Test
    void shouldMergeOnlyAnExplicitPdfCrossPageContinuation() {
        StructuredBlock first = block(BlockType.PARAGRAPH, "跨页句子尚未完成", locator(SourceFormat.PDF, 1, 1, 1, "Maintenance"), new BoundingBox(100, 100, 400, 120), false);
        StructuredBlock second = block(BlockType.PARAGRAPH, "下一页继续完成。", locator(SourceFormat.PDF, 2, 2, 2, "Maintenance"), new BoundingBox(100, 100, 400, 120), false);

        List<StructuredBlock> result = new SemanticBlockNormalizer().normalize(new ParseResult(List.of(first, second), List.of())).blocks();

        assertEquals(1, result.size());
        assertEquals("跨页句子尚未完成下一页继续完成。", result.get(0).text());
        assertEquals(1, result.get(0).locator().pdfPageStart());
        assertEquals(2, result.get(0).locator().pdfPageEnd());
    }

    @Test
    void shouldNotMergeCompletedSentenceOrDifferentPdfColumns() {
        StructuredBlock completed = block(BlockType.PARAGRAPH, "上一页已经完成。", locator(SourceFormat.PDF, 1, 1, 1, "Maintenance"), new BoundingBox(100, 100, 400, 120), false);
        StructuredBlock nextPage = block(BlockType.PARAGRAPH, "下一页是新段落。", locator(SourceFormat.PDF, 2, 2, 2, "Maintenance"), new BoundingBox(100, 100, 400, 120), false);
        StructuredBlock left = block(BlockType.PARAGRAPH, "左栏句子尚未完成", locator(SourceFormat.PDF, 3, 3, 3, "Maintenance"), new BoundingBox(100, 100, 200, 120), false);
        StructuredBlock right = block(BlockType.PARAGRAPH, "右栏继续内容。", locator(SourceFormat.PDF, 3, 3, 4, "Maintenance"), new BoundingBox(600, 100, 700, 120), false);

        List<StructuredBlock> result = new SemanticBlockNormalizer().normalize(new ParseResult(List.of(completed, nextPage, left, right), List.of())).blocks();

        assertEquals(List.of("上一页已经完成。", "下一页是新段落。", "左栏句子尚未完成", "右栏继续内容。"), result.stream().map(StructuredBlock::text).toList());
    }

    @Test
    void shouldKeepAtomicBlocksUntouched() {
        SourceLocator locator = locator(SourceFormat.STATIC_HTML, 0, 0, 1, "Maintenance");
        StructuredBlock warning = block(BlockType.WARNING, "警告：请勿操作。第二句。", locator, null, true);

        List<StructuredBlock> result = new SemanticBlockNormalizer().normalize(new ParseResult(List.of(warning), List.of())).blocks();

        assertEquals(1, result.size());
        assertEquals(warning.text(), result.get(0).text());
    }

    private StructuredBlock block(BlockType type, String text, SourceLocator locator, BoundingBox box, boolean atomic) {
        return new StructuredBlock(type, text, locator, box, ExtractionConfidence.HIGH,
                new BlockStructure(0, locator.headingPath(), atomic ? "atomic" : null,
                        atomic ? SequenceType.WARNING : SequenceType.NONE, 0, atomic, locator.sectionOrdinal()));
    }

    private SourceLocator locator(SourceFormat format, int pageStart, int pageEnd, int ordinal, String path) {
        return new SourceLocator(format, pageStart, pageEnd, null, 1, 1, path, ordinal);
    }
}
