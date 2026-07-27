package com.hirain.aiagent.rag.ranking;

/** 记录 RRF 后被移除的 Child，正文不会写入 Trace 或对外结果。 */
public record CandidateDeduplicationRecord(String removedChunkId, String keptChunkId,
                                            String parentId, String reason, double similarity) {
}
