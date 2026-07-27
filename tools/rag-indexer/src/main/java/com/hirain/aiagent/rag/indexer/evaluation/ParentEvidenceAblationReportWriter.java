package com.hirain.aiagent.rag.indexer.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 写入同一 Bundle/数据集上的 Parent Evidence 消融矩阵，报告不包含正文。 */
public final class ParentEvidenceAblationReportWriter {
    public void write(Path file, String bundleHash, RetrievalEvaluationDatasetV2 dataset,
                      Map<String, ParentEvidenceMetrics> results) throws Exception {
        if (Files.exists(file)) throw new IllegalArgumentException("EVALUATION_V2_REPORT_ALREADY_EXISTS");
        Files.createDirectories(file.toAbsolutePath().getParent());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 2);
        root.put("datasetVersion", dataset.datasetVersion());
        root.put("knowledgeScopeId", dataset.knowledgeScopeId());
        root.put("bundleDataSha256", bundleHash);
        List<Map<String, Object>> modes = new ArrayList<>();
        for (Map.Entry<String, ParentEvidenceMetrics> entry : results.entrySet()) {
            ParentEvidenceMetrics metrics = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("mode", entry.getKey());
            item.put("caseCount", metrics.caseCount());
            item.put("coverageAt1", metrics.coverageAt1());
            item.put("coverageAt2", metrics.coverageAt2());
            item.put("coverageAt3", metrics.coverageAt3());
            item.put("coverageAt4", metrics.coverageAt4());
            item.put("mrr", metrics.meanReciprocalRank());
            item.put("noEvidenceAccuracy", metrics.noEvidenceAccuracy());
            item.put("exactDuplicateDropCount", metrics.cases().stream().mapToInt(ParentEvidenceMetrics.CaseResult::exactDuplicateDropCount).sum());
            item.put("nearDuplicateDropCount", metrics.cases().stream().mapToInt(ParentEvidenceMetrics.CaseResult::nearDuplicateDropCount).sum());
            item.put("parentOccupancyDropCount", metrics.cases().stream().mapToInt(ParentEvidenceMetrics.CaseResult::parentOccupancyDropCount).sum());
            Map<String, String> splitByCase = dataset.cases().stream().collect(Collectors.toMap(
                    RetrievalEvaluationDatasetV2.Case::caseId, RetrievalEvaluationDatasetV2.Case::split));
            Map<String, Object> splitMetrics = new LinkedHashMap<>();
            for (String split : List.of("DEV", "TEST")) {
                List<ParentEvidenceMetrics.CaseResult> cases = metrics.cases().stream()
                        .filter(value -> split.equals(splitByCase.get(value.caseId()))).toList();
                splitMetrics.put(split, metrics(cases));
            }
            item.put("splitMetrics", splitMetrics);
            modes.add(item);
        }
        root.put("modes", modes);
        new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValue(file.toFile(), root);
    }

    private static Map<String, Object> metrics(List<ParentEvidenceMetrics.CaseResult> cases) {
        int count = cases.size();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("caseCount", count);
        for (int rank = 1; rank <= 4; rank++) {
            final int currentRank = rank;
            int covered = (int) cases.stream().filter(value -> value.answerability() == EvaluationAnswerability.ANSWERABLE
                    && value.firstCoveredRank() > 0 && value.firstCoveredRank() <= currentRank).count();
            output.put("coverageAt" + rank, count == 0 ? 0D : (double) covered / count);
        }
        double mrr = cases.stream().mapToDouble(value -> value.firstCoveredRank() > 0 ? 1D / value.firstCoveredRank() : 0D).sum();
        output.put("mrr", count == 0 ? 0D : mrr / count);
        return output;
    }
}
