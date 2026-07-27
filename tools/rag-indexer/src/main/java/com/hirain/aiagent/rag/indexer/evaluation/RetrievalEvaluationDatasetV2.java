package com.hirain.aiagent.rag.indexer.evaluation;

import java.util.List;

/** Parent Evidence 评测集；Query 和人工理由只允许存在于本机受控输入。 */
public record RetrievalEvaluationDatasetV2(String datasetVersion, String knowledgeScopeId,
                                           List<Case> cases) {
    public RetrievalEvaluationDatasetV2 {
        if (datasetVersion == null || datasetVersion.isBlank() || knowledgeScopeId == null || knowledgeScopeId.isBlank()) {
            throw new IllegalArgumentException("EVALUATION_V2_DATASET_INVALID");
        }
        cases = List.copyOf(cases == null ? List.of() : cases);
        if (cases.isEmpty()) throw new IllegalArgumentException("EVALUATION_V2_DATASET_INVALID");
    }

    public record Case(String caseId, String query, EvaluationCategory category,
                       EvaluationAnswerability answerability, List<String> answerCriteria,
                       List<EvidenceSetExpectation> acceptableEvidenceSets, List<String> tags,
                       String split) {
        public Case {
            if (caseId == null || caseId.isBlank() || query == null || query.isBlank()
                    || category == null || answerability == null || split == null || split.isBlank()) {
                throw new IllegalArgumentException("EVALUATION_V2_CASE_INVALID");
            }
            answerCriteria = List.copyOf(answerCriteria == null ? List.of() : answerCriteria);
            acceptableEvidenceSets = List.copyOf(acceptableEvidenceSets == null ? List.of() : acceptableEvidenceSets);
            tags = List.copyOf(tags == null ? List.of() : tags);
            if (answerability == EvaluationAnswerability.ANSWERABLE && acceptableEvidenceSets.isEmpty()) {
                throw new IllegalArgumentException("EVALUATION_V2_EXPECTED_EVIDENCE_MISSING");
            }
            if (answerability == EvaluationAnswerability.NO_EVIDENCE && !acceptableEvidenceSets.isEmpty()) {
                throw new IllegalArgumentException("EVALUATION_V2_NO_EVIDENCE_HAS_EXPECTATION");
            }
            if (!("TRAIN".equals(split) || "DEV".equals(split) || "TEST".equals(split))) {
                throw new IllegalArgumentException("EVALUATION_V2_SPLIT_INVALID");
            }
        }
    }
}
