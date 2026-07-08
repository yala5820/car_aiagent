package com.hirain.aiagent.runtime;

import com.hirain.aiagent.context.ContextFrame;
import com.hirain.aiagent.core.AgentResult;

import java.util.Map;

/**
 * AgentRuntime 调用 AgentLoopOrchestrator 的最小抽象。
 * 设计原因：runtime 单元测试不应依赖真实 Android Context、SQLite ChatMemory 或真实 LLM。
 */
@FunctionalInterface
public interface AgentExecutor {
    AgentResult execute(String userInput, Map<String, Object> context);

    /**
     * 兼容默认方法：将 ContextFrame 合并到 orchestratorContext 后委托到旧接口。
     */
    default AgentResult execute(RequestSession session, ContextFrame contextFrame) {
        return execute(session.userInput(),
                contextFrame.toOrchestratorContext(session.orchestratorContext()));
    }
}
