package com.hirain.aiagent.context;

import java.util.List;

/** Context 包内部的一次预算评估结果。 */
final class ContextAssemblyAttempt {
    private final ContextAssemblyResult candidate;
    private final boolean compressionRecommended;
    private final int targetSessionMemoryTokens;
    private final List<ContextContributionDecision> contributionDecisions;

    ContextAssemblyAttempt(ContextAssemblyResult candidate, boolean compressionRecommended,
                           int targetSessionMemoryTokens,
                           List<ContextContributionDecision> contributionDecisions) {
        this.candidate = candidate;
        this.compressionRecommended = compressionRecommended;
        this.targetSessionMemoryTokens = targetSessionMemoryTokens;
        this.contributionDecisions = contributionDecisions != null
                ? List.copyOf(contributionDecisions) : List.of();
    }

    ContextAssemblyResult candidate() { return candidate; }
    boolean compressionRecommended() { return compressionRecommended; }
    int targetSessionMemoryTokens() { return targetSessionMemoryTokens; }
    List<ContextContributionDecision> contributionDecisions() { return contributionDecisions; }
}
