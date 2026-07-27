package com.hirain.aiagent.rag.retrieval;

import com.hirain.aiagent.rag.model.RetrievalEvidence;
import java.util.List;

/** Rerank 后的 Child 聚合成一个 Parent 候选。 */
public record ParentCandidate(String parentId, RetrievalEvidence bestChild, List<String> supportingChildIds) {
    public ParentCandidate {
        supportingChildIds = List.copyOf(supportingChildIds == null ? List.of() : supportingChildIds);
    }
}
