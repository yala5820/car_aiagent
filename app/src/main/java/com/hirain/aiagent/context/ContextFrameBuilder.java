package com.hirain.aiagent.context;

import com.hirain.aiagent.intentrouter.IntentResult;
import com.hirain.aiagent.runtime.RequestSession;
import com.hirain.aiagent.toolgroup.ToolGroupId;
import com.hirain.aiagent.toolgroup.ToolGroupSelectionResult;

import java.util.List;

/**
 * {@link ContextFrame} 构造器 — 通过 {@link #fromSession(RequestSession)} 创建。
 * <p>
 * 设计原因：不在此重新生成 ID；所有 requestId/sessionId/userId/personaId/clientMessageId
 * 均来自 {@link RequestSession}，确保与 {@link com.hirain.aiagent.runtime.AgentRuntime} 一致。
 */
public final class ContextFrameBuilder {

    // ── 基础字段（fromSession 自动填充） ──
    private String requestId;
    private String clientMessageId;
    private String userId;
    private String sessionId;
    private String personaId;
    private String inputType;
    private String rawUserInput;
    private String normalizedUserInput;
    private IntentResult intentResult;
    private ToolGroupSelectionResult toolGroupSelectionResult;
    private List<ToolGroupId> selectedGroupIds;
    private List<String> selectedToolNames;

    // ── 可选字段（通过 setter 覆盖） ──
    private String effectivePersonaId;
    private String memorySummary;
    private String vehicleStateSnapshot;
    private String timeContext;
    private String promptContext;
    private String renderedExtraContext;
    private int tokenEstimate;
    private ContextDebugInfo debugInfo;
    private List<ContextSection> sections;
    private List<ContextContribution> contributions;

    ContextFrameBuilder() {
    }

    /**
     * 从 {@link RequestSession} 创建 Builder，自动填充所有基础字段。
     */
    public static ContextFrameBuilder fromSession(RequestSession session) {
        ContextFrameBuilder builder = new ContextFrameBuilder();
        builder.requestId = session.requestId();
        builder.clientMessageId = session.clientMessageId();
        builder.userId = session.userId();
        builder.sessionId = session.sessionId();
        builder.personaId = session.personaId();
        builder.inputType = session.inputType();
        builder.rawUserInput = session.userInput();
        builder.normalizedUserInput = session.userInput() != null
                ? session.userInput().trim() : "";
        builder.intentResult = session.intentResult();
        builder.toolGroupSelectionResult = session.toolGroupSelectionResult();
        builder.selectedGroupIds = session.toolGroupSelectionResult().selectedGroupIds();
        builder.selectedToolNames = session.toolGroupSelectionResult().selectedToolNames();
        return builder;
    }

    // ── Setter 链式方法 ──

    public ContextFrameBuilder requestId(String value) { this.requestId = value; return this; }
    public ContextFrameBuilder clientMessageId(String value) { this.clientMessageId = value; return this; }
    public ContextFrameBuilder userId(String value) { this.userId = value; return this; }
    public ContextFrameBuilder sessionId(String value) { this.sessionId = value; return this; }
    public ContextFrameBuilder personaId(String value) { this.personaId = value; return this; }
    public ContextFrameBuilder inputType(String value) { this.inputType = value; return this; }
    public ContextFrameBuilder rawUserInput(String value) { this.rawUserInput = value; return this; }
    public ContextFrameBuilder normalizedUserInput(String value) { this.normalizedUserInput = value; return this; }
    public ContextFrameBuilder intentResult(IntentResult value) { this.intentResult = value; return this; }
    public ContextFrameBuilder toolGroupSelectionResult(ToolGroupSelectionResult value) { this.toolGroupSelectionResult = value; return this; }
    public ContextFrameBuilder selectedGroupIds(List<ToolGroupId> value) { this.selectedGroupIds = value; return this; }
    public ContextFrameBuilder selectedToolNames(List<String> value) { this.selectedToolNames = value; return this; }
    public ContextFrameBuilder effectivePersonaId(String value) { this.effectivePersonaId = value; return this; }
    public ContextFrameBuilder memorySummary(String value) { this.memorySummary = value; return this; }
    public ContextFrameBuilder vehicleStateSnapshot(String value) { this.vehicleStateSnapshot = value; return this; }
    public ContextFrameBuilder timeContext(String value) { this.timeContext = value; return this; }
    public ContextFrameBuilder promptContext(String value) { this.promptContext = value; return this; }
    public ContextFrameBuilder renderedExtraContext(String value) { this.renderedExtraContext = value; return this; }
    public ContextFrameBuilder tokenEstimate(int value) { this.tokenEstimate = value; return this; }
    public ContextFrameBuilder debugInfo(ContextDebugInfo value) { this.debugInfo = value; return this; }
    public ContextFrameBuilder sections(List<ContextSection> value) { this.sections = value; return this; }
    public ContextFrameBuilder contributions(List<ContextContribution> value) { this.contributions = value; return this; }

    /**
     * 构建 {@link ContextFrame}。
     * <p>
     * effectivePersonaId 缺省值为 personaId。
     */
    public ContextFrame build() {
        String effectivePersona = effectivePersonaId != null
                ? effectivePersonaId : personaId;
        return new ContextFrame(
                requestId, clientMessageId, userId, sessionId, personaId,
                inputType, rawUserInput, normalizedUserInput,
                intentResult, toolGroupSelectionResult,
                selectedGroupIds, selectedToolNames,
                effectivePersona, memorySummary, vehicleStateSnapshot,
                timeContext, promptContext, renderedExtraContext,
                tokenEstimate, debugInfo, sections, contributions);
    }
}
