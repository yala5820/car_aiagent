package com.hirain.aiagent.runtime;

import com.hirain.aiagent.AgentRequest;
import com.hirain.aiagent.core.AgentResult;
import com.hirain.aiagent.intentrouter.IntentResult;
import com.hirain.aiagent.intentrouter.IntentRouter;
import com.hirain.aiagent.intentrouter.KeywordIntentRouter;
import com.hirain.aiagent.toolgroup.DefaultToolGroupSelector;
import com.hirain.aiagent.toolgroup.ToolGroupRegistry;
import com.hirain.aiagent.toolgroup.ToolGroupSelectionResult;
import com.hirain.aiagent.toolgroup.ToolGroupSelector;
import com.hirain.aiagent.trace.TraceContext;

/**
 * Runtime 主入口 — 位于 AIAgentService 与 AgentLoopOrchestrator 之间。
 * <p>
 * 职责：创建 RequestSession（含 IntentResult 路由）、执行 Agent 对话、封装 RuntimeResult。
 * 不创建 TraceSession、不调度 timeout、不通知 listener。
 */
public class AgentRuntime {

    private static final String CHAT_PERSONA = "chat";

    private final AgentExecutor chatExecutor;
    private final IntentRouter intentRouter;
    private final ToolGroupSelector toolGroupSelector;
    private final IdGenerator idGenerator;
    private final TimeProvider timeProvider;
    private final RequestSessionFactory sessionFactory;

    /**
     * 生产构造函数 — 默认 KeywordIntentRouter + DefaultToolGroupSelector + UUID + 系统时间。
     */
    public AgentRuntime(AgentExecutor chatExecutor) {
        this(chatExecutor, new KeywordIntentRouter(),
                new DefaultToolGroupSelector(ToolGroupRegistry.defaultRegistry()),
                new UuidIdGenerator(), new SystemTimeProvider());
    }

    /**
     * 测试构造函数 — 默认 KeywordIntentRouter + DefaultToolGroupSelector + 可注入 ID/时间。
     */
    public AgentRuntime(AgentExecutor chatExecutor,
                        IdGenerator idGenerator,
                        TimeProvider timeProvider) {
        this(chatExecutor, new KeywordIntentRouter(),
                new DefaultToolGroupSelector(ToolGroupRegistry.defaultRegistry()),
                idGenerator, timeProvider);
    }

    /**
     * 测试构造函数 — 默认 DefaultToolGroupSelector + 可注入 Router + ID/时间。
     */
    public AgentRuntime(AgentExecutor chatExecutor,
                        IntentRouter intentRouter,
                        IdGenerator idGenerator,
                        TimeProvider timeProvider) {
        this(chatExecutor, intentRouter,
                new DefaultToolGroupSelector(ToolGroupRegistry.defaultRegistry()),
                idGenerator, timeProvider);
    }

    /**
     * 全可注入构造函数。
     */
    public AgentRuntime(AgentExecutor chatExecutor,
                        IntentRouter intentRouter,
                        ToolGroupSelector toolGroupSelector,
                        IdGenerator idGenerator,
                        TimeProvider timeProvider) {
        this.chatExecutor = chatExecutor;
        this.intentRouter = intentRouter;
        this.toolGroupSelector = toolGroupSelector;
        this.idGenerator = idGenerator;
        this.timeProvider = timeProvider;
        this.sessionFactory = new RequestSessionFactory(idGenerator, timeProvider);
    }

    /**
     * 从 AgentRequest 和 TraceContext 创建规范化请求快照。
     * 在创建 Session 前调用 IntentRouter 和 ToolGroupSelector，并写入 Trace。
     */
    public RequestSession startSession(AgentRequest request, TraceContext traceContext) {
        IntentResult intentResult = routeIntentSafely(request);
        ToolGroupSelectionResult toolGroupSelectionResult = selectToolGroupsSafely(intentResult, request);
        writeIntentToTrace(traceContext, intentResult);
        writeToolGroupsToTrace(traceContext, toolGroupSelectionResult);
        return sessionFactory.create(request, CHAT_PERSONA, traceContext,
                intentResult, toolGroupSelectionResult);
    }

    /**
     * 执行 Agent 对话，捕获异常并返回 RuntimeResult。
     */
    public RuntimeResult execute(RequestSession session) {
        try {
            AgentResult result = chatExecutor.execute(
                    session.userInput(), session.orchestratorContext());
            return RuntimeResult.fromAgentResult(
                    session.requestId(), session.sessionId(),
                    result, timeProvider.nowMillis());
        } catch (Exception e) {
            return RuntimeResult.fromException(
                    session.requestId(), session.sessionId(),
                    e, timeProvider.nowMillis());
        }
    }

    /**
     * 生成超时结果（不执行 Agent 对话）。
     */
    public RuntimeResult timeoutResult(RequestSession session) {
        return RuntimeResult.timeout(
                session.requestId(), session.sessionId(), timeProvider.nowMillis());
    }

    /**
     * 从异常生成错误结果。
     */
    public RuntimeResult errorResult(RequestSession session, Exception exception) {
        return RuntimeResult.fromException(
                session.requestId(), session.sessionId(),
                exception, timeProvider.nowMillis());
    }

    // ── Intent 路由 ──

    /**
     * 安全调用 IntentRouter，捕获所有异常降级为 UNKNOWN。
     */
    private IntentResult routeIntentSafely(AgentRequest request) {
        String text = request != null && request.getText() != null ? request.getText() : "";
        String inputType = request != null && request.getInputType() != null
                ? request.getInputType()
                : "TEXT";
        try {
            IntentResult result = intentRouter.route(text, inputType);
            return result != null
                    ? result
                    : IntentResult.unknown(text.trim(), inputType, "router_exception");
        } catch (Exception e) {
            return IntentResult.unknown(text.trim(), inputType, "router_exception");
        }
    }

    /**
     * 安全调用 ToolGroupSelector，捕获所有异常降级为 fallback。
     */
    private ToolGroupSelectionResult selectToolGroupsSafely(IntentResult intentResult, AgentRequest request) {
        String text = request != null && request.getText() != null ? request.getText() : "";
        try {
            ToolGroupSelectionResult result = toolGroupSelector.select(intentResult, text);
            return result != null
                    ? result
                    : ToolGroupSelectionResult.fallback("tool_group_selector_null");
        } catch (Exception e) {
            return ToolGroupSelectionResult.fallback("tool_group_selector_exception");
        }
    }

    /**
     * 将 ToolGroupSelectionResult 写入 Trace root span attribute。
     */
    private void writeToolGroupsToTrace(TraceContext traceContext, ToolGroupSelectionResult result) {
        if (traceContext == null || traceContext.session() == null || result == null) return;
        traceContext.session().setAttribute("agent.tool_group.selected_group_ids",
                result.selectedGroupIds().stream()
                        .map(Enum::name)
                        .collect(java.util.stream.Collectors.joining(",")));
        traceContext.session().setAttribute("agent.tool_group.selected_tool_names",
                String.join(",", result.selectedToolNames()));
        traceContext.session().setAttribute("agent.tool_group.selection_reason",
                result.selectionReason());
        traceContext.session().setAttribute("agent.tool_group.confidence",
                result.confidence().name());
        traceContext.session().setAttribute("agent.tool_group.fallback_used",
                result.fallbackUsed());
    }

    private void writeIntentToTrace(TraceContext traceContext, IntentResult intentResult) {
        if (traceContext == null || traceContext.session() == null || intentResult == null) return;
        traceContext.session().setAttribute("agent.intent.tag", intentResult.intentTag().name());
        traceContext.session().setAttribute("agent.intent.confidence", intentResult.confidence().name());
        traceContext.session().setAttribute("agent.intent.matched_keywords",
                String.join(",", intentResult.matchedKeywords()));
        traceContext.session().setAttribute("agent.intent.source_input_type", intentResult.sourceInputType());
        traceContext.session().setAttribute("agent.intent.debug_reason", intentResult.debugReason());
    }
}
