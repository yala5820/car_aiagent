package com.hirain.aiagent.context;

/**
 * ContextOrchestrator.build() 的执行结果。
 * <p>
 * 区分 normal success（部分 provider fallback 仍视为成功）和
 * all-providers-failed fallback（frame 的 renderedExtraContext 为空但仍可用）。
 */
public final class ContextBuildResult {

    private final boolean success;
    private final ContextFrame frame;
    private final boolean fallbackUsed;
    private final String errorReason;

    private ContextBuildResult(boolean success, ContextFrame frame,
                               boolean fallbackUsed, String errorReason) {
        this.success = success;
        this.frame = frame;
        this.fallbackUsed = fallbackUsed;
        this.errorReason = errorReason;
    }

    /** 构建成功（允许部分 provider fallback）。 */
    public static ContextBuildResult success(ContextFrame frame, boolean fallbackUsed) {
        return new ContextBuildResult(true, frame, fallbackUsed, null);
    }

    /** 全部 provider 均失败，frame 的 renderedExtraContext 为空。 */
    public static ContextBuildResult fallback(ContextFrame frame, String reason) {
        return new ContextBuildResult(true, frame, true, reason);
    }

    public boolean success() { return success; }
    public ContextFrame frame() { return frame; }
    public boolean fallbackUsed() { return fallbackUsed; }
    public String errorReason() { return errorReason; }
}
