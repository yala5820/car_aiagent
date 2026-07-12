package com.hirain.aiagent.runtime;

import com.hirain.aiagent.AgentRequest;
import com.hirain.aiagent.context.ContextBuildInput;

import com.hirain.aiagent.context.ContextCancelChecker;
import com.hirain.aiagent.context.ContextFrame;
import com.hirain.aiagent.context.ContextOrchestrator;
import com.hirain.aiagent.context.ContextErrorCode;
import com.hirain.aiagent.context.ContextPrepareResult;
import com.hirain.aiagent.core.AgentResult;
import com.hirain.aiagent.intentrouter.IntentResult;
import com.hirain.aiagent.intentrouter.IntentRouter;
import com.hirain.aiagent.intentrouter.KeywordIntentRouter;
import com.hirain.aiagent.toolgroup.DefaultToolGroupSelector;
import com.hirain.aiagent.toolgroup.ToolGroupRegistry;
import com.hirain.aiagent.toolgroup.ToolGroupSelectionResult;
import com.hirain.aiagent.toolgroup.ToolGroupSelector;
import com.hirain.aiagent.memory.SessionIdResolver;
import com.hirain.aiagent.trace.TraceAttributeKeys;
import com.hirain.aiagent.trace.TraceContext;
import com.hirain.aiagent.trace.TraceSession;
import com.hirain.aiagent.trace.TraceSpanNames;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

/**
 * Runtime 主入口 — 位于 AIAgentService 与 AgentLoopOrchestrator 之间。
 * <p>
 * 职责：创建 RequestSession（含 IntentResult 路由）、通过 ContextOrchestrator 构建
 * ContextFrame、执行 Agent 对话、封装 RuntimeResult。
 * 不创建 TraceSession、不调度 timeout、不通知 listener。
 */
public class AgentRuntime {

    private static final String CHAT_PERSONA = "chat";

    private final AgentExecutor chatExecutor;
    private final ContextOrchestrator contextOrchestrator;
    private final RuntimeCancelChecker cancelChecker;
    private final IntentRouter intentRouter;
    private final ToolGroupSelector toolGroupSelector;
    private final ToolGroupRegistry toolGroupRegistry;
    private final IdGenerator idGenerator;
    private final TimeProvider timeProvider;
    private final RequestSessionFactory sessionFactory;
    private final SessionIdResolver sessionIdResolver;

    // ── 默认 ContextOrchestrator ──

    /** 默认 ContextOrchestrator — 仅用于测试构造器和简单生产路径。 */
    private static ContextOrchestrator defaultContextOrchestrator() {
        com.hirain.aiagent.prompt.PromptManager pm = new com.hirain.aiagent.prompt.PromptManager(null) {
            @Override public String render(String templateName) {
                return "System prompt for " + templateName;
            }
        };
        // JVM 兼容 ToolRegistry（override 4 个方法，不调用 registerAll/ToolDispatcher/Log.d）
        com.hirain.aiagent.ai.langchain4j.tool.ToolRegistry tr =
                new com.hirain.aiagent.ai.langchain4j.tool.ToolRegistry() {
            @Override public java.util.List<dev.langchain4j.agent.tool.ToolSpecification> toolSpecificationsByNames(
                    java.util.List<String> names) {
                return names != null ? names.stream().map(n -> dev.langchain4j.agent.tool.ToolSpecification.builder()
                        .name(n).description("default " + n).build()).toList() : java.util.List.of();
            }
            @Override public java.util.List<dev.langchain4j.agent.tool.ToolSpecification> enabledToolSpecifications() {
                return java.util.List.of();
            }
            @Override public java.util.List<dev.langchain4j.agent.tool.ToolSpecification> getToolSpecifications() {
                return java.util.List.of();
            }
            @Override public int size() { return 0; }
        };
        return ContextOrchestrator.defaultForText(
                ContextBuildInput.builder()
                        .promptManager(pm)
                        .toolRegistry(tr)
                        .toolGroupRegistry(ToolGroupRegistry.defaultRegistry())
                        .build());
    }

    // ── 构造函数 ──

    /** 最简生产构造函数 — 使用默认 ContextOrchestrator，无取消检查。 */
    public AgentRuntime(AgentExecutor chatExecutor) {
        this(chatExecutor, defaultContextOrchestrator());
    }

    /** 生产构造函数 — 指定 ContextOrchestrator，无取消检查。 */
    public AgentRuntime(AgentExecutor chatExecutor,
                        ContextOrchestrator contextOrchestrator) {
        this(chatExecutor, contextOrchestrator, RuntimeCancelChecker.neverCancelled());
    }

    /** 生产构造函数 — 指定 ContextOrchestrator 和取消检查器。 */
    public AgentRuntime(AgentExecutor chatExecutor,
                        ContextOrchestrator contextOrchestrator,
                        RuntimeCancelChecker cancelChecker) {
        this(chatExecutor, contextOrchestrator, null,
                ToolGroupRegistry.defaultRegistry(),
                new KeywordIntentRouter(),
                new DefaultToolGroupSelector(ToolGroupRegistry.defaultRegistry()),
                new UuidIdGenerator(), new SystemTimeProvider(), cancelChecker);
    }

    /** 生产构造函数 — 指定 ContextOrchestrator、SessionIdResolver 和取消检查器。 */
    public AgentRuntime(AgentExecutor chatExecutor,
                        ContextOrchestrator contextOrchestrator,
                        SessionIdResolver sessionIdResolver,
                        RuntimeCancelChecker cancelChecker) {
        this(chatExecutor, contextOrchestrator, sessionIdResolver,
                ToolGroupRegistry.defaultRegistry(),
                new KeywordIntentRouter(),
                new DefaultToolGroupSelector(ToolGroupRegistry.defaultRegistry()),
                new UuidIdGenerator(), new SystemTimeProvider(), cancelChecker);
    }

    /** 测试构造函数 — 指定 ContextOrchestrator 和 ID/时间，使用默认 Router/Selector。 */
    public AgentRuntime(AgentExecutor chatExecutor,
                        ContextOrchestrator contextOrchestrator,
                        IdGenerator idGenerator,
                        TimeProvider timeProvider) {
        this(chatExecutor, contextOrchestrator, null,
                ToolGroupRegistry.defaultRegistry(),
                new KeywordIntentRouter(),
                new DefaultToolGroupSelector(ToolGroupRegistry.defaultRegistry()),
                idGenerator, timeProvider, RuntimeCancelChecker.neverCancelled());
    }

    /** 测试构造函数 — 默认 ContextOrchestrator/Router/Selector + 可注入 ID/时间。 */
    public AgentRuntime(AgentExecutor chatExecutor,
                        IdGenerator idGenerator,
                        TimeProvider timeProvider) {
        this(chatExecutor, defaultContextOrchestrator(), null,
                ToolGroupRegistry.defaultRegistry(),
                new KeywordIntentRouter(),
                new DefaultToolGroupSelector(ToolGroupRegistry.defaultRegistry()),
                idGenerator, timeProvider, RuntimeCancelChecker.neverCancelled());
    }

    /** 测试构造函数 — 默认 ContextOrchestrator/Selector + 可注入 Router + ID/时间。 */
    public AgentRuntime(AgentExecutor chatExecutor,
                        IntentRouter intentRouter,
                        IdGenerator idGenerator,
                        TimeProvider timeProvider) {
        this(chatExecutor, defaultContextOrchestrator(), null, ToolGroupRegistry.defaultRegistry(),
                intentRouter,
                new DefaultToolGroupSelector(ToolGroupRegistry.defaultRegistry()),
                idGenerator, timeProvider, RuntimeCancelChecker.neverCancelled());
    }

    /** 测试构造函数 — 默认 ContextOrchestrator + 可注入 Router/Selector + ID/时间。 */
    public AgentRuntime(AgentExecutor chatExecutor,
                        IntentRouter intentRouter,
                        ToolGroupSelector toolGroupSelector,
                        IdGenerator idGenerator,
                        TimeProvider timeProvider) {
        this(chatExecutor, defaultContextOrchestrator(), null, ToolGroupRegistry.defaultRegistry(),
                intentRouter,
                toolGroupSelector, idGenerator, timeProvider,
                RuntimeCancelChecker.neverCancelled());
    }

    /** 全可注入构造函数。 */
    public AgentRuntime(AgentExecutor chatExecutor,
                        ContextOrchestrator contextOrchestrator,
                        SessionIdResolver sessionIdResolver,
                        ToolGroupRegistry toolGroupRegistry,
                        IntentRouter intentRouter,
                        ToolGroupSelector toolGroupSelector,
                        IdGenerator idGenerator,
                        TimeProvider timeProvider,
                        RuntimeCancelChecker cancelChecker) {
        this.chatExecutor = chatExecutor;
        this.contextOrchestrator = contextOrchestrator;
        this.sessionIdResolver = sessionIdResolver;
        this.toolGroupRegistry = toolGroupRegistry;
        this.cancelChecker = cancelChecker;
        this.intentRouter = intentRouter;
        this.toolGroupSelector = toolGroupSelector;
        this.idGenerator = idGenerator;
        this.timeProvider = timeProvider;
        this.sessionFactory = new RequestSessionFactory(idGenerator, timeProvider);
    }

    // ── Session 创建 ──

    /**
     * 从 AgentRequest 和 TraceContext 创建规范化请求快照。
     * 在创建 Session 前调用 IntentRouter 和 ToolGroupSelector，并写入 Trace。
     */
    public RequestSession startSession(AgentRequest request, TraceContext traceContext) {
        IntentResult intentResult = routeIntentSafely(request);
        ToolGroupSelectionResult toolGroupSelectionResult = selectToolGroupsSafely(intentResult, request);
        writeIntentToTrace(traceContext, intentResult);
        writeToolGroupsToTrace(traceContext, toolGroupSelectionResult);

        // 在构建 Context 前解析 resolvedSessionId
        String resolvedSessionId = resolveSessionIdSafely(request);

        RequestSession session = sessionFactory.create(request, traceContext,
                intentResult, toolGroupSelectionResult, resolvedSessionId);
        writeRequestMetaToTrace(traceContext, session);
        return session;
    }

    // ── 执行 ──

    /**
     * 执行 Agent 对话：先创建 agent.loop span，调用 ContextPreparer.prepare()，
     * 构建后、AgentLoop 前检查取消，再委托 chatExecutor 执行。
     */
    public RuntimeResult execute(RequestSession session) {
        // 空输入校验
        if (session == null || session.userInput() == null
                || session.userInput().trim().isEmpty()) {
            String reqId = session != null ? session.requestId() : null;
            String sessId = session != null ? session.sessionId() : null;
            String userId = session != null ? session.userId() : null;
            String persona = session != null ? session.personaId() : null;
            String clientMsg = session != null ? session.clientMessageId() : null;
            return RuntimeResult.fromAgentResult(reqId, sessId, userId, persona, clientMsg,
                    AgentResult.error(AgentResult.ErrorType.INVALID_INPUT, "Empty user input"),
                    timeProvider.nowMillis());
        }

        // 从 RequestSession 中提取 TraceSession，用于创建 agent.loop span
        TraceContext traceCtx = session.traceContext();
        TraceSession traceSession = (traceCtx != null && traceCtx.isActive())
                ? traceCtx.session() : null;

        Span loopSpan = traceSession != null
                ? traceSession.startAgentLoopSpan()
                : io.opentelemetry.api.GlobalOpenTelemetry.get()
                        .getTracer("agent")
                        .spanBuilder(TraceSpanNames.AGENT_LOOP)
                        .startSpan();
        try (Scope ignored = loopSpan.makeCurrent()) {
            // Context 准备（将 RuntimeCancelChecker 适配为 ContextCancelChecker）
            ContextCancelChecker contextCancel = () ->
                    cancelChecker != null && cancelChecker.isCancelled(session);
            ContextPrepareResult prepareResult = contextOrchestrator.prepare(session, contextCancel);
            if (prepareResult.isFailed()) {
                return failureFromPrepare(prepareResult, session);
            }
            if (prepareResult.isCancelled()) {
                return cancelledFromPrepare(prepareResult, session);
            }
            if (cancelChecker.isCancelled(session)) {
                return RuntimeResult.cancelled(
                        session.requestId(), session.sessionId(),
                        session.userId(), session.personaId(), session.clientMessageId(),
                        "cancelled_before_agent_loop", timeProvider.nowMillis());
            }
            AgentResult result = chatExecutor.execute(session, prepareResult);
            return RuntimeResult.fromAgentResult(
                    session.requestId(), session.sessionId(),
                    session.userId(), session.personaId(), session.clientMessageId(),
                    result, timeProvider.nowMillis());
        } catch (Exception e) {
            if (Span.current() != null) {
                loopSpan.recordException(e);
            }
            return RuntimeResult.fromException(
                    session.requestId(), session.sessionId(),
                    session.userId(), session.personaId(), session.clientMessageId(),
                    e, timeProvider.nowMillis());
        } finally {
            loopSpan.end();
        }
    }

    private RuntimeResult failureFromPrepare(ContextPrepareResult pr, RequestSession session) {
        String errorType = mapContextError(pr.errorCode());
        return RuntimeResult.failure(session.requestId(), session.sessionId(),
                session.userId(), session.personaId(), session.clientMessageId(),
                errorType, pr.errorDetail() != null ? pr.errorDetail() : "Context prepare failed",
                timeProvider.nowMillis());
    }

    private static String mapContextError(ContextErrorCode code) {
        if (code == null) return "CONTEXT_BUILD_FAILED";
        return switch (code) {
            case REQUIRED_PROVIDER_FAILED -> "REQUIRED_PROVIDER_FAILED";
            case TOOL_SPEC_RESOLUTION_FAILED -> "TOOL_SPEC_RESOLUTION_FAILED";
            case MEMORY_COMPACTION_FAILED -> "MEMORY_COMPACTION_FAILED";
            case CONTEXT_CANCELLED -> "CANCELLED";
            case CONTEXT_BUDGET_EXCEEDED -> "CONTEXT_BUDGET_EXCEEDED";
            case MESSAGE_SEQUENCE_INVALID -> "MESSAGE_SEQUENCE_INVALID";
            default -> "CONTEXT_BUILD_FAILED";
        };
    }

    private RuntimeResult cancelledFromPrepare(ContextPrepareResult pr, RequestSession session) {
        return RuntimeResult.cancelled(session.requestId(), session.sessionId(),
                session.userId(), session.personaId(), session.clientMessageId(),
                "context_cancelled", timeProvider.nowMillis());
    }

    // ── 超时 / 错误 / 取消 辅助方法 ──

    /**
     * 生成超时结果（不执行 Agent 对话）。
     */
    public RuntimeResult timeoutResult(RequestSession session) {
        return RuntimeResult.timeout(
                session.requestId(), session.sessionId(),
                session.userId(), session.personaId(), session.clientMessageId(),
                timeProvider.nowMillis());
    }

    /**
     * 从异常生成错误结果。
     */
    public RuntimeResult errorResult(RequestSession session, Exception exception) {
        return RuntimeResult.fromException(
                session.requestId(), session.sessionId(),
                session.userId(), session.personaId(), session.clientMessageId(),
                exception, timeProvider.nowMillis());
    }

    /**
     * 生成取消结果。
     */
    public RuntimeResult cancelledResult(RequestSession session, String reason) {
        return RuntimeResult.cancelled(
                session.requestId(), session.sessionId(),
                session.userId(), session.personaId(), session.clientMessageId(),
                reason, timeProvider.nowMillis());
    }

    // ── Intent 路由 ──

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

    private ToolGroupSelectionResult selectToolGroupsSafely(IntentResult intentResult, AgentRequest request) {
        String text = request != null && request.getText() != null ? request.getText() : "";
        try {
            ToolGroupSelectionResult result = toolGroupSelector.select(intentResult, text);
            if (result != null) return result;
            // selector 返回 null → 全量兜底
            return ToolGroupSelectionResult.allToolsFallback(
                    toolGroupRegistry, "tool_group_selector_null_all_tools");
        } catch (Exception e) {
            // selector 抛异常 → 全量兜底
            return ToolGroupSelectionResult.allToolsFallback(
                    toolGroupRegistry, "tool_group_selector_exception_all_tools");
        }
    }

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

    // ── SessionId 解析 ──

    /**
     * 当配置了 SessionIdResolver 时，在 Context 构建前解析 resolvedSessionId。
     * 无 resolver 时采用旧透传行为（返回 request 中的原始 sessionId，可能为 null）。
     */
    private String resolveSessionIdSafely(AgentRequest request) {
        if (sessionIdResolver == null) {
            return request != null ? emptyToNull(request.getSessionId()) : null;
        }
        String userId = request != null && request.getUserId() != null && !request.getUserId().isEmpty()
                ? request.getUserId()
                : "default_user";
        String requestedSessionId = request != null ? request.getSessionId() : null;
        String personaId = request != null && request.getPersonaId() != null && !request.getPersonaId().isEmpty()
                ? request.getPersonaId()
                : "chat";
        String sourceApp = request != null && request.getSourceApp() != null && !request.getSourceApp().isEmpty()
                ? request.getSourceApp()
                : "unknown";
        String title = request != null && request.getText() != null ? request.getText() : "";
        return sessionIdResolver.resolveSessionId(userId, requestedSessionId, title, personaId, sourceApp);
    }

    private static String emptyToNull(String value) {
        return (value != null && !value.isEmpty()) ? value : null;
    }

    private void writeRequestMetaToTrace(TraceContext traceContext, RequestSession session) {
        if (traceContext == null || traceContext.session() == null || session == null) return;
        if (session.clientMessageId() != null) {
            traceContext.session().setAttribute(
                    TraceAttributeKeys.CLIENT_MESSAGE_ID,
                    session.clientMessageId());
        }
        if (session.sessionId() != null) {
            traceContext.session().setAttribute("agent.session.id", session.sessionId());
        }
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
