package com.hirain.aiagent.core;

import com.hirain.aiagent.context.ContextAssemblyGateway;
import com.hirain.aiagent.context.ContextAssemblyRequest;
import com.hirain.aiagent.context.ContextAssemblyResult;
import com.hirain.aiagent.context.ContextAssemblyDebugInfo;
import com.hirain.aiagent.context.ContextBudgetPolicy;
import com.hirain.aiagent.context.ContextCancelChecker;
import com.hirain.aiagent.context.ContextErrorCode;
import com.hirain.aiagent.context.ContextPrepareResult;
import com.hirain.aiagent.context.ModelContextWindowProfiles;
import com.hirain.aiagent.memory.ContextMemoryGateway;
import com.hirain.aiagent.memory.MemoryPersistenceException;
import com.hirain.aiagent.ai.langchain4j.tool.ToolDispatchOutcome;
import com.hirain.aiagent.runtime.RequestSession;
import com.hirain.aiagent.tools.vision.VisionResultParser;
import com.hirain.aiagent.vision.routing.VisionRequirement;
import com.hirain.aiagent.safety.SafetyDecision;
import com.hirain.aiagent.safety.ToolSafetyEngine;
import com.hirain.aiagent.safety.confirmation.ToolConfirmationCoordinator;
import com.hirain.aiagent.safety.confirmation.PendingToolAction;
import com.hirain.aiagent.trace.AgentTraceRecorder;
import com.hirain.aiagent.trace.TraceAttributeKeys;
import com.hirain.aiagent.trace.TraceContext;
import com.hirain.aiagent.trace.TraceSession;
import com.hirain.aiagent.core.component.ModelCaller;
import com.hirain.aiagent.core.component.PostProcessor;
import com.hirain.aiagent.core.component.ToolExecutor;
import com.hirain.aiagent.core.policy.ToolExecutionAuthorizer;
import com.hirain.aiagent.rag.policy.KnowledgeLoopPolicy;
import com.hirain.aiagent.rag.policy.KnowledgeMemoryPolicy;
import com.hirain.aiagent.rag.policy.CitationGuard;

import java.util.List;
import java.util.ArrayList;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;

/**
 * TEXT 专用 AgentLoop — 只接收 ContextAssemblyGateway 和 TEXT 所需执行依赖。
 * <p>
 * 不持有 PromptManager、Android Context 或固定 ToolSpecification。
 * 所有模型输入的 messages/toolSpecifications 来自 ContextAssemblyResult。
 */
public class TextAgentLoopOrchestrator {

    private static final String TAG = "TextAgentLoopOrchestrator";

    private final AgentConfig config;
    private final ContextMemoryGateway memoryGateway;
    private final ContextAssemblyGateway contextAssemblyGateway;
    private final ToolSafetyEngine toolSafetyEngine;
    private final ToolConfirmationCoordinator confirmationCoordinator;
    private final AgentLoopState state = new AgentLoopState();
    private final ToolExecutionAuthorizer toolExecutionAuthorizer = new ToolExecutionAuthorizer();
    private final KnowledgeLoopPolicy knowledgeLoopPolicy = new KnowledgeLoopPolicy();
    private final KnowledgeMemoryPolicy knowledgeMemoryPolicy = new KnowledgeMemoryPolicy();
    private final CitationGuard citationGuard = new CitationGuard();

    public TextAgentLoopOrchestrator(AgentConfig config,
                                     ContextMemoryGateway memoryGateway,
                                     ContextAssemblyGateway contextAssemblyGateway,
                                     ToolSafetyEngine toolSafetyEngine) {
        this(config, memoryGateway, contextAssemblyGateway, toolSafetyEngine, null);
    }

    public TextAgentLoopOrchestrator(AgentConfig config,
                                     ContextMemoryGateway memoryGateway,
                                     ContextAssemblyGateway contextAssemblyGateway,
                                     ToolSafetyEngine toolSafetyEngine,
                                     ToolConfirmationCoordinator confirmationCoordinator) {
        if (config == null) throw new IllegalArgumentException("config must not be null");
        if (memoryGateway == null) throw new IllegalArgumentException("memoryGateway must not be null");
        if (contextAssemblyGateway == null) throw new IllegalArgumentException("contextAssemblyGateway must not be null");
        if (toolSafetyEngine == null) throw new IllegalArgumentException("toolSafetyEngine must not be null");
        this.config = config;
        this.memoryGateway = memoryGateway;
        this.contextAssemblyGateway = contextAssemblyGateway;
        this.toolSafetyEngine = toolSafetyEngine;
        this.confirmationCoordinator = confirmationCoordinator;
    }

    // ── 公开接口 ──

    public AgentLoopState getState() {
        return state;
    }

    /**
     * TEXT 专用入口 — ChatRequest 的 messages 和 toolSpecifications 来自 ContextAssemblyResult。
     */
    public AgentResult execute(RequestSession session, ContextPrepareResult prepareResult) {
        if (!state.tryStart()) {
            return AgentResult.error(AgentResult.ErrorType.INVALID_CONFIG, "Agent is busy");
        }
        if (session == null || prepareResult == null || !prepareResult.isSuccess()) {
            state.markError();
            return AgentResult.error(AgentResult.ErrorType.INVALID_CONFIG, "Invalid session or prepare result");
        }
        if (config.memoryPolicy() != AgentConfig.MemoryPolicy.PERSISTENT) {
            state.markError();
            return AgentResult.error(AgentResult.ErrorType.INVALID_CONFIG, "TEXT path requires PERSISTENT memory");
        }

        String sessionId = session.sessionId();
        if (sessionId == null || sessionId.trim().isEmpty()) {
            state.markError();
            return AgentResult.error(AgentResult.ErrorType.INVALID_CONFIG, "resolved sessionId is required for TEXT path");
        }

        String userId = session.userId() != null ? session.userId() : "default_user";
        ChatMemory chatMemory = memoryGateway.chatMemoryForSession(sessionId);
        TraceSession traceSession = extractTraceSession(session.orchestratorContext());
        AgentTraceRecorder trace = traceSession != null
                ? new AgentTraceRecorder(traceSession) : null;
        // 捕获 agent.loop 的 Context，确保 memory span 挂在 agent.loop 下而非 iteration 下
        if (trace != null) {
            trace.setMemoryParentContext(io.opentelemetry.context.Context.current());
        }
        ContextBudgetPolicy budgetPolicy = ModelContextWindowProfiles.qwenTurboDemo();
        AgentLoopContext loopCtx = new AgentLoopContext(
                session.userInput(), session.personaId(), session.orchestratorContext());

        try {
            // 当前用户消息先参与 Context 装配，只有预算和取消检查通过后才持久化。
            // 这样超预算或模型调用前取消的请求不会污染 SessionMemory。
            UserMessage currentMsg = prepareResult.currentUserMessage();
            boolean currentUserCommitted = false;
            boolean compressionAttemptedForRequest = false;
            boolean visionEvidenceSucceeded = false;
            int requiredNoToolRetryCount = 0;

            for (int i = 0; i < config.maxIterations(); i++) {
                loopCtx.setIteration(i);
                state.setIteration(i);
                session.knowledgeRequestState().beginIteration(i);

                // 创建 agent.iteration 容器 span
                Span iterSpan = trace != null
                        ? trace.startIteration(i, io.opentelemetry.context.Context.current())
                        : null;
                Scope iterScope = iterSpan != null ? iterSpan.makeCurrent() : null;
                try {

                    // TEXT 全链只读取 RequestSession 的绝对 deadline，不再启动 AgentLoop 独立计时器。
                    if (session.deadline().isExpired(System.currentTimeMillis())) {
                        state.markTimeout();
                        return AgentResult.error(AgentResult.ErrorType.TIMEOUT,
                                "request_deadline_exceeded_before_context_assembly");
                    }
    
                    // Context Assembly
                    ContextCancelChecker cancelCheck = () ->
                            prepareResult.cancelChecker() != null
                                    && prepareResult.cancelChecker().isCancelled();
                    ContextAssemblyRequest assemblyReq = new ContextAssemblyRequest(
                            prepareResult.frame(), i, budgetPolicy,
                            cancelCheck, compressionAttemptedForRequest, session);
                    ContextAssemblyResult assemblyResult = contextAssemblyGateway.assemble(assemblyReq);
                    if (assemblyResult != null && assemblyResult.compressionAttempted()) {
                        compressionAttemptedForRequest = true;
                    }
    
                    if (assemblyResult == null || !assemblyResult.success()) {
                        state.markError();
                        AgentResult.ErrorType mappedType = mapAssemblyError(assemblyResult);
                        return AgentResult.error(mappedType,
                                assemblyResult != null ? assemblyResult.errorDetail()
                                        : "Context assembly result is null");
                    }
    
                    // 预算超限检查
                    if (assemblyResult.budgetReport() != null
                            && !assemblyResult.budgetReport().withinBudget()) {
                        state.markError();
                        return AgentResult.error(AgentResult.ErrorType.CONTEXT_BUDGET_EXCEEDED,
                                "Context budget exceeded: estimated="
                                        + assemblyResult.budgetReport().estimatedInputTokens()
                                        + " max=" + assemblyResult.budgetReport().maxInputTokens());
                    }
    
                    List<ChatMessage> requestMessages = assemblyResult.messages();
                    List<ToolSpecification> requestTools = assemblyResult.toolSpecifications();

                    if (session.deadline().isExpired(System.currentTimeMillis())) {
                        state.markTimeout();
                        return AgentResult.error(AgentResult.ErrorType.TIMEOUT,
                                "request_deadline_exceeded_before_model_call");
                    }
    
                    // 模型调用前检查取消
                    if (cancelCheck.isCancelled()) {
                        state.markError();
                        return AgentResult.error(AgentResult.ErrorType.CANCELLED, "cancelled_before_model_call");
                    }
                    if (knowledgeLoopPolicy.evidenceExhausted(session.knowledgeIntentDecision(),session.knowledgeRequestState())) {
                        state.markError();
                        return AgentResult.error(AgentResult.ErrorType.TOOL_EXECUTION_FAILED,
                                "KNOWLEDGE_EVIDENCE_UNAVAILABLE");
                    }
    
                    if (!currentUserCommitted && currentMsg != null) {
                        chatMemory.add(currentMsg);
                        currentUserCommitted = true;
                    }
    
                    // ModelCaller → LLM 调用
                    ToolChoice toolChoice = null;
                    VisionRequirement visionRequirement = session.visionIntentDecision().requirement();
                    boolean knowledgeToolRequired=knowledgeLoopPolicy.beforeModel(session.knowledgeIntentDecision(),session.knowledgeRequestState()).toolRequired();
                    if (knowledgeToolRequired) toolChoice = ToolChoice.REQUIRED;
                    else if (visionEvidenceSucceeded) toolChoice = ToolChoice.NONE;
                    else if (visionRequirement == VisionRequirement.REQUIRED) toolChoice = ToolChoice.REQUIRED;
                    else if (visionRequirement == VisionRequirement.OPTIONAL) toolChoice = ToolChoice.AUTO;
                    ChatRequest.Builder requestBuilder = ChatRequest.builder()
                            .messages(requestMessages)
                            .toolSpecifications(requestTools);
                    if (toolChoice != null) requestBuilder.toolChoice(toolChoice);
                    ChatRequest request = requestBuilder.build();
    
                    Span llmSpan = trace != null
                            ? trace.startLlmCall(config.modelName(), i, requestMessages.size(),
                                    io.opentelemetry.context.Context.current())
                            : null;
                    if (trace != null) {
                        trace.recordLlmRequest(llmSpan, config.modelName(), i,
                                requestMessages, requestTools,
                                assemblyResult.budgetReport().estimatedInputTokens());
                    }
                    Scope llmScope = llmSpan != null ? llmSpan.makeCurrent() : null;
                    ChatResponse response;
                    AiMessage aiMessage;
                    try {
                        response = config.modelCaller().call(request);
                        aiMessage = response.aiMessage();
                        if (llmSpan != null) trace.enrichLlmResponse(llmSpan, response,
                                assemblyResult.budgetReport().estimatedInputTokens());
                    } catch (Exception e) {
                        if (trace != null) trace.recordException(llmSpan, e);
                        throw e;
                    } finally {
                        if (llmScope != null) llmScope.close();
                        if (llmSpan != null) llmSpan.end();
                    }
    
                    // 模型返回后、写 AiMessage 前先检查 deadline，再检查用户取消。
                    if (session.deadline().isExpired(System.currentTimeMillis())) {
                        state.markTimeout();
                        return AgentResult.error(AgentResult.ErrorType.TIMEOUT,
                                "request_deadline_exceeded_after_model_call");
                    }
                    if (cancelCheck.isCancelled()) {
                        state.markError();
                        return AgentResult.error(AgentResult.ErrorType.CANCELLED,
                                "cancelled_after_model_call");
                    }
    
                    // LLM 请求了工具调用 → ToolSafetyEngine → ToolExecutor → 回填
                    if (aiMessage.hasToolExecutionRequests()) {
                        // ToolResult 必须紧跟其声明者，因此 ToolCall AiMessage 在执行工具前持久化。
                        chatMemory.add(aiMessage);
                        List<ToolExecutionRequest> toolReqs = aiMessage.toolExecutionRequests();
                        // 必须先依据本轮模型可见规格授权，拒绝批次不会进入 Safety 或 Dispatcher。
                        var authorization = toolExecutionAuthorizer.authorize(toolReqs, requestTools,
                                session.knowledgeIntentDecision());
                        if (!authorization.authorized()) {
                            for (ToolExecutionRequest req : toolReqs) {
                                chatMemory.add(new ToolExecutionResultMessage(req.id(), req.name(),
                                        "[NOT_EXECUTED] " + authorization.reasonCode()));
                            }
                            state.markError();
                            return AgentResult.error(AgentResult.ErrorType.TOOL_EXECUTION_FAILED,
                                    authorization.reasonCode());
                        }
                        if (session.visionIntentDecision().requirement() == VisionRequirement.REQUIRED
                                && (toolReqs.size() != 1
                                || !"front_camera_interaction".equals(toolReqs.get(0).name()))) {
                            for (ToolExecutionRequest req : toolReqs) {
                                chatMemory.add(new ToolExecutionResultMessage(req.id(), req.name(),
                                        "[NOT_EXECUTED] required front vision tool only"));
                            }
                            state.markError();
                            return AgentResult.error(AgentResult.ErrorType.TOOL_EXECUTION_FAILED,
                                    "VISION_EVIDENCE_UNAVAILABLE:wrong_or_multiple_tool");
                        }
                        if (session.deadline().isExpired(System.currentTimeMillis())) {
                            state.markTimeout();
                            return AgentResult.error(AgentResult.ErrorType.TIMEOUT,
                                    "request_deadline_exceeded_before_tool_execution");
                        }
                        if (cancelCheck.isCancelled()) {
                            state.markError();
                            return AgentResult.error(AgentResult.ErrorType.CANCELLED,
                                    "cancelled_before_tool_execution");
                        }
                        // 整批先完成一次 Safety 预检，确认型动作出现时保证零部分执行。
                        List<SafetyDecision> safetyDecisions = new ArrayList<>(toolReqs.size());
                        boolean confirmationRequired = false;
                        for (ToolExecutionRequest req : toolReqs) {
                            Span safetySpan = trace != null ? trace.startToolSafetyCheck() : null;
                            SafetyDecision decision = SafetyDecision.allow();
                            try {
                                decision = toolSafetyEngine.check(req);
                                safetyDecisions.add(decision);
                                confirmationRequired |= decision.requiresConfirmation();
                            } finally {
                                if (trace != null) trace.finishToolSafetyCheck(safetySpan, decision);
                            }
                        }

                        if (confirmationRequired) {
                            String confirmationText;
                            if (toolReqs.size() != 1) {
                                confirmationText = "本次包含多个工具操作且其中有高风险动作，"
                                        + "为避免部分执行，请拆分后重新请求。";
                            } else if (confirmationCoordinator == null) {
                                confirmationText = "该高风险操作需要文本二次确认，但当前确认通道不可用，本次未执行。";
                            } else if (session.deadline().isExpired(System.currentTimeMillis())
                                    || cancelCheck.isCancelled()) {
                                state.markError();
                                return AgentResult.error(AgentResult.ErrorType.CANCELLED,
                                        "cancelled_before_pending_confirmation");
                            } else {
                                ToolExecutionRequest pendingRequest = toolReqs.get(0);
                                SafetyDecision pendingDecision = safetyDecisions.get(0);
                                confirmationText = confirmationCoordinator.createPending(
                                        session.sessionId(), session.requestId(), pendingRequest,
                                        pendingDecision.reason());
                                PendingToolAction pendingAction =
                                        confirmationCoordinator.pendingAction();
                                if (pendingAction != null && session.traceContext() != null
                                        && session.traceContext().session() != null) {
                                    session.traceContext().session().setAttribute(
                                            TraceAttributeKeys.CONFIRMATION_ID,
                                            pendingAction.confirmationId());
                                    session.traceContext().session().setAttribute(
                                            TraceAttributeKeys.CONFIRMATION_STATUS, "PENDING");
                                }
                                if (session.deadline().isExpired(System.currentTimeMillis())
                                        || cancelCheck.isCancelled()) {
                                    confirmationCoordinator.cancelPendingForOriginalRequest(
                                            session.requestId());
                                    state.markError();
                                    return AgentResult.error(AgentResult.ErrorType.CANCELLED,
                                            "cancelled_after_pending_confirmation");
                                }
                            }

                            for (ToolExecutionRequest req : toolReqs) {
                                chatMemory.add(new ToolExecutionResultMessage(
                                        req.id(), req.name(),
                                        "[CONFIRMATION_REQUIRED] " + confirmationText));
                            }
                            chatMemory.add(AiMessage.from(confirmationText));
                            state.markCompleted();
                            return AgentResult.success(confirmationText, i + 1, 0L, List.of());
                        }

                        for (int toolIdx = 0; toolIdx < toolReqs.size(); toolIdx++) {
                            ToolExecutionRequest req = toolReqs.get(toolIdx);
                            Span toolSpan = trace != null
                                    ? trace.startTool(req, i,
                                            io.opentelemetry.context.Context.current()) : null;
                            Scope toolScope = toolSpan != null ? toolSpan.makeCurrent() : null;
                            try {
                                if (session.deadline().isExpired(System.currentTimeMillis())) {
                                    // 为未执行 Tool 闭合 ToolExchange，但绝不进入 Safety 或 dispatch。
                                    for (int remaining = toolIdx; remaining < toolReqs.size(); remaining++) {
                                        ToolExecutionRequest unexReq = toolReqs.get(remaining);
                                        chatMemory.add(new ToolExecutionResultMessage(
                                                unexReq.id(), unexReq.name(),
                                                "[TIMEOUT] request deadline exceeded"));
                                    }
                                    state.markTimeout();
                                    return AgentResult.error(AgentResult.ErrorType.TIMEOUT,
                                            "request_deadline_exceeded_during_tool_execution");
                                }
                                if (cancelCheck.isCancelled()) {
                                    // 为所有未执行工具写入 cancelled ToolResult，保证 ToolExchange 闭合
                                    for (int remaining = toolIdx; remaining < toolReqs.size(); remaining++) {
                                        ToolExecutionRequest unexReq = toolReqs.get(remaining);
                                        chatMemory.add(new ToolExecutionResultMessage(
                                                unexReq.id(), unexReq.name(),
                                                "[CANCELLED] tool execution cancelled"));
                                    }
                                    state.markError();
                                    return AgentResult.error(AgentResult.ErrorType.CANCELLED,
                                            "cancelled_during_tool_execution");
                                }
                                SafetyDecision decision = safetyDecisions.get(toolIdx);
    
                                String toolResult;
                                ToolDispatchOutcome dispatchOutcome = null;
                                if (decision.isDenied()) {
                                    toolResult = toolSafetyEngine.formatDenyResult(decision);
                                } else {
                                    if (session.deadline().isExpired(System.currentTimeMillis())) {
                                        for (int remaining = toolIdx; remaining < toolReqs.size(); remaining++) {
                                            ToolExecutionRequest unexReq = toolReqs.get(remaining);
                                            chatMemory.add(new ToolExecutionResultMessage(
                                                    unexReq.id(), unexReq.name(),
                                                    "[TIMEOUT] request deadline exceeded"));
                                        }
                                        state.markTimeout();
                                        return AgentResult.error(AgentResult.ErrorType.TIMEOUT,
                                                "request_deadline_exceeded_before_tool_dispatch");
                                    }
                                    // Stage 2: tool.dispatch（接入真实 DispatchDiagnostics）
                                    Span dispatchSpan = trace != null ? trace.startToolDispatch(req.name()) : null;
                                    long dispatchStartMs = System.currentTimeMillis();
                                    try {
                                        dispatchOutcome = config.toolRegistry() != null
                                                ? config.toolRegistry().dispatchWithOutcome(req)
                                                : ToolDispatchOutcome.failure(false, false,
                                                "工具执行失败: TEXT ToolRegistry 未配置",
                                                "INVALID_CONFIG", "TEXT ToolRegistry is required",
                                                null, null);
                                        toolResult = dispatchOutcome.resultText();
                                        if (trace != null) {
                                            trace.finishToolDispatch(dispatchSpan,
                                                    dispatchOutcome.dispatchSuccess(),
                                                    dispatchOutcome.targetClass(), dispatchOutcome.targetMethod(),
                                                    System.currentTimeMillis() - dispatchStartMs,
                                                    dispatchOutcome.argumentParseSuccess(),
                                                    dispatchOutcome.invokeSuccess());
                                        }
                                    } catch (Exception dispatchEx) {
                                        if (trace != null) {
                                            trace.finishToolDispatch(dispatchSpan, false, null, null,
                                                    System.currentTimeMillis() - dispatchStartMs,
                                                    false, false);
                                        }
                                        throw dispatchEx;
                                    }
                                }

                                // Stage 3: tool.result_writeback（分步记录真实结果）
                                Span writebackSpan = trace != null ? trace.startToolWriteback() : null;
                                boolean memoryWriteSuccess = false;
                                boolean loopCtxWriteSuccess = false;
                                try {
                                    String persistedToolResult = toolResult != null ? toolResult : "{}";
                                    if ("searchVehicleKnowledge".equals(req.name())) {
                                        // 完整结果仅供当前 Request 后续迭代使用；历史会话只保存受控短投影。
                                        session.knowledgeRequestState().turnBuffer().put(req.id(), persistedToolResult);
                                        persistedToolResult = knowledgeMemoryPolicy.compact(persistedToolResult);
                                    }
                                    chatMemory.add(new ToolExecutionResultMessage(req.id(), req.name(), persistedToolResult));
                                    memoryWriteSuccess = true;
                                    if (trace != null) {
                                        trace.finishTool(toolSpan, decision, dispatchOutcome);
                                    }
                                    loopCtx.addToolResult(req.name(), req.arguments(),
                                            toolResult, decision);
                                    loopCtxWriteSuccess = true;
                                    if ("front_camera_interaction".equals(req.name())) {
                                        com.hirain.aiagent.tools.vision.FrontViewVisionResult visionResult =
                                                VisionResultParser.parse(toolResult);
                                        if (visionResult == null || !visionResult.success
                                                || !"SUCCESS".equals(visionResult.status)) {
                                            state.markError();
                                            return AgentResult.error(AgentResult.ErrorType.TOOL_EXECUTION_FAILED,
                                                    "VISION_EVIDENCE_UNAVAILABLE:" +
                                                            (visionResult != null ? visionResult.status : "invalid_result"));
                                        }
                                        visionEvidenceSucceeded = true;
                                    }
                                } finally {
                                    if (trace != null) {
                                        trace.finishToolWriteback(writebackSpan,
                                                memoryWriteSuccess && loopCtxWriteSuccess);
                                    }
                                }
                            } catch (MemoryPersistenceException e) {
                                throw e;
                            } catch (Exception e) {
                                if (toolScope != null) {
                                    toolScope.close();
                                    toolScope = null;
                                }
                                if (trace != null) trace.finishTool(toolSpan, "error", null);
                                loopCtx.addToolResult(req.name(), req.arguments(),
                                        "error: " + e.getMessage(), null);
                                chatMemory.add(new ToolExecutionResultMessage(
                                        req.id(), req.name(),
                                        "error: " + e.getMessage()));
                            } finally {
                                if (toolScope != null) toolScope.close();
                                if (toolSpan != null) toolSpan.end();
                            }
                        }
    
                        if (session.deadline().isExpired(System.currentTimeMillis())) {
                            state.markTimeout();
                            return AgentResult.error(AgentResult.ErrorType.TIMEOUT,
                                    "request_deadline_exceeded_after_tool_execution");
                        }

                        // 工具循环因取消中断 → 返回
                        if (cancelCheck.isCancelled()) {
                            state.markError();
                            return AgentResult.error(AgentResult.ErrorType.CANCELLED,
                                    "cancelled_during_tool_execution");
                        }
    
                        continue;
                    }
    
                    if (session.visionIntentDecision().requirement() == VisionRequirement.REQUIRED
                            && !visionEvidenceSucceeded) {
                        if (requiredNoToolRetryCount++ == 0) continue;
                        state.markError();
                        return AgentResult.error(AgentResult.ErrorType.TOOL_EXECUTION_FAILED,
                                "VISION_EVIDENCE_UNAVAILABLE:VISION_TOOL_NOT_CALLED");
                    }
                    if (knowledgeLoopPolicy.beforeModel(session.knowledgeIntentDecision(),session.knowledgeRequestState()).toolRequired()) {
                        if (requiredNoToolRetryCount++ == 0) continue;
                        state.markError();
                        return AgentResult.error(AgentResult.ErrorType.TOOL_EXECUTION_FAILED,"KNOWLEDGE_TOOL_NOT_CALLED");
                    }

                    // LLM 返回文本 → PostProcessor → Terminator → ResultCollector
                    String output = aiMessage.text();
    
                    if (config.postProcessors() != null) {
                        for (PostProcessor pp : config.postProcessors()) {
                            output = pp.process(output, loopCtx);
                        }
                    }
    
                    if (session.deadline().isExpired(System.currentTimeMillis())) {
                        state.markTimeout();
                        return AgentResult.error(AgentResult.ErrorType.TIMEOUT,
                                "request_deadline_exceeded_before_result");
                    }
                    if (cancelCheck.isCancelled()) {
                        state.markError();
                        return AgentResult.error(AgentResult.ErrorType.CANCELLED,
                                "cancelled_before_result");
                    }
    
                    AiMessage processedMsg = AiMessage.from(output);
                    if (session.knowledgeIntentDecision().requirement()
                            == com.hirain.aiagent.rag.policy.KnowledgeRequirement.REQUIRED) {
                        var citation = citationGuard.validate(output,
                                session.knowledgeRequestState().citationEvidenceMap(), true);
                        if (!citation.valid()) {
                            state.markError();
                            return AgentResult.error(AgentResult.ErrorType.TOOL_EXECUTION_FAILED,
                                    citation.reasonCode());
                        }
                        output = citation.output();
                    }
                    // 普通文本只写入 PostProcessor 后的最终版本，确保外部回复与 Memory 一致。
                    chatMemory.add(processedMsg);
                    if (config.terminator().shouldStop(loopCtx,
                            ChatResponse.builder().aiMessage(processedMsg).build())) {
                        if (memoryGateway != null
                                && session.visionIntentDecision().requirement()
                                == VisionRequirement.NONE
                                && session.knowledgeIntentDecision().requirement()
                                != com.hirain.aiagent.rag.policy.KnowledgeRequirement.REQUIRED) {
                            memoryGateway.extractTurnMemory(userId, sessionId,
                                    session.userInput(), output, trace);
                        }
                        state.markCompleted();
                        return config.resultCollector().collect(
                                ChatResponse.builder().aiMessage(processedMsg).build(), loopCtx);
                    }
    
                    // 不终止 → 继续下一轮迭代
                    continue;
                } finally {
                    if (iterScope != null) iterScope.close();
                    if (iterSpan != null) iterSpan.end();
                }
            }

            state.markError();
            return AgentResult.error(AgentResult.ErrorType.MAX_ITERATIONS_REACHED,
                    "Max iterations reached: " + config.maxIterations());
        } catch (Exception e) {
            state.markError();
            android.util.Log.e(TAG, "Text agent loop failed", e);
            return AgentResult.error(AgentResult.ErrorType.MODEL_CALL_FAILED, e.getMessage());
        }
    }

    // ── 内部方法 ──

    static TraceSession extractTraceSession(java.util.Map<String, Object> extraContext) {
        if (extraContext == null) return null;
        Object value = extraContext.get(TraceContext.TRACE_CONTEXT_KEY);
        if (!(value instanceof TraceContext traceContext)) return null;
        if (!traceContext.isActive()) return null;
        return traceContext.session();
    }

    /** 获取工具 dispatch target 信息供 trace 记录（通过 config.toolRegistry 可选获取）。 */
    private String getDispatchTarget(String toolName) {
        if (config.toolRegistry() == null) return null;
        return config.toolRegistry().dispatchTargetInfo(toolName);
    }

    /** 获取工具目标类名（trace 用）。 */
    private String getTargetClass(String toolName) {
        if (config.toolRegistry() == null) return null;
        return config.toolRegistry().targetClassName(toolName);
    }

    /** 获取工具目标方法名（trace 用）。 */
    private String getTargetMethod(String toolName) {
        if (config.toolRegistry() == null) return null;
        return config.toolRegistry().targetMethodName(toolName);
    }

    /** 将 ContextAssemblyResult.errorCode() 映射到 AgentResult.ErrorType。 */
    static AgentResult.ErrorType mapAssemblyError(ContextAssemblyResult assemblyResult) {
        if (assemblyResult == null) return AgentResult.ErrorType.CONTEXT_BUILD_FAILED;
        ContextErrorCode code = assemblyResult.errorCode();
        if (code == null) return AgentResult.ErrorType.CONTEXT_BUILD_FAILED;
        switch (code) {
            case CONTEXT_CANCELLED:         return AgentResult.ErrorType.CANCELLED;
            case MESSAGE_SEQUENCE_INVALID:  return AgentResult.ErrorType.MESSAGE_SEQUENCE_INVALID;
            case TOOL_SPEC_RESOLUTION_FAILED: return AgentResult.ErrorType.TOOL_SPEC_RESOLUTION_FAILED;
            case REQUIRED_PROVIDER_FAILED:  return AgentResult.ErrorType.REQUIRED_PROVIDER_FAILED;
            case CONTEXT_BUDGET_EXCEEDED:   return AgentResult.ErrorType.CONTEXT_BUDGET_EXCEEDED;
            case MEMORY_COMPACTION_FAILED:  return AgentResult.ErrorType.MEMORY_COMPACTION_FAILED;
            default:                        return AgentResult.ErrorType.CONTEXT_BUILD_FAILED;
        }
    }
}
