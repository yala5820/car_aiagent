package com.hirain.aiagent.runtime;
import com.hirain.aiagent.AgentRequest;import com.hirain.aiagent.rag.policy.KnowledgeRequirement;import org.junit.Test;import static org.junit.Assert.*;
/** Runtime 必须在 Session 创建时产生一次性知识状态，并从 Service 起点继承 60 秒绝对期限。 */
public class AgentRuntimeKnowledgeRoutingTest {
 @Test public void officialKnowledgeRequestGetsRequiredDecisionAndSixtySecondDeadline(){AgentRuntime runtime=new AgentRuntime((session,prepared)->null,()->"request",()->9_000L);AgentRequest request=new AgentRequest();request.setInputType("TEXT");request.setText("请查用户手册中的空调使用条件");RequestDeadline admission=new RequestDeadline(1_000L,30_000L);RequestSession session=runtime.startSession(request,null,admission);assertEquals(KnowledgeRequirement.REQUIRED,session.knowledgeIntentDecision().requirement());assertEquals(61_000L,session.deadline().deadlineAtMs());assertNotNull(session.knowledgeRequestState());}
}
