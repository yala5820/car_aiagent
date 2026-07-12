package com.hirain.aiagent.context;

import com.hirain.aiagent.runtime.RequestSession;

import dev.langchain4j.data.message.ChatMessage;

/**
 * Context 装配请求 — 每次 AgentLoop 迭代调用一次。
 * <p>
 * 包含 {@link ContextFrame}（静态 + 动态 Contributions，含 SESSION_MEMORY MessageContribution）
 * 和 {@link RequestSession}（Provider 数据来源）。
 * 不传 Android Context、服务实例或可变对象。
 */
public final class ContextAssemblyRequest {

    private final ContextFrame frame;
    private final int iteration;
    private final ContextBudgetPolicy budgetPolicy;
    private final ContextCancelChecker cancelChecker;
    private final boolean compressionAlreadyAttempted;
    private final RequestSession session;

    public ContextAssemblyRequest(ContextFrame frame,
                                   int iteration,
                                   ContextBudgetPolicy budgetPolicy,
                                   ContextCancelChecker cancelChecker,
                                   boolean compressionAlreadyAttempted) {
        this(frame, iteration, budgetPolicy, cancelChecker,
                compressionAlreadyAttempted, null);
    }

    public ContextAssemblyRequest(ContextFrame frame,
                                   int iteration,
                                   ContextBudgetPolicy budgetPolicy,
                                   ContextCancelChecker cancelChecker,
                                   boolean compressionAlreadyAttempted,
                                   RequestSession session) {
        this.frame = frame;
        this.iteration = iteration;
        this.budgetPolicy = budgetPolicy;
        this.cancelChecker = cancelChecker;
        this.compressionAlreadyAttempted = compressionAlreadyAttempted;
        this.session = session;
    }

    public ContextFrame frame() { return frame; }
    public int iteration() { return iteration; }
    public ContextBudgetPolicy budgetPolicy() { return budgetPolicy; }
    public ContextCancelChecker cancelChecker() { return cancelChecker; }
    public boolean compressionAlreadyAttempted() { return compressionAlreadyAttempted; }
    public RequestSession session() { return session; }
}
