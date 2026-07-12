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

    public ContextAssemblyDebugInfo(List<ContextProviderOutcome> providerOutcomes,
                                     int messageCount,
                                     int toolSpecCount,
                                     String errorSummary) {
        this.providerOutcomes = providerOutcomes != null
                ? Collections.unmodifiableList(new ArrayList<>(providerOutcomes))
                : List.of();
        this.messageCount = messageCount;
        this.toolSpecCount = toolSpecCount;
        this.errorSummary = errorSummary;
    }

    public List<ContextProviderOutcome> providerOutcomes() { return providerOutcomes; }
    public int messageCount() { return messageCount; }
    public int toolSpecCount() { return toolSpecCount; }
    public String errorSummary() { return errorSummary; }
}
