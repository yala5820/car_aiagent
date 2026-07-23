package com.hirain.aiagent.rag.ranking;
import java.util.List;import org.junit.Test;import static org.junit.Assert.*;
/** RRF 必须只使用 Rank，双路命中优先于单路同阶候选。 */
public class ReciprocalRankFusionTest { @Test public void fusesRanksAndPreservesSources(){List<FusionCandidate> values=new ReciprocalRankFusion(60).fuse(List.of("a","b"),List.of("b","c"));assertEquals("b",values.get(0).chunkId());assertEquals(2,values.get(0).sources().size());assertEquals(Integer.valueOf(2),values.get(0).denseRank());assertEquals(Integer.valueOf(1),values.get(0).lexicalRank());} }
