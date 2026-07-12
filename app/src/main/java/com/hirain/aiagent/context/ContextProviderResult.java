package com.hirain.aiagent.context;

import java.util.Collections;
import java.util.List;

/**
 * ContextProvider 执行结果 — 区分 success / fallback / failure。
 * <p>
 * 包含不可变 contributions 列表、单个 ContextProviderOutcome、status/errorCode/detail。
 */
public final class ContextProviderResult {

    private final boolean success;
    private final boolean fallback;
    private final ContextProviderStatus status;
    private final String providerName;
    private final String errorReason;
    private final ContextErrorCode errorCode;
    private final List<ContextContribution> contributions;
    private final ContextProviderOutcome outcome;

    private ContextProviderResult(boolean success, boolean fallback,
                                  ContextProviderStatus status,
                                  String providerName,
                                  String errorReason, ContextErrorCode errorCode,
                                  List<ContextContribution> contributions,
                                  ContextProviderOutcome outcome) {
        this.success = success;
        this.fallback = fallback;
        this.status = status;
        this.providerName = providerName;
        this.errorReason = errorReason;
        this.errorCode = errorCode;
        this.contributions = contributions != null
                ? Collections.unmodifiableList(contributions)
                : List.of();
        this.outcome = outcome;
    }

    /** provider 正常执行完毕。 */
    public static ContextProviderResult success(String providerName,
                                                 List<ContextContribution> contributions) {
        ContextProviderOutcome o = new ContextProviderOutcome(
                providerName, ContextProviderStatus.SUCCESS, null, null, 0L);
        return new ContextProviderResult(true, false, ContextProviderStatus.SUCCESS,
                providerName, null, null, contributions, o);
    }

    /** provider 降级，但仍可用。 */
    public static ContextProviderResult fallback(String providerName,
                                                  String reason,
                                                  List<ContextContribution> contributions) {
        ContextProviderOutcome o = new ContextProviderOutcome(
                providerName, ContextProviderStatus.FALLBACK, null, reason, 0L);
        return new ContextProviderResult(true, true, ContextProviderStatus.FALLBACK,
                providerName, reason, null, contributions, o);
    }

    /** provider 彻底失败，无可用 section。 */
    public static ContextProviderResult failure(String providerName,
                                                 String reason,
                                                 ContextErrorCode errorCode) {
        ContextProviderOutcome o = new ContextProviderOutcome(
                providerName, ContextProviderStatus.FAILED, errorCode, reason, 0L);
        return new ContextProviderResult(false, false, ContextProviderStatus.FAILED,
                providerName, reason, errorCode, List.of(), o);
    }

    // ── 读取器 ──

    public boolean success() { return success; }
    public boolean fallback() { return fallback; }
    public ContextProviderStatus status() { return status; }
    public String providerName() { return providerName; }
    public String errorReason() { return errorReason; }
    public ContextErrorCode errorCode() { return errorCode; }
    public List<ContextContribution> contributions() { return contributions; }
    public ContextProviderOutcome outcome() { return outcome; }
}
