package com.hirain.aiagent.rag.policy;

import com.hirain.aiagent.rag.contract.RagTokenEstimator;
import com.hirain.aiagent.rag.model.RetrievalEvidence;
import java.util.*;

/** 只按完整 Parent 选择 Evidence；超预算时跳过后续 Parent，不截断已选 Parent。 */
public final class ParentEvidenceBudgetPolicy {
    private final int maxParents;
    private final int maxTokens;
    private final RagTokenEstimator estimator = new RagTokenEstimator();

    public ParentEvidenceBudgetPolicy(int maxParents, int maxTokens) {
        this.maxParents = maxParents;
        this.maxTokens = maxTokens;
    }

    public List<RetrievalEvidence> select(List<RetrievalEvidence> parents) {
        List<RetrievalEvidence> output = new ArrayList<>();
        int used = 0;
        for (RetrievalEvidence parent : parents == null ? List.<RetrievalEvidence>of() : parents) {
            if (output.size() >= maxParents) break;
            int tokens = estimator.estimate((parent.sectionPath() == null ? "" : parent.sectionPath() + "\n") + parent.content()).totalTokens();
            // 第一名 Parent 本身超预算时不能跳过后返回较低排名内容，否则会掩盖分块硬上限失效。
            if (tokens > maxTokens) break;
            if (used + tokens > maxTokens) break;
            output.add(parent);
            used += tokens;
        }
        return List.copyOf(output);
    }
}
