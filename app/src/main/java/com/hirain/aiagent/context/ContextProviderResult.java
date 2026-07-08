package com.hirain.aiagent.context;

/**
 * ContextProvider 执行结果 — 区分 success / fallback / failure。
 * <p>
 * 设计原因：Provider 失败不应阻断整个 Context 构建，ContextOrchestrator 会收集失败并继续。
 */
public final class ContextProviderResult {

    private final boolean success;
    private final boolean fallback;
    private final String providerName;
    private final ContextSection section;
    private final String errorReason;

    private ContextProviderResult(boolean success, boolean fallback,
                                  String providerName, ContextSection section,
                                  String errorReason) {
        this.success = success;
        this.fallback = fallback;
        this.providerName = providerName;
        this.section = section;
        this.errorReason = errorReason;
    }

    /** provider 正常执行完毕（包含 fallback 和正常两种子状态）。 */
    public static ContextProviderResult success(String providerName, ContextSection section) {
        return new ContextProviderResult(true, false, providerName, section, null);
    }

    /** provider 降级（依赖缺失、数据不完整），但仍产生可用 section。 */
    public static ContextProviderResult fallback(String providerName, ContextSection section,
                                                  String reason) {
        return new ContextProviderResult(true, true, providerName, section, reason);
    }

    /** provider 彻底失败，无可用 section。 */
    public static ContextProviderResult failure(String providerName, String reason) {
        return new ContextProviderResult(false, false, providerName, null, reason);
    }

    public boolean success() { return success; }
    public boolean fallback() { return fallback; }
    public String providerName() { return providerName; }
    public ContextSection section() { return section; }
    public String errorReason() { return errorReason; }
}
