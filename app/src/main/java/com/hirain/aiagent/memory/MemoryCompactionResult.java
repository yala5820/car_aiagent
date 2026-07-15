package com.hirain.aiagent.memory;

/**
 * Memory 压缩执行结果 — 由 {@code executeCompactionPlan()} 返回。
 */
public final class MemoryCompactionResult {

    private final boolean executed;
    private final boolean success;
    private final long tokensBefore;
    private final long tokensAfter;
    private final boolean reloadRequired;
    private final String errorDetail;
    private final boolean snapshotMatch;

    public MemoryCompactionResult(boolean executed, boolean success,
                                   long tokensBefore, long tokensAfter,
                                   boolean reloadRequired, String errorDetail) {
        this(executed, success, tokensBefore, tokensAfter, reloadRequired,
                errorDetail, true);
    }

    public MemoryCompactionResult(boolean executed, boolean success,
                                  long tokensBefore, long tokensAfter,
                                  boolean reloadRequired, String errorDetail,
                                  boolean snapshotMatch) {
        this.executed = executed;
        this.success = success;
        this.tokensBefore = tokensBefore;
        this.tokensAfter = tokensAfter;
        this.reloadRequired = reloadRequired;
        this.errorDetail = errorDetail;
        this.snapshotMatch = snapshotMatch;
    }

    public boolean executed() { return executed; }
    public boolean success() { return success; }
    public long tokensBefore() { return tokensBefore; }
    public long tokensAfter() { return tokensAfter; }
    public boolean reloadRequired() { return reloadRequired; }
    public String errorDetail() { return errorDetail; }
    public boolean snapshotMatch() { return snapshotMatch; }

    public static MemoryCompactionResult notExecuted() {
        return new MemoryCompactionResult(false, false, 0, 0, false, null);
    }
}
