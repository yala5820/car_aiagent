package com.hirain.aiagent.rag.indexer.evaluation;

import java.util.List;

/** Eval V2 主指标：最终 Parent Evidence 对可接受证据集合的覆盖能力。 */
public record ParentEvidenceMetrics(int caseCount, double coverageAt1, double coverageAt2,
                                     double coverageAt3, double coverageAt4,
                                     double meanReciprocalRank, double noEvidenceAccuracy,
                                     List<CaseResult> cases) {
    public ParentEvidenceMetrics {
        cases = List.copyOf(cases == null ? List.of() : cases);
    }

    public record CaseResult(String caseId, String querySha256, EvaluationAnswerability answerability,
                             boolean evidenceSetCovered, int firstCoveredRank,
                             List<String> rankedParentIds, List<String> candidateParentIds,
                             int rankedChildCount, int parentCount, int evidenceTokenCount,
                             int exactDuplicateDropCount, int nearDuplicateDropCount,
                             int parentOccupancyDropCount, List<String> matchedRequiredParentIds,
                             Double bestChildRerankScore, Double marginToNextParent, String rankingSource) {
        public CaseResult {
            rankedParentIds = List.copyOf(rankedParentIds == null ? List.of() : rankedParentIds);
            candidateParentIds = List.copyOf(candidateParentIds == null ? List.of() : candidateParentIds);
            matchedRequiredParentIds = List.copyOf(matchedRequiredParentIds == null ? List.of() : matchedRequiredParentIds);
        }
    }
}
