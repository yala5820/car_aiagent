package com.hirain.aiagent.runtime;

import com.hirain.aiagent.AgentRequest;
import com.hirain.aiagent.intentrouter.IntentResult;
import com.hirain.aiagent.trace.TraceContext;

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
    private final String userInput;
    private final long startedAtMs;
    private final TraceContext traceContext;
    private final IntentResult intentResult;
    private final Map<String, Object> orchestratorContext;

    RequestSession(AgentRequest request, String requestId, String sessionId,
                   String userId, String sourceApp, String inputType,
                   String personaId, String userInput, long startedAtMs,
                   TraceContext traceContext, IntentResult intentResult,
                   Map<String, Object> orchestratorContext) {
        this.request = request;
        this.requestId = requestId;
        this.sessionId = sessionId;
        this.userId = userId;
        this.sourceApp = sourceApp;
        this.inputType = inputType;
        this.personaId = personaId;
        this.userInput = userInput;
        this.startedAtMs = startedAtMs;
        this.traceContext = traceContext;
        this.intentResult = intentResult;
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
    public String userInput() { return userInput; }
    public long startedAtMs() { return startedAtMs; }
    public TraceContext traceContext() { return traceContext; }
    public IntentResult intentResult() { return intentResult; }
    public Map<String, Object> orchestratorContext() { return orchestratorContext; }
}
