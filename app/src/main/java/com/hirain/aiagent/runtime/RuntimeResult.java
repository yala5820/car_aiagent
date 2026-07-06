package com.hirain.aiagent.runtime;

import com.hirain.aiagent.core.AgentResult;

/**
 * Runtime 层统一执行结果 — 封装 AgentLoopOrchestrator 执行结果
 * 或 runtime 内部产生的超时/异常结果。
 * <p>
 * 设计原因：将 AgentResult 与 AgentResponse 完全解耦；
 * Runtime 内部使用 RuntimeResult，最终由 RuntimeResponseMapper 转换为 AgentResponse。
 */
public final class RuntimeResult {

    private final String requestId;
    private final String sessionId;
    private final boolean success;
    private final String output;
    private final String errorType;
    private final String errorDetail;
    private final long timestampMs;
    private final int iterationsUsed;
    private final long durationMs;

    private RuntimeResult(String requestId, String sessionId, boolean success,
                          String output, String errorType, String errorDetail,
                          long timestampMs, int iterationsUsed, long durationMs) {
        this.requestId = requestId;
        this.sessionId = sessionId;
        this.success = success;
        this.output = output;
        this.errorType = errorType;
        this.errorDetail = errorDetail;
        this.timestampMs = timestampMs;
        this.iterationsUsed = iterationsUsed;
        this.durationMs = durationMs;
    }

    // ── 工厂方法 ──

    public static RuntimeResult success(String requestId, String sessionId,
                                        String output, long timestampMs,
                                        int iterationsUsed, long durationMs) {
        return new RuntimeResult(requestId, sessionId, true, output, null, null,
                timestampMs, iterationsUsed, durationMs);
    }

    public static RuntimeResult failure(String requestId, String sessionId,
                                        String errorType, String errorDetail,
                                        long timestampMs) {
        return new RuntimeResult(requestId, sessionId, false, null,
                errorType, errorDetail, timestampMs, 0, 0);
    }

    public static RuntimeResult timeout(String requestId, String sessionId, long timestampMs) {
        return new RuntimeResult(requestId, sessionId, false, null,
                "TIMEOUT", "请求超时", timestampMs, 0, 0);
    }

    public static RuntimeResult fromAgentResult(String requestId, String sessionId,
                                                AgentResult agentResult, long timestampMs) {
        if (agentResult.isSuccess()) {
            return success(requestId, sessionId, agentResult.output(), timestampMs,
                    agentResult.iterationsUsed(), agentResult.durationMs());
        }
        String errorType = agentResult.errorType() != null
                ? agentResult.errorType().name() : "UNKNOWN";
        return failure(requestId, sessionId, errorType, agentResult.errorDetail(), timestampMs);
    }

    public static RuntimeResult fromException(String requestId, String sessionId,
                                              Exception exception, long timestampMs) {
        String detail = exception.getMessage() != null ? exception.getMessage() : "未知错误";
        return new RuntimeResult(requestId, sessionId, false, null,
                "EXCEPTION", detail, timestampMs, 0, 0);
    }

    // ── 读取器 ──

    public String requestId() { return requestId; }
    public String sessionId() { return sessionId; }
    public boolean success() { return success; }
    public String output() { return output; }
    public String errorType() { return errorType; }
    public String errorDetail() { return errorDetail; }
    public long timestampMs() { return timestampMs; }
    public int iterationsUsed() { return iterationsUsed; }
    public long durationMs() { return durationMs; }
}
