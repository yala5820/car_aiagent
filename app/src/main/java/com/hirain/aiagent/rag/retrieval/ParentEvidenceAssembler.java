package com.hirain.aiagent.rag.retrieval;

import com.hirain.aiagent.rag.document.SourceLocatorEntityMapper;
import com.hirain.aiagent.rag.model.*;
import com.hirain.aiagent.rag.store.KnowledgeStoreGateway;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;
import com.hirain.aiagent.rag.policy.RetrievalConfidencePolicy;
import java.util.*;
import java.util.stream.Collectors;

/** 将 Child 排名恢复为完整 Parent；Parent 缺失时 fail closed，不返回不完整证据。 */
public final class ParentEvidenceAssembler {
    private final SourceLocatorEntityMapper locator = new SourceLocatorEntityMapper();
    private final RetrievalConfidencePolicy confidencePolicy = new RetrievalConfidencePolicy();

    public List<RetrievalEvidence> assemble(KnowledgeStoreGateway gateway, List<ParentCandidate> candidates) {
        if (gateway == null || candidates == null || candidates.isEmpty()) return List.of();
        List<String> ids = candidates.stream().map(ParentCandidate::parentId).filter(Objects::nonNull).distinct().collect(Collectors.toList());
        Map<String, KnowledgeChunkEntity> parents = new HashMap<>();
        for (KnowledgeChunkEntity parent : gateway.parentChunksByIds(ids)) {
            if (parent != null && "PARENT".equals(parent.chunkLevel)) parents.put(parent.chunkId, parent);
        }
        List<RetrievalEvidence> output = new ArrayList<>();
        for (ParentCandidate candidate : candidates) {
            KnowledgeChunkEntity parent = parents.get(candidate.parentId());
            if (parent == null || parent.content == null || parent.content.isBlank()) continue;
            RetrievalEvidence child = candidate.bestChild();
            String rankingSource = child.rerankScore() != null ? "RERANK" : "RRF";
            ParentRankingDiagnostics diagnostics = new ParentRankingDiagnostics(
                    parent.chunkId, child.chunkId(), candidate.supportingChildIds(), rankingSource,
                    child.rerankScore(), child.rerankRank(), child.fusionScore(), child.fusionRank(),
                    child.denseRank(), child.lexicalRank());
            Double margin = marginToNextParent(candidate, candidates);
            RetrievalConfidence confidence = confidencePolicy.classify(rankingSource, child.rerankScore(), margin);
            output.add(new RetrievalEvidence(
                    "internal-parent-" + parent.chunkId, parent.chunkId, null, parent.content,
                    parent.documentId, parent.documentTitle, parent.documentVersion, parent.chapter, parent.section,
                    parent.headingPath, locator.fromChunk(parent), child.denseDistance(), child.denseRank(),
                    child.lexicalScore(), child.lexicalRank(), child.fusionScore(), child.fusionRank(),
                    child.rerankScore(), child.rerankRank(), child.retrievalSources(),
                    Applicability.EXACT, confidence, diagnostics));
        }
        return List.copyOf(output);
    }

    private static Double marginToNextParent(ParentCandidate candidate, List<ParentCandidate> candidates) {
        Double best = candidate.bestChild().rerankScore();
        if (best == null) return null;
        Double next = null;
        for (ParentCandidate other : candidates) {
            if (other == candidate || other.bestChild().rerankScore() == null) continue;
            double value = other.bestChild().rerankScore();
            if (next == null || value > next) next = value;
        }
        return next == null ? null : best - next;
    }
}
