package com.hirain.aiagent.rag.retrieval;

import com.hirain.aiagent.rag.model.RetrievalEvidence;
import java.util.*;
import java.util.stream.Collectors;

/** 按 Rerank 后顺序聚合 Child，同一 Parent 只保留最佳 Child 作为排名代表。 */
public final class ParentCandidateAggregator {
    public List<ParentCandidate> aggregate(List<RetrievalEvidence> children) {
        Map<String, List<RetrievalEvidence>> groups = new LinkedHashMap<>();
        if (children != null) for (RetrievalEvidence child : children) {
            if (child == null || child.parentChunkId() == null || child.parentChunkId().isBlank()) continue;
            groups.computeIfAbsent(child.parentChunkId(), ignored -> new ArrayList<>()).add(child);
        }
        List<ParentCandidate> result = new ArrayList<>();
        for (Map.Entry<String, List<RetrievalEvidence>> entry : groups.entrySet()) {
            List<RetrievalEvidence> values = entry.getValue();
            RetrievalEvidence best = values.stream().min(bestComparator()).orElse(null);
            if (best == null) continue;
            result.add(new ParentCandidate(entry.getKey(), best,
                    values.stream().map(RetrievalEvidence::chunkId).filter(Objects::nonNull).distinct().collect(Collectors.toList())));
        }
        result.sort(Comparator.comparingInt((ParentCandidate value) -> value.bestChild().rerankRank() != null
                        ? value.bestChild().rerankRank()
                        : value.bestChild().fusionRank() == null ? Integer.MAX_VALUE : value.bestChild().fusionRank())
                .thenComparing(ParentCandidate::parentId));
        return List.copyOf(result);
    }

    private static Comparator<RetrievalEvidence> bestComparator() {
        return Comparator.<RetrievalEvidence>comparingDouble(value -> value.rerankScore() == null
                        ? Double.NEGATIVE_INFINITY : value.rerankScore()).reversed()
                .thenComparingInt(value -> value.fusionRank() == null ? Integer.MAX_VALUE : value.fusionRank())
                .thenComparing(RetrievalEvidence::chunkId, Comparator.nullsLast(String::compareTo));
    }
}
