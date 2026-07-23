package com.hirain.aiagent.runtime;

import com.hirain.aiagent.AgentRequest;
import com.hirain.aiagent.intentrouter.IntentResult;
import com.hirain.aiagent.toolgroup.ToolGroupSelectionResult;
import com.hirain.aiagent.trace.TraceContext;
import com.hirain.aiagent.vision.routing.VisionIntentDecision;
import com.hirain.aiagent.rag.policy.KnowledgeIntentDecision;
import com.hirain.aiagent.rag.policy.KnowledgeRequestState;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 单次请求的运行时快照 — 规范化后的请求各维度字段，不可变。
 * <p>
 * 包含 AgentRequest 引用、规范化后的 requestId/sessionId/userId、执行时间戳、
 * IntentResult、TraceContext 以及提供给 AgentLoopOrchestrator 的上下文 Map。
 * orchestratorContext 使用不可修改 Map 封装，防止外部篡改。
 */
public final class RequestSession {

    private final AgentRequest request;
    private final String requestId;
    private final String sessionId;
    private final String userId;
    private final String sourceApp;
    private final String inputType;
    private final String personaId;
    private final String clientMessageId;
    private final String userInput;
    private final long startedAtMs;
    private final RequestDeadline deadline;
    private final TraceContext traceContext;
    private final IntentResult intentResult;
    private final ToolGroupSelectionResult toolGroupSelectionResult;
    private final VisionIntentDecision visionIntentDecision;
    private final KnowledgeIntentDecision knowledgeIntentDecision;
    private final KnowledgeRequestState knowledgeRequestState;
    private final Map<String, Object> orchestratorContext;

    RequestSession(AgentRequest request, String requestId, String sessionId,
                   String userId, String sourceApp, String inputType,
                   String personaId, String clientMessageId, String userInput,
                   long startedAtMs, TraceContext traceContext,
                   IntentResult intentResult,
                   ToolGroupSelectionResult toolGroupSelectionResult,
                   Map<String, Object> orchestratorContext) {
        this(request, requestId, sessionId, userId, sourceApp, inputType,
                personaId, clientMessageId, userInput,
                RequestDeadline.standard(startedAtMs), traceContext, intentResult,
                toolGroupSelectionResult, VisionIntentDecision.none("legacy_default"),
                KnowledgeIntentDecision.none("legacy_default"), new KnowledgeRequestState(), orchestratorContext);
    }

    RequestSession(AgentRequest request, String requestId, String sessionId,
                   String userId, String sourceApp, String inputType,
                   String personaId, String clientMessageId, String userInput,
                   RequestDeadline deadline, TraceContext traceContext,
                   IntentResult intentResult,
                   ToolGroupSelectionResult toolGroupSelectionResult,
                   VisionIntentDecision visionIntentDecision,
                   Map<String, Object> orchestratorContext) {
        this(request, requestId, sessionId, userId, sourceApp, inputType, personaId, clientMessageId, userInput,
                deadline, traceContext, intentResult, toolGroupSelectionResult, visionIntentDecision,
                KnowledgeIntentDecision.none("legacy_default"), new KnowledgeRequestState(), orchestratorContext);
    }
    RequestSession(AgentRequest request, String requestId, String sessionId,
                   String userId, String sourceApp, String inputType,
                   String personaId, String clientMessageId, String userInput,
                   RequestDeadline deadline, TraceContext traceContext,
                   IntentResult intentResult, ToolGroupSelectionResult toolGroupSelectionResult,
                   VisionIntentDecision visionIntentDecision, KnowledgeIntentDecision knowledgeIntentDecision,
                   KnowledgeRequestState knowledgeRequestState, Map<String, Object> orchestratorContext) {
        this.request = request;
        this.requestId = requestId;
        this.sessionId = sessionId;
        this.userId = userId;
        this.sourceApp = sourceApp;
        this.inputType = inputType;
        this.personaId = personaId;
        this.clientMessageId = clientMessageId;
        this.userInput = userInput;
        this.deadline = deadline != null ? deadline
                : RequestDeadline.standard(System.currentTimeMillis());
        this.startedAtMs = this.deadline.startedAtMs();
        this.traceContext = traceContext;
        this.intentResult = intentResult;
        this.toolGroupSelectionResult = toolGroupSelectionResult;
        this.visionIntentDecision = visionIntentDecision != null
                ? visionIntentDecision : VisionIntentDecision.none("missing_vision_decision");
        this.knowledgeIntentDecision = knowledgeIntentDecision != null
                ? knowledgeIntentDecision : KnowledgeIntentDecision.none("missing_knowledge_decision");
        this.knowledgeRequestState = knowledgeRequestState != null ? knowledgeRequestState : new KnowledgeRequestState();
        this.orchestratorContext = Collections.unmodifiableMap(
                new HashMap<>(orchestratorContext));
    }

    public AgentRequest request() { return request; }
    public String requestId() { return requestId; }
    public String sessionId() { return sessionId; }
    public String userId() { return userId; }
    public String sourceApp() { return sourceApp; }
    public String inputType() { return inputType; }
    public String personaId() { return personaId; }
    public String clientMessageId() { return clientMessageId; }
    public String userInput() { return userInput; }
    public long startedAtMs() { return startedAtMs; }
    public RequestDeadline deadline() { return deadline; }
    public TraceContext traceContext() { return traceContext; }
    public IntentResult intentResult() { return intentResult; }
    public ToolGroupSelectionResult toolGroupSelectionResult() { return toolGroupSelectionResult; }
    public VisionIntentDecision visionIntentDecision() { return visionIntentDecision; }
    public KnowledgeIntentDecision knowledgeIntentDecision() { return knowledgeIntentDecision; }
    public KnowledgeRequestState knowledgeRequestState() { return knowledgeRequestState; }
    public Map<String, Object> orchestratorContext() { return orchestratorContext; }
}
