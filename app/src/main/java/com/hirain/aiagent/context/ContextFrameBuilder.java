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

    /** 复制现有 Frame 身份与诊断字段，供预算尝试只替换 Contributions。 */
    public static ContextFrameBuilder fromFrame(ContextFrame frame) {
        ContextFrameBuilder builder = new ContextFrameBuilder();
        builder.requestId = frame.requestId();
        builder.clientMessageId = frame.clientMessageId();
        builder.userId = frame.userId();
        builder.sessionId = frame.sessionId();
        builder.personaId = frame.personaId();
        builder.inputType = frame.inputType();
        builder.rawUserInput = frame.rawUserInput();
        builder.normalizedUserInput = frame.normalizedUserInput();
        builder.intentResult = frame.intentResult();
        builder.toolGroupSelectionResult = frame.toolGroupSelectionResult();
        builder.selectedGroupIds = frame.selectedGroupIds();
        builder.selectedToolNames = frame.selectedToolNames();
        builder.effectivePersonaId = frame.effectivePersonaId();
        builder.contributions = frame.contributions();
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
                effectivePersona, contributions);
    }
}
