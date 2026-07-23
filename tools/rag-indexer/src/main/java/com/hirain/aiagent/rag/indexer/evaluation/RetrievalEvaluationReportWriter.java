package com.hirain.aiagent.rag.indexer.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.hirain.aiagent.rag.indexer.util.DeterministicJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 写入可审计但不含 Query 正文的 JSON 报告；显式拒绝覆盖既有报告。 */
public final class RetrievalEvaluationReportWriter {
    public void write(Path file, String bundleHash, RetrievalEvaluationDataset dataset, RetrievalMetrics metrics) throws Exception {
        write(file, bundleHash, dataset, metrics, "DENSE_V1");
    }

    /** 显式写出评测模式，防止把 Dense 基线和 Android 对齐的 Hybrid 指标混为一谈。 */
    public void write(Path file, String bundleHash, RetrievalEvaluationDataset dataset, RetrievalMetrics metrics,
                      String retrievalMode) throws Exception {
        if (Files.exists(file)) throw new IllegalArgumentException("EVALUATION_REPORT_ALREADY_EXISTS");
        if (file.getParent() == null) throw new IllegalArgumentException("EVALUATION_REPORT_PATH_INVALID");
        Files.createDirectories(file.getParent());
        List<Map<String, Object>> cases = metrics.cases().stream().map(value -> Map.<String, Object>of("caseId", value.caseId(), "querySha256", value.querySha256(), "firstRelevantRank", value.firstRelevantRank(), "rankedChunkIds", value.rankedChunkIds())).toList();
        Map<String, Object> root = new LinkedHashMap<>(); root.put("schemaVersion", 1); root.put("datasetVersion", dataset.datasetVersion()); root.put("knowledgeScopeId", dataset.knowledgeScopeId()); root.put("bundleDataSha256", bundleHash); root.put("retrievalMode", retrievalMode);
        root.put("metrics", Map.of("caseCount", metrics.caseCount(), "recallAt1", metrics.recallAt1(), "recallAt3", metrics.recallAt3(), "recallAt5", metrics.recallAt5(), "mrr", metrics.meanReciprocalRank())); root.put("cases", cases);
        new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValue(file.toFile(), root);
    }
}
