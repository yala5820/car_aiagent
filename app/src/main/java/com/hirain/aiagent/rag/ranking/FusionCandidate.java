package com.hirain.aiagent.rag.ranking;
import java.util.List;
/** RRF 输出保留各路 Rank 与来源，不把 Dense/BM25 的不同量纲伪合并为原始分数。 */
public record FusionCandidate(String chunkId, double fusionScore, Integer denseRank, Integer lexicalRank, List<String> sources) { public FusionCandidate { sources=List.copyOf(sources); } }
