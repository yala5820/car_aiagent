package com.hirain.aiagent.rag.indexer.evaluation;

import java.util.List;

/** 版本化离线检索评测集；原始 Query 仅在本地评测输入中短暂使用，绝不进入公开报告。 */
public record RetrievalEvaluationDataset(String datasetVersion, String knowledgeScopeId, List<Case> cases) {
    public RetrievalEvaluationDataset {
        if (datasetVersion == null || datasetVersion.isBlank() || knowledgeScopeId == null || knowledgeScopeId.isBlank()) {
            throw new IllegalArgumentException("EVALUATION_DATASET_INVALID");
        }
        cases = List.copyOf(cases);
        if (cases.isEmpty()) throw new IllegalArgumentException("EVALUATION_DATASET_INVALID");
    }

    /** 每个 Case 必须显式标注至少一个期望 Child ID，避免把无答案题误用于 Dense Recall。 */
    public record Case(String caseId, String query, List<String> expectedChunkIds) {
        public Case {
            if (caseId == null || caseId.isBlank() || query == null || query.isBlank()) {
                throw new IllegalArgumentException("EVALUATION_DATASET_INVALID");
            }
            expectedChunkIds = List.copyOf(expectedChunkIds);
            if (expectedChunkIds.isEmpty()) throw new IllegalArgumentException("EVALUATION_DATASET_INVALID");
        }
    }
}
