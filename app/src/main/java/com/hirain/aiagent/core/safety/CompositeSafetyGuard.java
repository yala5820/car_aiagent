package com.hirain.aiagent.core.safety;

import com.hirain.aiagent.core.AgentLoopContext;
import com.hirain.aiagent.core.SafetyVerdict;
import com.hirain.aiagent.core.component.SafetyGuard;

import java.util.List;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

/**
 * 组合多个安全审查器 — 按顺序执行，首个否决即停止。
 */
public class CompositeSafetyGuard implements SafetyGuard {

    private final List<SafetyGuard> guards;

    public CompositeSafetyGuard(List<SafetyGuard> guards) {
        this.guards = guards;
    }

    @Override
    public SafetyVerdict evaluate(ToolExecutionRequest request, AgentLoopContext ctx) {
        for (SafetyGuard guard : guards) {
            SafetyVerdict verdict = guard.evaluate(request, ctx);
            if (verdict.isVetoed()) return verdict;
        }
        return SafetyVerdict.allow();
    }
}
