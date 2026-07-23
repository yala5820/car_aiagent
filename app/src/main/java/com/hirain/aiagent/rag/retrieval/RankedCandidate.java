package com.hirain.aiagent.rag.retrieval;

/** 阶段候选只以稳定 chunkId 作为同分 Tie-breaker，避免 ObjectBox long ID 泄露到排序协议。 */
public record RankedCandidate(String chunkId, double score) implements Comparable<RankedCandidate> {
    @Override public int compareTo(RankedCandidate other) { int scoreOrder = Double.compare(other.score, score); return scoreOrder != 0 ? scoreOrder : chunkId.compareTo(other.chunkId); }
}
