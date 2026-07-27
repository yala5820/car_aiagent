package com.hirain.aiagent.rag.policy;

import com.hirain.aiagent.rag.model.RetrievalConfidence;

/**
 * Parent 级检索置信度策略。阈值来自 2026-07-27 DEV 校准，且只适用于正常 Rerank；
 * RRF fallback 不复用该阈值，避免把不同分数空间混成同一语义。
 */
public final class RetrievalConfidencePolicy {
    public static final String VERSION = "DEV-CALIBRATED-2026-07-27-1";
    private static final double HIGH_SCORE = 0.94D;
    private static final double HIGH_MARGIN = 0.05D;
    private static final double MEDIUM_SCORE = 0.90D;
    private static final double MEDIUM_MARGIN = 0.02D;

    public RetrievalConfidence classify(String rankingSource, Double bestChildRerankScore, Double marginToNextParent) {
        if (!"RERANK".equals(rankingSource) || bestChildRerankScore == null || marginToNextParent == null
                || !Double.isFinite(bestChildRerankScore) || !Double.isFinite(marginToNextParent)) {
            return RetrievalConfidence.UNASSESSED;
        }
        if (bestChildRerankScore >= HIGH_SCORE && marginToNextParent >= HIGH_MARGIN) return RetrievalConfidence.HIGH;
        if (bestChildRerankScore >= MEDIUM_SCORE && marginToNextParent >= MEDIUM_MARGIN) return RetrievalConfidence.MEDIUM;
        return RetrievalConfidence.LOW;
    }
}
