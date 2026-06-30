package com.hirain.aiagent.core;

import java.util.List;

/**
 * 结构化 Agent 执行结果 — 替代 catch-and-return-string 模式。
 */
public final class AgentResult {

    public enum ErrorType {
        NONE,
        MODEL_CALL_FAILED,
        TOOL_EXECUTION_FAILED,
        MAX_ITERATIONS_REACHED,
        SAFETY_VETO,
        TIMEOUT,
        INVALID_CONFIG,
        INTERRUPTED
    }

    private final boolean success;
    private final String output;
    private final ErrorType errorType;
    private final String errorDetail;
    private final int iterationsUsed;
    private final long durationMs;
    private final List<ToolExecutionRecord> toolHistory;

    private AgentResult(boolean success, String output, ErrorType errorType,
                        String errorDetail, int iterationsUsed, long durationMs,
                        List<ToolExecutionRecord> toolHistory) {
        this.success = success;
        this.output = output;
        this.errorType = errorType;
        this.errorDetail = errorDetail;
        this.iterationsUsed = iterationsUsed;
        this.durationMs = durationMs;
        this.toolHistory = toolHistory;
    }

    // ── 工厂方法 ──

    public static AgentResult success(String output, int iterationsUsed,
                                      long durationMs, List<ToolExecutionRecord> toolHistory) {
        return new AgentResult(true, output, ErrorType.NONE, null,
                iterationsUsed, durationMs, toolHistory);
    }

    public static AgentResult error(ErrorType type, String detail) {
        return new AgentResult(false, null, type, detail, 0, 0, List.of());
    }

    // ── 读取器 ──

    public boolean isSuccess() { return success; }
    public String output() { return output; }
    public ErrorType errorType() { return errorType; }
    public String errorDetail() { return errorDetail; }
    public int iterationsUsed() { return iterationsUsed; }
    public long durationMs() { return durationMs; }
    public List<ToolExecutionRecord> toolHistory() { return toolHistory; }
}
