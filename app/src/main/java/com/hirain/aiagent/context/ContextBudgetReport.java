package com.hirain.aiagent.context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 预算报告 — 每轮装配输出的预算状态。
 */
public final class ContextBudgetReport {

    private final int estimatedInputTokens;
    private final int maxInputTokens;
    private final boolean withinBudget;
    private final int estimatedBeforeTokens;
    private final List<ContextBudgetDecision.TrimAction> trimActions;
    private final boolean compressionRecommended;

    public ContextBudgetReport(int estimatedInputTokens,
                                int maxInputTokens,
                                boolean withinBudget) {
        this(estimatedInputTokens, maxInputTokens, withinBudget,
                estimatedInputTokens, List.of(), false);
    }

    public ContextBudgetReport(int estimatedInputTokens,
                                int maxInputTokens,
                                boolean withinBudget,
                                int estimatedBeforeTokens,
                                List<ContextBudgetDecision.TrimAction> trimActions,
                                boolean compressionRecommended) {
        this.estimatedInputTokens = estimatedInputTokens;
        this.maxInputTokens = maxInputTokens;
        this.withinBudget = withinBudget;
        this.estimatedBeforeTokens = estimatedBeforeTokens;
        this.trimActions = trimActions != null
                ? Collections.unmodifiableList(new ArrayList<>(trimActions))
                : List.of();
        this.compressionRecommended = compressionRecommended;
    }

    public int estimatedInputTokens() { return estimatedInputTokens; }
    public int maxInputTokens() { return maxInputTokens; }
    public boolean withinBudget() { return withinBudget; }
    public int estimatedBeforeTokens() { return estimatedBeforeTokens; }
    public List<ContextBudgetDecision.TrimAction> trimActions() { return trimActions; }
    public boolean compressionRecommended() { return compressionRecommended; }
}
