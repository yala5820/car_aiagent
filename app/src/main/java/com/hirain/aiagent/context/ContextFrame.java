package com.hirain.aiagent.context;

import com.hirain.aiagent.intentrouter.IntentResult;
import com.hirain.aiagent.runtime.RequestSession;
import com.hirain.aiagent.toolgroup.ToolGroupId;
import com.hirain.aiagent.toolgroup.ToolGroupSelectionResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 不可变上下文快照 — 由 {@link ContextOrchestrator} 构建，包含所有 provider 生成的上下文信息。
 * <p>
 * 基础字段（requestId/sessionId/userId/personaId/clientMessageId）来源于 {@link RequestSession}，
 * 不在此重新生成。
 */
public final class ContextFrame {

    private final String requestId;
    private final String clientMessageId;
    private final String userId;
    private final String sessionId;
    private final String personaId;
    private final String inputType;
    private final String rawUserInput;
    private final String normalizedUserInput;
    private final IntentResult intentResult;
    private final ToolGroupSelectionResult toolGroupSelectionResult;
    private final List<ToolGroupId> selectedGroupIds;
    private final List<String> selectedToolNames;
    private final String effectivePersonaId;
    private final String memorySummary;
    private final String vehicleStateSnapshot;
    private final String timeContext;
    private final String promptContext;
    private final String renderedExtraContext;
    private final int tokenEstimate;
    private final ContextDebugInfo debugInfo;
    private final List<ContextSection> sections;
    private final List<ContextContribution> contributions;

    // Used only by ContextFrameBuilder
    ContextFrame(String requestId, String clientMessageId,
                 String userId, String sessionId, String personaId,
                 String inputType, String rawUserInput, String normalizedUserInput,
                 IntentResult intentResult,
                 ToolGroupSelectionResult toolGroupSelectionResult,
                 List<ToolGroupId> selectedGroupIds,
                 List<String> selectedToolNames,
                 String effectivePersonaId, String memorySummary,
                 String vehicleStateSnapshot, String timeContext,
                 String promptContext, String renderedExtraContext,
                 int tokenEstimate,
                 ContextDebugInfo debugInfo, List<ContextSection> sections,
                 List<ContextContribution> contributions) {
        this.requestId = requestId;
        this.clientMessageId = clientMessageId;
        this.userId = userId;
        this.sessionId = sessionId;
        this.personaId = personaId;
        this.inputType = inputType;
        this.rawUserInput = rawUserInput;
        this.normalizedUserInput = normalizedUserInput;
        this.intentResult = intentResult;
        this.toolGroupSelectionResult = toolGroupSelectionResult;
        this.selectedGroupIds = selectedGroupIds != null
                ? Collections.unmodifiableList(new ArrayList<>(selectedGroupIds))
                : List.of();
        this.selectedToolNames = selectedToolNames != null
                ? Collections.unmodifiableList(new ArrayList<>(selectedToolNames))
                : List.of();
        this.effectivePersonaId = effectivePersonaId;
        this.memorySummary = memorySummary != null ? memorySummary : "";
        this.vehicleStateSnapshot = vehicleStateSnapshot != null ? vehicleStateSnapshot : "";
        this.timeContext = timeContext != null ? timeContext : "";
        this.promptContext = promptContext != null ? promptContext : "";
        this.renderedExtraContext = renderedExtraContext != null ? renderedExtraContext : "";
        this.tokenEstimate = tokenEstimate;
        this.debugInfo = debugInfo;
        this.sections = sections != null
                ? Collections.unmodifiableList(new ArrayList<>(sections))
                : List.of();
        this.contributions = contributions != null
                ? Collections.unmodifiableList(new ArrayList<>(contributions))
                : List.of();
    }

    // ── 基础读取器 ──

    public String requestId() { return requestId; }
    public String clientMessageId() { return clientMessageId; }
    public String userId() { return userId; }
    public String sessionId() { return sessionId; }
    public String personaId() { return personaId; }
    public String inputType() { return inputType; }
    public String rawUserInput() { return rawUserInput; }
    public String normalizedUserInput() { return normalizedUserInput; }

    // ── Intent/ToolGroup ──

    public IntentResult intentResult() { return intentResult; }
    public ToolGroupSelectionResult toolGroupSelectionResult() { return toolGroupSelectionResult; }
    public List<ToolGroupId> selectedGroupIds() { return selectedGroupIds; }
    public List<String> selectedToolNames() { return selectedToolNames; }

    // ── Provider 上下文 ──

    public String effectivePersonaId() { return effectivePersonaId; }
    public String memorySummary() { return memorySummary; }
    public String vehicleStateSnapshot() { return vehicleStateSnapshot; }
    public String timeContext() { return timeContext; }
    public String promptContext() { return promptContext; }

    // ── 渲染结果 / 模式 / 预算 / 调试 ──

    public String renderedExtraContext() { return renderedExtraContext; }
    public int tokenEstimate() { return tokenEstimate; }
    public ContextDebugInfo debugInfo() { return debugInfo; }
    public List<ContextSection> sections() { return sections; }
    public List<ContextContribution> contributions() { return contributions; }
}
