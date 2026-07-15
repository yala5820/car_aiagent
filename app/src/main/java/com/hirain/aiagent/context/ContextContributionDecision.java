package com.hirain.aiagent.context;

/** 单个 sourceKey 在一次装配尝试中的最终去向。 */
public final class ContextContributionDecision {
    private final String sourceKey;
    private final boolean produced;
    private final boolean included;
    private final boolean trimmed;
    private final String trimReason;
    private final int attemptIndex;

    public ContextContributionDecision(String sourceKey, boolean produced, boolean included,
                                       boolean trimmed, String trimReason, int attemptIndex) {
        this.sourceKey = sourceKey;
        this.produced = produced;
        this.included = included;
        this.trimmed = trimmed;
        this.trimReason = trimReason;
        this.attemptIndex = attemptIndex;
    }

    public String sourceKey() { return sourceKey; }
    public boolean produced() { return produced; }
    public boolean included() { return included; }
    public boolean trimmed() { return trimmed; }
    public String trimReason() { return trimReason; }
    public int attemptIndex() { return attemptIndex; }
}
