package com.hirain.aiagent.rag.policy;
import org.junit.Test;import static org.junit.Assert.*;
/** 请求级状态的次数、顺序与 Query 去重必须在任何 Tool 副作用前收敛。 */
public class KnowledgeRequestStateTest {
 @Test public void enforcesIterationRequestAndQueryLimits(){KnowledgeRequestState state=new KnowledgeRequestState();assertTrue(state.tryBeginInvocation(0,"空调使用条件").allowed());assertEquals("MULTIPLE_RAG_CALLS_IN_ITERATION",state.tryBeginInvocation(0,"另一查询").reasonCode());assertEquals("DUPLICATE_QUERY",state.tryBeginInvocation(1,"空调使用条件").reasonCode());assertTrue(state.tryBeginInvocation(1,"空调注意事项").allowed());assertEquals("RAG_INVOCATION_LIMIT_REACHED",state.tryBeginInvocation(2,"第三次").reasonCode());}
}
