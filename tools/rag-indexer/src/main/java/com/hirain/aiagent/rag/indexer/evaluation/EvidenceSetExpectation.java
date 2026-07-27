package com.hirain.aiagent.rag.indexer.evaluation;

import java.util.List;

/** 一组可接受的完整 Parent Evidence；集合内为 AND，多个集合之间为 OR。 */
public record EvidenceSetExpectation(List<String> requiredParentIds,
                                     List<String> optionalLocatorChildIds,
                                     String rationale) {
    public EvidenceSetExpectation {
        requiredParentIds = List.copyOf(requiredParentIds == null ? List.of() : requiredParentIds);
        optionalLocatorChildIds = List.copyOf(optionalLocatorChildIds == null ? List.of() : optionalLocatorChildIds);
        if (requiredParentIds.isEmpty() || rationale == null || rationale.isBlank()) {
            throw new IllegalArgumentException("EVALUATION_V2_EVIDENCE_SET_INVALID");
        }
    }
}
