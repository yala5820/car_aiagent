package com.hirain.aiagent.rag.policy;

import com.hirain.aiagent.rag.contract.RagTokenEstimator;
import com.hirain.aiagent.rag.model.RetrievalEvidence;

import java.util.ArrayList;
import java.util.List;

/**
 * Evidence 预算统一使用共享 RAG TokenEstimator V2，避免 Android 与离线 Chunk 对同一文本产生不同预算。
 */
public final class EvidenceBudgetPolicy {
    private static final RagTokenEstimator TOKEN_ESTIMATOR = new RagTokenEstimator();
    private final int maxTokens;

    public EvidenceBudgetPolicy(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public List<RetrievalEvidence> select(List<RetrievalEvidence> values) {
        List<RetrievalEvidence> output = new ArrayList<>();
        int used = 0;
        for (RetrievalEvidence value : values) {
            int tokens = estimate(value.content());
            if (used + tokens > maxTokens) continue;
            output.add(value);
            used += tokens;
        }
        return List.copyOf(output);
    }

    public static int estimate(String text) {
        return TOKEN_ESTIMATOR.estimate(text).totalTokens();
    }
}
