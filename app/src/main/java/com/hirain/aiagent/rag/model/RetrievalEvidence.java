package com.hirain.aiagent.rag.model;

import java.util.List;

/** 内部检索证据，保留 Trace/评测必需的排名与分数，绝不可直接序列化给模型。 */
public record RetrievalEvidence(String retrievalEvidenceId, String chunkId, String parentChunkId, String content,
                                String documentId, String documentTitle, String documentVersion, String chapter, String section,
                                SourceLocator sourceLocator, Double denseDistance, Integer denseRank, Double lexicalScore,
                                Integer lexicalRank, Double fusionScore, Integer fusionRank, Double rerankScore, Integer rerankRank,
                                List<String> retrievalSources, Applicability applicability) {
    public RetrievalEvidence { retrievalSources = List.copyOf(retrievalSources == null ? List.of() : retrievalSources); }
}
