package com.hirain.aiagent.core.policy;
import com.hirain.aiagent.rag.policy.KnowledgeRequirement;
import com.hirain.aiagent.rag.policy.KnowledgeIntentDecision;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import java.util.*;
/**
 * Tool 可见性到执行权限的唯一桥接：授权来源只能是本轮 ContextAssembly 的 ToolSpecifications，
 * 不能退回全局 Registry。任一未授权项导致整批拒绝，避免已执行前半批的部分副作用。
 */
public final class ToolExecutionAuthorizer {
 public ToolAuthorizationDecision authorize(List<ToolExecutionRequest> requests,List<ToolSpecification> visible,KnowledgeIntentDecision knowledge){if(requests==null||requests.isEmpty())return ToolAuthorizationDecision.allow();Set<String> names=new HashSet<>();if(visible!=null)for(ToolSpecification item:visible)names.add(item.name());for(ToolExecutionRequest request:requests)if(request==null||!names.contains(request.name()))return ToolAuthorizationDecision.deny("TOOL_NOT_AUTHORIZED");if(knowledge!=null&&knowledge.requirement()==KnowledgeRequirement.REQUIRED&&(requests.size()!=1||!"searchVehicleKnowledge".equals(requests.get(0).name())))return ToolAuthorizationDecision.deny("KNOWLEDGE_TOOL_BATCH_INVALID");return ToolAuthorizationDecision.allow();}
}
