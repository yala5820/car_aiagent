package com.hirain.aiagent.context;

import com.hirain.aiagent.intentrouter.IntentResult;
import com.hirain.aiagent.runtime.RequestSession;
import com.hirain.aiagent.toolgroup.ToolGroupId;
import com.hirain.aiagent.toolgroup.ToolGroupSelectionResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
    private final List<ContextContribution> contributions;

    // Used only by ContextFrameBuilder
    ContextFrame(String requestId, String clientMessageId,
                 String userId, String sessionId, String personaId,
                 String inputType, String rawUserInput, String normalizedUserInput,
                 IntentResult intentResult,
                 ToolGroupSelectionResult toolGroupSelectionResult,
                 List<ToolGroupId> selectedGroupIds,
                 List<String> selectedToolNames,
                 String effectivePersonaId,
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

    public String effectivePersonaId() { return effectivePersonaId; }
    public List<ContextContribution> contributions() { return contributions; }
}
