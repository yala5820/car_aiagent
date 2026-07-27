package com.hirain.aiagent.rag.indexer.report;

import com.hirain.aiagent.rag.indexer.pipeline.DocumentBuildState;
import com.hirain.aiagent.rag.indexer.util.Sha256;
import java.util.*;

/** 汇总 V2 Parent/Child 分布并保留异常原因，供人工审核 Gate 使用。 */
public final class ChunkQualityAnalyzer {
    public ChunkQualityReport analyze(List<DocumentBuildState> states){long parents=0,children=0,pu=0,ps=0,ph=0,cu=0,cs=0,ch=0,pac=0,overlap=0,atomic=0,parentOverlap=0,paragraphCount=0,semanticMerge=0,belowForce=0,directRange=0,smallUnmerged=0;Map<String,Long> per=new TreeMap<>(),hashes=new TreeMap<>();List<String> diagnostics=new ArrayList<>();List<ChunkQualityFinding> findings=new ArrayList<>();
        Map<String,Long> overHardReasons=new TreeMap<>();for(DocumentBuildState state:states){if(state.chunkResult()==null)continue;for(var p:state.chunkResult().parents()){parents++;paragraphCount+=p.paragraphs().size();if(p.overlapSegment())parentOverlap++;int t=new com.hirain.aiagent.rag.contract.RagTokenEstimator().estimate(p.text()).totalTokens();if(t<150)pu++;if(t>1200)ps++;if(t>2000){ph++;findings.add(new ChunkQualityFinding("PARENT_OVER_HARD_LIMIT",state.corpusDocument().documentId(),p.ordinal(),0,t,p.locator()));}}
            for(var c:state.chunkResult().children()){children++;int t=c.tokenEstimate();if(t>=0){if(t<160)cu++;if(t<30)belowForce++;else if(t<=100)directRange++;if(t>384)cs++;if(t>512){ch++;overHardReasons.merge(c.splitReason(),1L,Long::sum);findings.add(new ChunkQualityFinding("CHILD_OVER_HARD_LIMIT_"+c.splitReason(),state.corpusDocument().documentId(),c.parentOrdinal(),c.ordinal(),t,c.locator()));}}if("PARAGRAPH_SEMANTIC_MERGE".equals(c.splitReason()))semanticMerge++;per.merge(state.corpusDocument().documentId()+":"+c.parentOrdinal(),1L,Long::sum);hashes.merge(Sha256.ofUtf8(c.text()),1L,Long::sum);if("PARENT_AS_CHILD".equals(c.splitReason()))pac++;if(c.overlapTokenCount()>0)overlap++;}
            for(var d:state.chunkResult().diagnostics()){diagnostics.add(d.reasonCode());if("SMALL_PARAGRAPH_UNMERGED".equals(d.reasonCode()))smallUnmerged++;if("PARENT_ATOMIC_UNIT_OVER_HARD_LIMIT".equals(d.reasonCode())||"CHILD_ATOMIC_GROUP_OVER_HARD_LIMIT".equals(d.reasonCode()))atomic++;}}
        hashes.entrySet().removeIf(entry->entry.getValue()<2);findings.sort(Comparator.comparing(ChunkQualityFinding::documentId).thenComparingInt(ChunkQualityFinding::parentOrdinal).thenComparingInt(ChunkQualityFinding::childOrdinal));return new ChunkQualityReport(parents,children,pu,ps,ph,cu,cs,ch,pac,overlap,atomic,Map.copyOf(per),Map.copyOf(hashes),Map.copyOf(overHardReasons),List.copyOf(diagnostics),List.copyOf(findings),parentOverlap,paragraphCount,semanticMerge,belowForce,directRange,smallUnmerged);}
}
