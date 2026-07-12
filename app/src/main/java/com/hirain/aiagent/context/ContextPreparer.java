package com.hirain.aiagent.context;

import com.hirain.aiagent.runtime.RequestSession;

/**
 * Context 请求级准备入口 — 每个 RequestSession 只调用一次。
 * <p>
 * 设计原因：AgentRuntime 通过此窄接口调用 Context prepare，
 * 不需要了解 ContextOrchestrator 内部的 Provider 链或 BudgetManager。
 */
public interface ContextPreparer {
    ContextPrepareResult prepare(RequestSession session,
                                  ContextCancelChecker cancelChecker);
}
