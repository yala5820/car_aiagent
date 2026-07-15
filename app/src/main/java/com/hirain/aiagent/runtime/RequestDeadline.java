package com.hirain.aiagent.runtime;

/**
 * 单次 TEXT 请求的端到端截止时间。
 * <p>
 * 设计原因：Service、Runtime、Context、AgentLoop 和模型 HTTP 调用必须读取同一个时间边界，
 * 不能各自重新启动独立计时器。该对象只保存绝对时间，不持有线程或定时任务。
 */
public final class RequestDeadline {

    public static final long DEFAULT_TIMEOUT_MS = 30_000L;

    private final long startedAtMs;
    private final long deadlineAtMs;

    public RequestDeadline(long startedAtMs, long timeoutMs) {
        if (timeoutMs <= 0L) {
            throw new IllegalArgumentException("timeoutMs must be positive");
        }
        this.startedAtMs = startedAtMs;
        this.deadlineAtMs = safeAdd(startedAtMs, timeoutMs);
    }

    public static RequestDeadline standard(long startedAtMs) {
        return new RequestDeadline(startedAtMs, DEFAULT_TIMEOUT_MS);
    }

    public long remainingMs(long nowMs) {
        return Math.max(0L, deadlineAtMs - nowMs);
    }

    public boolean isExpired(long nowMs) {
        return nowMs >= deadlineAtMs;
    }

    public long startedAtMs() {
        return startedAtMs;
    }

    public long deadlineAtMs() {
        return deadlineAtMs;
    }

    private static long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }
}
