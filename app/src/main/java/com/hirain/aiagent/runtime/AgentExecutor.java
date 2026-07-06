package com.hirain.aiagent.runtime;

import com.hirain.aiagent.core.AgentResult;

import java.util.Map;

/**
 * AgentRuntime 调用 AgentLoopOrchestrator 的最小抽象。
 * 设计原因：runtime 单元测试不应依赖真实 Android Context、SQLite ChatMemory 或真实 LLM。
 */
@FunctionalInterface
public interface AgentExecutor {
    AgentResult execute(String userInput, Map<String, Object> context);
}
