package com.hirain.aiagent.rag.model;

import java.util.List;

/** RAG 内部结果；单独类型确保 normalizedQuery、耗时和检索诊断不会依赖忽略字段泄露。 */
public record RagResult(int schemaVersion, RagStatus status, boolean answerable, String query, String normalizedQuery,
                        RetrievalMode retrievalMode, List<RetrievalEvidence> retrievalEvidence, List<String> degradedReasons,
                        RagFailureReason failureReasonCode, long elapsedMs, String diagnosticsSummary) {
    public RagResult { retrievalEvidence = List.copyOf(retrievalEvidence == null ? List.of() : retrievalEvidence); degradedReasons = List.copyOf(degradedReasons == null ? List.of() : degradedReasons); }
}
