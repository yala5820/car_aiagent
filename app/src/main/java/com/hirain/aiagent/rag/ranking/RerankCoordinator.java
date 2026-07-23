package com.hirain.aiagent.rag.ranking;
import com.hirain.aiagent.rag.cloud.RerankItem;import java.util.*;
/** 仅在有效 Rerank 返回时改变 RRF 顺序；空/失败由调用方保留原 RRF 顺序。 */
public final class RerankCoordinator { public List<FusionCandidate> apply(List<FusionCandidate> fused,List<RerankItem> reranked){if(reranked==null||reranked.isEmpty())return List.copyOf(fused);Map<Integer,Double> score=new HashMap<>();for(RerankItem item:reranked)score.put(item.index(),item.score());List<FusionCandidate> output=new ArrayList<>(fused);output.sort(Comparator.<FusionCandidate>comparingDouble(value->score.getOrDefault(fused.indexOf(value),Double.NEGATIVE_INFINITY)).reversed().thenComparing(FusionCandidate::chunkId));return List.copyOf(output);} }
