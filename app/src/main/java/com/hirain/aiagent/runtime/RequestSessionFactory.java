package com.hirain.aiagent.runtime;

import com.hirain.aiagent.AgentRequest;
import com.hirain.aiagent.intentrouter.IntentResult;
import com.hirain.aiagent.toolgroup.ToolGroupSelectionResult;
import com.hirain.aiagent.trace.TraceContext;

import java.util.HashMap;
import java.util.Map;

/**
 * 从 AgentRequest 和 TraceContext 创建 RequestSession。
 * <p>
 * 设计原因：将 requestId/sessionId/userId 的规范化逻辑集中在此，
 * 避免在 AgentRuntime 或 AIAgentService 中重复校验。
 */
public class RequestSessionFactory {

    private final IdGenerator idGenerator;
    private final TimeProvider timeProvider;

    public RequestSessionFactory(IdGenerator idGenerator, TimeProvider timeProvider) {
        this.idGenerator = idGenerator;
        this.timeProvider = timeProvider;
    }

    public RequestSession create(AgentRequest request, String personaId,
                                 TraceContext traceContext, IntentResult intentResult,
                                 ToolGroupSelectionResult toolGroupSelectionResult) {
        // ── intentResult 空值降级（request 可能为 null） ──
        if (intentResult == null) {
            String safeText = request != null ? nonEmpty(request.getText(), "") : "";
            String safeInputType = request != null ? nonEmpty(request.getInputType(), "TEXT") : "TEXT";
            intentResult = IntentResult.unknown(safeText, safeInputType, "missing_intent_result");
        }

        // ── toolGroupSelectionResult 空值降级 ──
        if (toolGroupSelectionResult == null) {
            toolGroupSelectionResult = ToolGroupSelectionResult.fallback("missing_tool_group_selection");
        }

        // ── request 空值时创建最小可用 RequestSession ──
        if (request == null) {
            long now = timeProvider.nowMillis();
            String safePersona = nonEmpty(personaId, "chat");
            return new RequestSession(null, idGenerator.newRequestId(), null, "default_user",
                    "unknown", "TEXT", safePersona, "",
                    now, traceContext, intentResult, toolGroupSelectionResult, new HashMap<>());
        }

        // ── 规范化 requestId ──
        String requestId = nonEmpty(request.getRequestId(), idGenerator.newRequestId());

        // ── sessionId：缺失时不创建新的业务 sessionId ──
        String sessionId = emptyToNull(request.getSessionId());

        // ── userId：有 sessionId 时使用该值，否则使用 default_user ──
        String userId = nonEmpty(sessionId, "default_user");

        // ── 其余字段 ──
        String sourceApp = nonEmpty(request.getSourceApp(), "unknown");
        String inputType = nonEmpty(request.getInputType(), "TEXT");
        String normalizedPersonaId = nonEmpty(personaId, "chat");
        String userInput = nonEmpty(request.getText(), "");

        // ── 构建 orchestratorContext（不包含 intentResult） ──
        Map<String, Object> context = new HashMap<>();
        context.put("user_id", userId);
        if (request.getExtraContext() != null) {
            context.putAll(request.getExtraContext());
        }
        if (traceContext != null) {
            context.putAll(traceContext.toContextData());
        }

        long now = timeProvider.nowMillis();

        return new RequestSession(request, requestId, sessionId, userId,
                sourceApp, inputType, normalizedPersonaId, userInput,
                now, traceContext, intentResult, toolGroupSelectionResult, context);
    }

    private static String nonEmpty(String value, String fallback) {
        return (value != null && !value.isEmpty()) ? value : fallback;
    }

    private static String emptyToNull(String value) {
        return (value != null && !value.isEmpty()) ? value : null;
    }
}
