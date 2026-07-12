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
import com.hirain.aiagent.runtime.RequestSession;
import com.hirain.aiagent.trace.AgentTraceRecorder;
import com.hirain.aiagent.trace.TraceContext;
import com.hirain.aiagent.trace.TraceSession;
import com.hirain.aiagent.core.component.ModelCaller;
import com.hirain.aiagent.core.component.PostProcessor;
import com.hirain.aiagent.core.component.SafetyGuard;
import com.hirain.aiagent.core.component.ToolExecutor;

import java.util.List;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.request.ChatRequest;
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
    private final AgentLoopState state = new AgentLoopState();

    public TextAgentLoopOrchestrator(AgentConfig config,
                                     ContextMemoryGateway memoryGateway,
                                     ContextAssemblyGateway contextAssemblyGateway) {
        if (config == null) throw new IllegalArgumentException("config must not be null");
        if (memoryGateway == null) throw new IllegalArgumentException("memoryGateway must not be null");
        if (contextAssemblyGateway == null) throw new IllegalArgumentException("contextAssemblyGateway must not be null");
        this.config = config;
        this.memoryGateway = memoryGateway;
        this.contextAssemblyGateway = contextAssemblyGateway;
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
        ChatMemory chatMemory = memoryGateway.chatMemoryForSession(
                sessionId, config.maxMemoryMessages());
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

            for (int i = 0; i < config.maxIterations(); i++) {
                loopCtx.setIteration(i);
                state.setIteration(i);

                // 创建 agent.iteration 容器 span
                Span iterSpan = trace != null
                        ? trace.startIteration(i, io.opentelemetry.context.Context.current())
                        : null;
                Scope iterScope = iterSpan != null ? iterSpan.makeCurrent() : null;
                try {

                    // 超时检查
                    if (System.currentTimeMillis() - loopCtx.startTimeMs() > config.timeout().toMillis()) {
                        state.markTimeout();
                        return AgentResult.error(AgentResult.ErrorType.TIMEOUT, "Agent loop timed out");
                    }
    
                    // Context Assembly
                    ContextCancelChecker cancelCheck = () ->
                            prepareResult.cancelChecker() != null
                                    && prepareResult.cancelChecker().isCancelled();
                    ContextAssemblyRequest assemblyReq = new ContextAssemblyRequest(
                            prepareResult.frame(), i, budgetPolicy,
                            cancelCheck, false, session);
                    ContextAssemblyResult assemblyResult = contextAssemblyGateway.assemble(assemblyReq);
    
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
    
                    // 模型调用前检查取消
                    if (cancelCheck.isCancelled()) {
                        state.markError();
                        return AgentResult.error(AgentResult.ErrorType.CANCELLED, "cancelled_before_model_call");
                    }
    
                    if (!currentUserCommitted && currentMsg != null) {
                        chatMemory.add(currentMsg);
                        currentUserCommitted = true;
                    }
    
                    // ModelCaller → LLM 调用
                    ChatRequest request = ChatRequest.builder()
                            .messages(requestMessages)
                            .toolSpecifications(requestTools)
                            .build();
    
                    Span llmSpan = trace != null
                            ? trace.startLlmCall(config.modelName(), i, requestMessages.size(),
                                    io.opentelemetry.context.Context.current())
                            : null;
                    if (trace != null) {
                        trace.recordLlmRequest(llmSpan, config.modelName(), i,
                                requestMessages, requestTools);
                    }
                    Scope llmScope = llmSpan != null ? llmSpan.makeCurrent() : null;
                    ChatResponse response;
                    AiMessage aiMessage;
                    try {
                        response = config.modelCaller().call(request);
                        aiMessage = response.aiMessage();
                        if (llmSpan != null) trace.enrichLlmResponse(llmSpan, response);
                    } catch (Exception e) {
                        if (trace != null) trace.recordException(llmSpan, e);
                        throw e;
                    } finally {
                        if (llmScope != null) llmScope.close();
                        if (llmSpan != null) llmSpan.end();
                    }
    
                    // 模型返回后、写 AiMessage 前检查取消
                    if (cancelCheck.isCancelled()) {
                        state.markError();
                        return AgentResult.error(AgentResult.ErrorType.CANCELLED,
                                "cancelled_after_model_call");
                    }
    
                    chatMemory.add(aiMessage);
    
                    // LLM 请求了工具调用 → SafetyGuard → ToolExecutor → 回填
                    if (aiMessage.hasToolExecutionRequests()) {
                        List<ToolExecutionRequest> toolReqs = aiMessage.toolExecutionRequests();
                        if (cancelCheck.isCancelled()) {
                            state.markError();
                            return AgentResult.error(AgentResult.ErrorType.CANCELLED,
                                    "cancelled_before_tool_execution");
                        }
                        int vetoCount = 0;
    
                        for (int toolIdx = 0; toolIdx < toolReqs.size(); toolIdx++) {
                            ToolExecutionRequest req = toolReqs.get(toolIdx);
                            Span toolSpan = trace != null
                                    ? trace.startTool(req, i,
                                            io.opentelemetry.context.Context.current()) : null;
                            Scope toolScope = toolSpan != null ? toolSpan.makeCurrent() : null;
                            try {
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
                                // Stage 1: tool.safety_check
                                Span safetySpan = trace != null ? trace.startToolSafetyCheck() : null;
                                SafetyVerdict verdict = SafetyVerdict.allow();
                                String vetoReason = null;
                                try {
                                    for (SafetyGuard guard : config.safetyGuards()) {
                                        verdict = guard.evaluate(req, loopCtx);
                                        if (verdict.isVetoed()) {
                                            vetoReason = verdict.reason();
                                            break;
                                        }
                                    }
                                } finally {
                                    if (trace != null) {
                                        trace.finishToolSafetyCheck(safetySpan,
                                                config.safetyGuards().size(), verdict.isVetoed(), vetoReason);
                                    }
                                }
    
                                String toolResult;
                                if (verdict.isVetoed()) {
                                    loopCtx.setLastSafetyVeto(verdict);
                                    toolResult = "[SAFETY VETO] " + verdict.reason();
                                    vetoCount++;
                                } else {
                                    // Stage 2: tool.dispatch
                                    Span dispatchSpan = trace != null ? trace.startToolDispatch(req.name()) : null;
                                    long dispatchStartMs = System.currentTimeMillis();
                                    String targetClass = getTargetClass(req.name());
                                    String targetMethod = getTargetMethod(req.name());
                                    try {
                                        toolResult = config.toolExecutor().execute(req);
                                        if (trace != null) {
                                            trace.finishToolDispatch(dispatchSpan, true, targetClass, targetMethod,
                                                    System.currentTimeMillis() - dispatchStartMs,
                                                    true, true);
                                        }
                                    } catch (Exception dispatchEx) {
                                        if (trace != null) {
                                            trace.finishToolDispatch(dispatchSpan, false, targetClass, targetMethod,
                                                    System.currentTimeMillis() - dispatchStartMs,
                                                    false, false);
                                        }
                                        throw dispatchEx;
                                    }
                                }

                                // Stage 3: tool.result_writeback
                                Span writebackSpan = trace != null ? trace.startToolWriteback() : null;
                                try {
                                    chatMemory.add(new ToolExecutionResultMessage(
                                            req.id(), req.name(),
                                            toolResult != null ? toolResult : "{}"));
                                    if (trace != null) {
                                        trace.finishTool(toolSpan, toolResult, verdict);
                                    }
                                    loopCtx.addToolResult(req.name(), req.arguments(),
                                            toolResult, verdict);
                                } finally {
                                    if (trace != null) trace.finishToolWriteback(writebackSpan, true);
                                }
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
    
                        // 工具循环因取消中断 → 返回
                        if (cancelCheck.isCancelled()) {
                            state.markError();
                            return AgentResult.error(AgentResult.ErrorType.CANCELLED,
                                    "cancelled_during_tool_execution");
                        }
    
                        // 全部工具均被安全否决 → 运行 PostProcessor，经 ResultCollector 返回
                        if (vetoCount == toolReqs.size() && vetoCount > 0) {
                            String output = "安全原因已阻止所有工具调用。";
                            if (config.postProcessors() != null) {
                                for (PostProcessor pp : config.postProcessors()) {
                                    output = pp.process(output, loopCtx);
                                }
                            }
                            if (memoryGateway != null) {
                                memoryGateway.extractTurnMemory(userId, sessionId,
                                        session.userInput(), output, trace);
                            }
                            state.markCompleted();
                            return config.resultCollector().collect(
                                    ChatResponse.builder()
                                            .aiMessage(AiMessage.from(output))
                                            .build(), loopCtx);
                        }
    
                        continue;
                    }
    
                    // LLM 返回文本 → PostProcessor → Terminator → ResultCollector
                    String output = aiMessage.text();
    
                    if (config.postProcessors() != null) {
                        for (PostProcessor pp : config.postProcessors()) {
                            output = pp.process(output, loopCtx);
                        }
                    }
    
                    if (cancelCheck.isCancelled()) {
                        state.markError();
                        return AgentResult.error(AgentResult.ErrorType.CANCELLED,
                                "cancelled_before_result");
                    }
    
                    AiMessage processedMsg = AiMessage.from(output);
                    if (config.terminator().shouldStop(loopCtx,
                            ChatResponse.builder().aiMessage(processedMsg).build())) {
                        if (memoryGateway != null) {
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
