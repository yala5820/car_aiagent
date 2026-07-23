package com.hirain.aiagent.rag.indexer.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RetrievalEvaluationReportWriterTest {
    @TempDir Path temporaryDirectory;

    @Test
    void shouldNotWriteRawQueryIntoReport() throws Exception {
        String query = "仅用于确认报告脱敏的私有查询";
        RetrievalEvaluationDataset dataset = new RetrievalEvaluationDataset("v1", "scope", List.of(new RetrievalEvaluationDataset.Case("case-1", query, List.of("child-1"))));
        RetrievalMetrics metrics = new RetrievalMetrics(1, 1d, 1d, 1d, 1d, List.of(new RetrievalMetrics.CaseResult("case-1", "a".repeat(64), 1, List.of("child-1"))));
        Path report = temporaryDirectory.resolve("report.json");
        new RetrievalEvaluationReportWriter().write(report, "b".repeat(64), dataset, metrics);
        String output = Files.readString(report);
        assertFalse(output.contains(query));
        assertTrue(output.contains("datasetVersion"));
        assertTrue(output.contains("bundleDataSha256"));
    }
}
