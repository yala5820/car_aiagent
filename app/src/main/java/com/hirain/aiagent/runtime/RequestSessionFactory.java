package com.hirain.aiagent.runtime;

import com.hirain.aiagent.AgentRequest;
import com.hirain.aiagent.intentrouter.IntentResult;
import com.hirain.aiagent.toolgroup.ToolGroupSelectionResult;
import com.hirain.aiagent.trace.TraceContext;
import com.hirain.aiagent.vision.routing.VisionIntentDecision;
import com.hirain.aiagent.rag.policy.KnowledgeIntentDecision;
import com.hirain.aiagent.rag.policy.KnowledgeRequestState;

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
        return create(request, traceContext, intentResult, toolGroupSelectionResult,
                resolvedSessionId, null);
    }

    /** 使用 Runtime 前准入阶段创建的统一 deadline。 */
    public RequestSession create(AgentRequest request,
                                 TraceContext traceContext, IntentResult intentResult,
                                 ToolGroupSelectionResult toolGroupSelectionResult,
                                 String resolvedSessionId,
                                 RequestDeadline requestDeadline) {
        return create(request, traceContext, intentResult, toolGroupSelectionResult,
                resolvedSessionId, requestDeadline, VisionIntentDecision.none("legacy_default"));
    }

    /** 使用 Runtime 已完成的视觉决策创建不可变请求快照。 */
    public RequestSession create(AgentRequest request,
                                 TraceContext traceContext, IntentResult intentResult,
                                 ToolGroupSelectionResult toolGroupSelectionResult,
                                 String resolvedSessionId,
                                 RequestDeadline requestDeadline,
                                 VisionIntentDecision visionIntentDecision) {
        // ── intentResult 空值降级（request 可能为 null） ──
        if (intentResult == null) {
            String safeText = request != null ? nonEmpty(request.getText(), "") : "";
            String safeInputType = request != null ? nonEmpty(request.getInputType(), "TEXT") : "TEXT";
            intentResult = IntentResult.unknown(safeText, safeInputType, "missing_intent_result");
        }

        // ── toolGroupSelectionResult 空值降级 ──
        // Factory 作为最后防线也必须失败关闭，禁止空选择被解释为普通对话或全量工具。
        if (toolGroupSelectionResult == null) {
            toolGroupSelectionResult = ToolGroupSelectionResult.failedClosed("missing_tool_group_selection");
        }

        // ── request 空值时创建最小可用 RequestSession ──
        if (request == null) {
            long now = timeProvider.nowMillis();
            RequestDeadline deadline = requestDeadline != null
                    ? requestDeadline : RequestDeadline.standard(now);
            return new RequestSession(null, idGenerator.newRequestId(), null, "default_user",
                    "unknown", "TEXT", "chat", null, "",
                    deadline, traceContext, intentResult, toolGroupSelectionResult,
                    visionIntentDecision, new HashMap<>());
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
            // 图片选择是受控执行选项，不能作为调用方上下文进入模型可见 Context。
            context.remove("vision_demo_image_id");
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
        RequestDeadline deadline = requestDeadline != null
                ? requestDeadline : RequestDeadline.standard(now);

        return new RequestSession(request, requestId, sessionId, userId,
                sourceApp, inputType, normalizedPersonaId, clientMessageId, userInput,
                deadline, traceContext, intentResult, toolGroupSelectionResult,
                visionIntentDecision, context);
    }

    /** Runtime 传入一次性知识决策及同一实例状态，Tool 只能通过该 Session/执行上下文取得它。 */
    public RequestSession createWithKnowledge(AgentRequest request, TraceContext traceContext, IntentResult intentResult,
            ToolGroupSelectionResult selection, String resolvedSessionId, RequestDeadline deadline,
            VisionIntentDecision vision, KnowledgeIntentDecision knowledge, KnowledgeRequestState state) {
        RequestSession base=create(request,traceContext,intentResult,selection,resolvedSessionId,deadline,vision);
        return new RequestSession(base.request(),base.requestId(),base.sessionId(),base.userId(),base.sourceApp(),base.inputType(),base.personaId(),base.clientMessageId(),base.userInput(),base.deadline(),base.traceContext(),base.intentResult(),base.toolGroupSelectionResult(),base.visionIntentDecision(),knowledge,state,base.orchestratorContext());
    }

    private static String nonEmpty(String value, String fallback) {
        return (value != null && !value.isEmpty()) ? value : fallback;
    }

    private static String emptyToNull(String value) {
        return (value != null && !value.isEmpty()) ? value : null;
    }
}
