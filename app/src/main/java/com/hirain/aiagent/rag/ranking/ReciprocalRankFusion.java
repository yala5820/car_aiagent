package com.hirain.aiagent.rag.ranking;
import java.util.*;
/** 固定 V1 RRF：只融合排名，score=sum(1/(60+rank))。 */
public final class ReciprocalRankFusion {
 private final int k; public ReciprocalRankFusion(int k){this.k=k;}
 public List<FusionCandidate> fuse(List<String> dense,List<String> lexical){Map<String,Mutable> values=new HashMap<>();add(values,dense,"DENSE");add(values,lexical,"LEXICAL");List<FusionCandidate> out=new ArrayList<>();for(var e:values.entrySet()){Mutable v=e.getValue();out.add(new FusionCandidate(e.getKey(),v.score,v.denseRank,v.lexicalRank,v.sources));}out.sort(Comparator.comparingDouble(FusionCandidate::fusionScore).reversed().thenComparing(FusionCandidate::chunkId));return List.copyOf(out);}
 private void add(Map<String,Mutable> values,List<String> items,String source){if(items==null)return;for(int i=0;i<items.size();i++){String id=items.get(i);if(id==null)continue;Mutable value=values.computeIfAbsent(id,key->new Mutable());int rank=i+1;value.score+=1d/(k+rank);value.sources.add(source);if("DENSE".equals(source))value.denseRank=rank;else value.lexicalRank=rank;}}
 private static final class Mutable{double score;Integer denseRank;Integer lexicalRank;List<String> sources=new ArrayList<>();}
}
