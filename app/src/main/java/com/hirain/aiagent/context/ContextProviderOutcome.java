package com.hirain.aiagent.context;

/**
 * 单个 Provider 的执行结果 — 用于装配诊断和 Trace 观测。
 */
public final class ContextProviderOutcome {

    private final String providerName;
    private final ContextProviderStatus status;
    private final ContextErrorCode errorCode;
    private final String errorDetail;
    private final long durationMs;

    public ContextProviderOutcome(String providerName,
                                   ContextProviderStatus status,
                                   ContextErrorCode errorCode,
                                   String errorDetail,
                                   long durationMs) {
        this.providerName = providerName;
        this.status = status;
        this.errorCode = errorCode;
        this.errorDetail = errorDetail;
        this.durationMs = durationMs;
    }

    public String providerName() { return providerName; }
    public ContextProviderStatus status() { return status; }
    public ContextErrorCode errorCode() { return errorCode; }
    public String errorDetail() { return errorDetail; }
    public long durationMs() { return durationMs; }
}
