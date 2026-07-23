package com.hirain.aiagent.rag.trace;

import java.util.List;

/**
 * RAG 一次检索的脱敏诊断快照。
 * 设计原因：跨层诊断只交换批准的元数据，避免调用方为了观察检索而传递 Query 或 Evidence 正文。
 */
public record RagTraceSnapshot(String queryHash, String bundleVersion, boolean scopeMatched,
                               int candidateCount, List<String> documentIds, List<String> evidenceIds,
                               String retrievalMode, long elapsedMs, long deadlineRemainingMs,
                               String failureCode) {
    public RagTraceSnapshot {
        documentIds = List.copyOf(documentIds == null ? List.of() : documentIds);
        evidenceIds = List.copyOf(evidenceIds == null ? List.of() : evidenceIds);
        queryHash = safe(queryHash); bundleVersion = safe(bundleVersion); retrievalMode = safe(retrievalMode);
        failureCode = safe(failureCode);
    }
    private static String safe(String value) { return value == null ? "" : value; }
}
