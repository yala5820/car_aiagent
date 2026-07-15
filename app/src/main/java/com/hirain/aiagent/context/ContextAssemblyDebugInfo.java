package com.hirain.aiagent.context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 装配诊断信息 — 不含敏感正文。
 */
public final class ContextAssemblyDebugInfo {

    private final List<ContextProviderOutcome> providerOutcomes;
    private final int messageCount;
    private final int toolSpecCount;
    private final String errorSummary;
    private final List<ContextContributionDecision> contributionDecisions;
    private final boolean compressionRecommended;
    private final int targetSessionMemoryTokens;

    public ContextAssemblyDebugInfo(List<ContextProviderOutcome> providerOutcomes,
                                     int messageCount,
                                     int toolSpecCount,
                                     String errorSummary) {
        this(providerOutcomes, messageCount, toolSpecCount, errorSummary,
                List.of(), false, 0);
    }

    public ContextAssemblyDebugInfo(List<ContextProviderOutcome> providerOutcomes,
                                    int messageCount, int toolSpecCount, String errorSummary,
                                    List<ContextContributionDecision> contributionDecisions,
                                    boolean compressionRecommended,
                                    int targetSessionMemoryTokens) {
        this.providerOutcomes = providerOutcomes != null
                ? Collections.unmodifiableList(new ArrayList<>(providerOutcomes))
                : List.of();
        this.messageCount = messageCount;
        this.toolSpecCount = toolSpecCount;
        this.errorSummary = errorSummary;
        this.contributionDecisions = contributionDecisions != null
                ? Collections.unmodifiableList(new ArrayList<>(contributionDecisions)) : List.of();
        this.compressionRecommended = compressionRecommended;
        this.targetSessionMemoryTokens = targetSessionMemoryTokens;
    }

    public List<ContextProviderOutcome> providerOutcomes() { return providerOutcomes; }
    public int messageCount() { return messageCount; }
    public int toolSpecCount() { return toolSpecCount; }
    public String errorSummary() { return errorSummary; }
    public List<ContextContributionDecision> contributionDecisions() { return contributionDecisions; }
    public boolean compressionRecommended() { return compressionRecommended; }
    public int targetSessionMemoryTokens() { return targetSessionMemoryTokens; }
}
