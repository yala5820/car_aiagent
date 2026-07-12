package com.hirain.aiagent.runtime;

import com.hirain.aiagent.context.ContextPrepareResult;
import com.hirain.aiagent.core.AgentResult;

/**
 * AgentRuntime 调用 AgentLoopOrchestrator 的最小抽象。
 * <p>
 * 唯一 SAM 接受 {@link RequestSession} 和 {@link ContextPrepareResult}。
 * Phase 3 迁移桥：Service lambda 内调用旧 {@code textOrchestrator.execute(userInput, context)}。
 * Phase 5 移除该迁移桥。
 */
@FunctionalInterface
public interface AgentExecutor {
    AgentResult execute(RequestSession session, ContextPrepareResult prepareResult);
}
