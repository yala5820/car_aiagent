package com.hirain.aiagent.runtime;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 单个活跃请求 — 不可变快照 + 可变终态标记。
 * <p>
 * tryComplete 使用 CAS 保证终态只从 RUNNING 转换一次，
 * 后续调用返回 false。
 */
public final class ActiveRequest {

    public enum TerminalState {
        RUNNING, COMPLETED, CANCELLED, TIMEOUT, FAILED
    }

    private final String requestId;
    private final String sessionId;
    private final String userId;
    private final String personaId;
    private final String clientMessageId;
    private final long startedAtMs;
    private final AtomicReference<TerminalState> state = new AtomicReference<>(TerminalState.RUNNING);
    private volatile String cancelReason;

    public ActiveRequest(String requestId, String sessionId, String userId,
                         String personaId, String clientMessageId, long startedAtMs) {
        this.requestId = requestId;
        this.sessionId = sessionId;
        this.userId = userId;
        this.personaId = personaId;
        this.clientMessageId = clientMessageId;
        this.startedAtMs = startedAtMs;
    }

    /** 抢占终态：只有 RUNNING→终态返回 true，后续调用返回 false */
    public boolean tryComplete(TerminalState terminalState, String reason) {
        boolean changed = state.compareAndSet(TerminalState.RUNNING, terminalState);
        if (changed && terminalState == TerminalState.CANCELLED) {
            cancelReason = reason;
        }
        return changed;
    }

    public boolean isCancelled() { return state.get() == TerminalState.CANCELLED; }
    public TerminalState state() { return state.get(); }
    public String requestId() { return requestId; }
    public String sessionId() { return sessionId; }
    public String userId() { return userId; }
    public String personaId() { return personaId; }
    public String clientMessageId() { return clientMessageId; }
    public long startedAtMs() { return startedAtMs; }
    public String cancelReason() { return cancelReason; }
}
