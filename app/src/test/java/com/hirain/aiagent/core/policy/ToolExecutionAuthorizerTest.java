package com.hirain.aiagent.core.policy;
import com.hirain.aiagent.rag.policy.*;import dev.langchain4j.agent.tool.*;import java.util.*;import org.junit.Test;import static org.junit.Assert.*;
/** 隐藏 Tool、混合知识批次都应在 Dispatcher 前失败关闭。 */
public class ToolExecutionAuthorizerTest {
 private final ToolExecutionAuthorizer authorizer=new ToolExecutionAuthorizer();
 @Test public void rejectsHiddenAndMixedKnowledgeTools(){ToolSpecification knowledge=ToolSpecification.builder().name("searchVehicleKnowledge").description("test").build();KnowledgeIntentDecision required=new KnowledgeIntentDecision(KnowledgeRequirement.REQUIRED,"test","v1",false);assertFalse(authorizer.authorize(List.of(ToolExecutionRequest.builder().id("1").name("set_ac_status").arguments("{}").build()),List.of(knowledge),required).authorized());assertFalse(authorizer.authorize(List.of(ToolExecutionRequest.builder().id("1").name("searchVehicleKnowledge").arguments("{}").build(),ToolExecutionRequest.builder().id("2").name("set_ac_status").arguments("{}").build()),List.of(knowledge),required).authorized());assertTrue(authorizer.authorize(List.of(ToolExecutionRequest.builder().id("1").name("searchVehicleKnowledge").arguments("{}").build()),List.of(knowledge),required).authorized());}
}
