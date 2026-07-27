package com.hirain.aiagent.rag.indexer.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 写入脱敏 Eval V2 报告：只保存 Case ID、Query Hash、Parent ID 和排名诊断。 */
public final class ParentEvidenceReportWriter {
    public void write(Path file, String bundleHash, RetrievalEvaluationDatasetV2 dataset,
                      ParentEvidenceMetrics metrics, String retrievalMode) throws Exception {
        if (Files.exists(file)) throw new IllegalArgumentException("EVALUATION_V2_REPORT_ALREADY_EXISTS");
        if (file.getParent() == null) throw new IllegalArgumentException("EVALUATION_V2_REPORT_PATH_INVALID");
        Files.createDirectories(file.getParent());
        List<Map<String, Object>> cases = metrics.cases().stream().map(value -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("caseId", value.caseId());
            item.put("querySha256", value.querySha256());
            item.put("answerability", value.answerability().name());
            item.put("evidenceSetCovered", value.evidenceSetCovered());
            item.put("firstCoveredRank", value.firstCoveredRank());
            item.put("rankedParentIds", value.rankedParentIds());
            item.put("candidateParentIds", value.candidateParentIds());
            item.put("rankedChildCount", value.rankedChildCount());
            item.put("parentCount", value.parentCount());
            item.put("evidenceTokenCount", value.evidenceTokenCount());
            item.put("exactDuplicateDropCount", value.exactDuplicateDropCount());
            item.put("nearDuplicateDropCount", value.nearDuplicateDropCount());
            item.put("parentOccupancyDropCount", value.parentOccupancyDropCount());
            item.put("matchedRequiredParentIds", value.matchedRequiredParentIds());
            item.put("bestChildRerankScore", value.bestChildRerankScore());
            item.put("marginToNextParent", value.marginToNextParent());
            item.put("rankingSource", value.rankingSource());
            return item;
        }).toList();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 2);
        root.put("datasetVersion", dataset.datasetVersion());
        root.put("knowledgeScopeId", dataset.knowledgeScopeId());
        root.put("bundleDataSha256", bundleHash);
        root.put("retrievalMode", retrievalMode);
        int exactDrops = metrics.cases().stream().mapToInt(ParentEvidenceMetrics.CaseResult::exactDuplicateDropCount).sum();
        int nearDrops = metrics.cases().stream().mapToInt(ParentEvidenceMetrics.CaseResult::nearDuplicateDropCount).sum();
        int occupancyDrops = metrics.cases().stream().mapToInt(ParentEvidenceMetrics.CaseResult::parentOccupancyDropCount).sum();
        int evidenceTokens = metrics.cases().stream().mapToInt(ParentEvidenceMetrics.CaseResult::evidenceTokenCount).sum();
        int maxEvidenceTokens = metrics.cases().stream().mapToInt(ParentEvidenceMetrics.CaseResult::evidenceTokenCount).max().orElse(0);
        Map<String, Object> metricValues = new LinkedHashMap<>();
        metricValues.put("caseCount", metrics.caseCount());
        metricValues.put("parentEvidenceCoverageAt1", metrics.coverageAt1());
        metricValues.put("parentEvidenceCoverageAt2", metrics.coverageAt2());
        metricValues.put("parentEvidenceCoverageAt3", metrics.coverageAt3());
        metricValues.put("parentEvidenceCoverageAt4", metrics.coverageAt4());
        metricValues.put("parentEvidenceMRR", metrics.meanReciprocalRank());
        metricValues.put("noEvidenceAccuracy", metrics.noEvidenceAccuracy());
        metricValues.put("exactDuplicateDropCount", exactDrops);
        metricValues.put("nearDuplicateDropCount", nearDrops);
        metricValues.put("parentOccupancyDropCount", occupancyDrops);
        metricValues.put("evidenceTokenTotal", evidenceTokens);
        metricValues.put("evidenceTokenMax", maxEvidenceTokens);
        metricValues.put("parentEvidenceBudget", 5000);
        metricValues.put("maxParentEvidenceCount", 4);
        metricValues.put("noEvidencePolicy", "OFFLINE_SHARED_PHRASE_V1_TEST_ONLY");
        root.put("metrics", metricValues);
        root.put("cases", cases);
        new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValue(file.toFile(), root);
    }
}
