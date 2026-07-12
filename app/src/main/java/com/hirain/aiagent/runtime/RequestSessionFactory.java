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
 * 设计原因：将 requestId/sessionId/userId/personaId 的规范化逻辑集中在此，
 * 避免在 AgentRuntime 或 AIAgentService 中重复校验。
 */
public class RequestSessionFactory {

    private final IdGenerator idGenerator;
    private final TimeProvider timeProvider;

    public RequestSessionFactory(IdGenerator idGenerator, TimeProvider timeProvider) {
        this.idGenerator = idGenerator;
        this.timeProvider = timeProvider;
    }

    public RequestSession create(AgentRequest request,
                                 TraceContext traceContext, IntentResult intentResult,
                                 ToolGroupSelectionResult toolGroupSelectionResult) {
        String sessionId = request != null ? emptyToNull(request.getSessionId()) : null;
        return create(request, traceContext, intentResult, toolGroupSelectionResult, sessionId);
    }

    /**
     * 创建 RequestSession，使用已解析的 resolvedSessionId。
     * <p>
     * 设计原因：Phase 0 起，TEXT 主路径的 sessionId 应由 Runtime 预先解析，
     * 不再由 Factory 从 request 中透传（避免缺失 sessionId 时静默为 null）。
     */
    public RequestSession create(AgentRequest request,
                                 TraceContext traceContext, IntentResult intentResult,
                                 ToolGroupSelectionResult toolGroupSelectionResult,
                                 String resolvedSessionId) {
        // ── intentResult 空值降级（request 可能为 null） ──
        if (intentResult == null) {
            String safeText = request != null ? nonEmpty(request.getText(), "") : "";
            String safeInputType = request != null ? nonEmpty(request.getInputType(), "TEXT") : "TEXT";
            intentResult = IntentResult.unknown(safeText, safeInputType, "missing_intent_result");
        }

        // ── toolGroupSelectionResult 空值降级 ──
        // 生产主路径的全量兜底由 AgentRuntime.selectToolGroupsSafely() 保证，
        // Factory 这里是最后防线，保留轻量降级（CHAT_ONLY_GROUP + 空 toolNames）。
        if (toolGroupSelectionResult == null) {
            toolGroupSelectionResult = ToolGroupSelectionResult.fallback("missing_tool_group_selection");
        }

        // ── request 空值时创建最小可用 RequestSession ──
        if (request == null) {
            long now = timeProvider.nowMillis();
            return new RequestSession(null, idGenerator.newRequestId(), null, "default_user",
                    "unknown", "TEXT", "chat", null, "",
                    now, traceContext, intentResult, toolGroupSelectionResult, new HashMap<>());
        }

        // ── 规范化 requestId ──
        String requestId = nonEmpty(request.getRequestId(), idGenerator.newRequestId());

        // ── sessionId：优先使用已解析的 resolvedSessionId，否则从 request 读取 ──
        String sessionId = resolvedSessionId != null
                ? resolvedSessionId
                : (request != null ? emptyToNull(request.getSessionId()) : null);

        // ── userId：从请求中读取，缺失时为 default_user ──
        String userId = nonEmpty(request.getUserId(), "default_user");

        // ── 其余字段 ──
        String sourceApp = nonEmpty(request.getSourceApp(), "unknown");
        String inputType = nonEmpty(request.getInputType(), "TEXT");
        String normalizedPersonaId = nonEmpty(request.getPersonaId(), "chat");
        String clientMessageId = emptyToNull(request.getClientMessageId());
        String userInput = nonEmpty(request.getText(), "");

        // ── 构建 orchestratorContext ──
        // 写入顺序说明：
        // 1. 先写入 caller extraContext（低优先级）
        // 2. 再写入 traceContext 数据（中优先级）
        // 3. 最后写入 canonical 身份字段（高优先级），确保 extraContext 不可覆盖 Runtime 解析结果
        Map<String, Object> context = new HashMap<>();
        if (request.getExtraContext() != null) {
            context.putAll(request.getExtraContext());
        }
        if (traceContext != null) {
            context.putAll(traceContext.toContextData());
        }
        context.put("user_id", userId);
        context.put("persona_id", normalizedPersonaId);
        if (clientMessageId != null) {
            context.put("client_message_id", clientMessageId);
        }
        if (sessionId != null) {
            context.put("session_id", sessionId);
        }

        long now = timeProvider.nowMillis();

        return new RequestSession(request, requestId, sessionId, userId,
                sourceApp, inputType, normalizedPersonaId, clientMessageId, userInput,
                now, traceContext, intentResult, toolGroupSelectionResult, context);
    }

    private static String nonEmpty(String value, String fallback) {
        return (value != null && !value.isEmpty()) ? value : fallback;
    }

    private static String emptyToNull(String value) {
        return (value != null && !value.isEmpty()) ? value : null;
    }
}
