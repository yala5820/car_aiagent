package com.hirain.aiagent.rag.ranking;

import java.util.List;

/** RRF 后候选去重结果，保留诊断计数但不暴露正文。 */
public record CandidateDeduplicationResult(List<FusionCandidate> candidates,
                                            List<CandidateDeduplicationRecord> records) {
    public CandidateDeduplicationResult {
        candidates = List.copyOf(candidates == null ? List.of() : candidates);
        records = List.copyOf(records == null ? List.of() : records);
    }
}
