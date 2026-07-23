package com.hirain.aiagent.rag.indexer.report;

import com.hirain.aiagent.rag.indexer.corpus.CorpusDocumentDefinition;
import com.hirain.aiagent.rag.indexer.model.BoundingBox;
import com.hirain.aiagent.rag.indexer.model.BlockType;
import com.hirain.aiagent.rag.indexer.model.ExtractionConfidence;
import com.hirain.aiagent.rag.indexer.model.ParseResult;
import com.hirain.aiagent.rag.indexer.model.SourceDocument;
import com.hirain.aiagent.rag.indexer.model.SourceFormat;
import com.hirain.aiagent.rag.indexer.model.SourceLocator;
import com.hirain.aiagent.rag.indexer.model.StructuredBlock;
import com.hirain.aiagent.rag.indexer.pipeline.DocumentBuildState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 审核报告只能提供定位，不能把原文 Warning 内容复制进 work 报告。 */
final class ParserReviewReportWriterTest {
    @TempDir Path root;

    @Test
    void shouldWriteWarningLocatorWithoutSourceText() throws Exception {
        String sensitiveText = "这是不得进入审核报告的车辆手册正文";
        SourceLocator locator = new SourceLocator(SourceFormat.PDF, 7, 7, null, 0, 0, null, 7);
        StructuredBlock warning = new StructuredBlock(BlockType.WARNING, sensitiveText, locator,
                new BoundingBox(0, 0, 1, 1), ExtractionConfidence.MEDIUM);
        DocumentBuildState state = new DocumentBuildState(
                new CorpusDocumentDefinition("manual", "title", "manual", "1", "zh", "PDF", "manual.pdf", "a".repeat(64), null),
                new SourceDocument(root.resolve("manual.pdf"), SourceFormat.PDF, new com.hirain.aiagent.rag.indexer.model.DocumentMetadata("manual", "title", "zh")),
                new ParseResult(List.of(warning), List.of()), null, null);
        Path report = root.resolve("parser-review.json");

        new ParserReviewReportWriter().write(report, "run-1", "scope-1", List.of(state));

        String json = java.nio.file.Files.readString(report);
        assertTrue(json.contains("\"pdfPageStart\":7"));
        assertTrue(json.contains("\"warningLocators\""));
        assertFalse(json.contains(sensitiveText));
    }
}
