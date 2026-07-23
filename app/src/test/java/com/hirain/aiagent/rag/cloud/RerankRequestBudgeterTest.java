package com.hirain.aiagent.rag.cloud;
import java.util.List;import org.junit.Test;import static org.junit.Assert.*;
/** Rerank 文本预算独立于最终 Evidence 预算。 */
public class RerankRequestBudgeterTest {
 @Test public void truncatesCandidatesWithinIndependentBudget(){List<RerankCandidate> values=new RerankRequestBudgeter(6).apply(List.of(new RerankCandidate("H","123456"),new RerankCandidate("X","x")));assertEquals(1,values.size());assertEquals("12345",values.get(0).text());}
}
