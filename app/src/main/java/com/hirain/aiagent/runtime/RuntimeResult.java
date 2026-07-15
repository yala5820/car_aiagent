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
    private final String userId;
    private final String personaId;
    private final String clientMessageId;
    private final boolean success;
    private final String output;
    private final String errorType;
    private final String errorDetail;
    private final long timestampMs;
    private final int iterationsUsed;
    private final long durationMs;

    private RuntimeResult(String requestId, String sessionId,
                          String userId, String personaId, String clientMessageId,
                          boolean success, String output, String errorType,
                          String errorDetail, long timestampMs,
                          int iterationsUsed, long durationMs) {
        this.requestId = requestId;
        this.sessionId = sessionId;
        this.userId = userId;
        this.personaId = personaId;
        this.clientMessageId = clientMessageId;
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
                                        String userId, String personaId, String clientMessageId,
                                        String output, long timestampMs,
                                        int iterationsUsed, long durationMs) {
        return new RuntimeResult(requestId, sessionId, userId, personaId, clientMessageId,
                true, output, null, null, timestampMs, iterationsUsed, durationMs);
    }

    public static RuntimeResult failure(String requestId, String sessionId,
                                        String userId, String personaId, String clientMessageId,
                                        String errorType, String errorDetail,
                                        long timestampMs) {
        return new RuntimeResult(requestId, sessionId, userId, personaId, clientMessageId,
                false, null, errorType, errorDetail, timestampMs, 0, 0);
    }

    public static RuntimeResult timeout(String requestId, String sessionId,
                                        String userId, String personaId, String clientMessageId,
                                        long timestampMs) {
        return new RuntimeResult(requestId, sessionId, userId, personaId, clientMessageId,
                false, null, "TIMEOUT", "请求超时", timestampMs, 0, 0);
    }

    public static RuntimeResult cancelled(String requestId, String sessionId,
                                          String userId, String personaId,
                                          String clientMessageId,
                                          String reason, long timestampMs) {
        return new RuntimeResult(requestId, sessionId, userId, personaId, clientMessageId,
                false, null, "CANCELLED",
                reason != null ? reason : "请求已取消", timestampMs, 0, 0);
    }

    public static RuntimeResult busy(String requestId, String sessionId,
                                     String userId, String personaId,
                                     String clientMessageId, long timestampMs) {
        return failure(requestId, sessionId, userId, personaId, clientMessageId,
                "BUSY", "系统正忙，请稍后重试；如需中止当前请求，请先取消当前请求。",
                timestampMs);
    }

    public static RuntimeResult duplicate(String requestId, String sessionId,
                                          String userId, String personaId,
                                          String clientMessageId, long timestampMs) {
        return failure(requestId, sessionId, userId, personaId, clientMessageId,
                "DUPLICATE_REQUEST", "该 requestId 已存在或近期已执行，请勿重复提交。",
                timestampMs);
    }

    public static RuntimeResult fromAgentResult(String requestId, String sessionId,
                                                String userId, String personaId,
                                                String clientMessageId,
                                                AgentResult agentResult, long timestampMs) {
        if (agentResult.isSuccess()) {
            return success(requestId, sessionId, userId, personaId, clientMessageId,
                    agentResult.output(), timestampMs,
                    agentResult.iterationsUsed(), agentResult.durationMs());
        }
        String errorType = agentResult.errorType() != null
                ? agentResult.errorType().name() : "UNKNOWN";
        return failure(requestId, sessionId, userId, personaId, clientMessageId,
                errorType, agentResult.errorDetail(), timestampMs);
    }

    public static RuntimeResult fromException(String requestId, String sessionId,
                                              String userId, String personaId,
                                              String clientMessageId,
                                              Exception exception, long timestampMs) {
        String detail = exception.getMessage() != null ? exception.getMessage() : "未知错误";
        return new RuntimeResult(requestId, sessionId, userId, personaId, clientMessageId,
                false, null, "EXCEPTION", detail, timestampMs, 0, 0);
    }

    // ── 读取器 ──

    public String requestId() { return requestId; }
    public String sessionId() { return sessionId; }
    public String userId() { return userId; }
    public String personaId() { return personaId; }
    public String clientMessageId() { return clientMessageId; }
    public boolean success() { return success; }
    public String output() { return output; }
    public String errorType() { return errorType; }
    public String errorDetail() { return errorDetail; }
    public long timestampMs() { return timestampMs; }
    public int iterationsUsed() { return iterationsUsed; }
    public long durationMs() { return durationMs; }
}
