package com.hirain.aiagent.core.safety;

import com.hirain.aiagent.core.AgentLoopContext;
import com.hirain.aiagent.core.SafetyVerdict;
import com.hirain.aiagent.core.component.SafetyGuard;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

/** 始终放行所有工具调用（用于 chat、vision_qa 等无需安全审查的人格）。 */
public class AllowAllSafetyGuard implements SafetyGuard {
    @Override
    public SafetyVerdict evaluate(ToolExecutionRequest request, AgentLoopContext ctx) {
        return SafetyVerdict.allow();
    }
}
