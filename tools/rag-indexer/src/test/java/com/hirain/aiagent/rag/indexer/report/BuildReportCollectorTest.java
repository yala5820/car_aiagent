package com.hirain.aiagent.rag.indexer.report;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BuildReportCollectorTest {
    @Test
    void shouldDerivePublishabilityFromHardGatesAndExposeOnlyStatistics() {
        ParserBuildSummary parser = new ParserBuildSummary(Map.of("PDF", 1L), Map.of("PDF", 1L), Map.of("PDF", 0L), Map.of("PDF", "pdfbox"));
        ChunkBuildSummary chunk = new ChunkBuildSummary(1, 1, 0, 0, 1);
        EmbeddingBuildSummary embedding = new EmbeddingBuildSummary(1, 0, 1, 0, 1);
        StoreBuildSummary store = new StoreBuildSummary(1, 1, 1, 1, 1, "a".repeat(64), 1);
        BuildReport report = new BuildReportCollector().collect("run", List.of(), List.of("NOTICE"), parser, chunk, embedding, store, true, true);
        assertTrue(report.publishable());
        assertFalse(new BuildReportCollector().collect("run", List.of("PARSE_FAILED"), List.of(), parser, chunk, embedding, store, true, true).publishable());
    }
}
