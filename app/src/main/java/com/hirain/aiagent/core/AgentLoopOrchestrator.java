package com.hirain.aiagent.core;

import android.content.Context;
import android.util.Log;

import com.hirain.aiagent.context.ContextAssemblyGateway;
import com.hirain.aiagent.context.ContextAssemblyRequest;
import com.hirain.aiagent.context.ContextAssemblyResult;
import com.hirain.aiagent.context.ContextBudgetPolicy;
import com.hirain.aiagent.context.ContextPrepareResult;
import com.hirain.aiagent.context.ContextAssemblyDebugInfo;
import com.hirain.aiagent.context.ContextCancelChecker;
import com.hirain.aiagent.context.ContextErrorCode;
import com.hirain.aiagent.context.ModelContextWindowProfiles;
import com.hirain.aiagent.runtime.RequestSession;
import com.hirain.aiagent.core.component.LoopTerminator;
import com.hirain.aiagent.memory.ContextMemoryGateway;
import com.hirain.aiagent.memory.MemoryOrchestrator;
import com.hirain.aiagent.memory.SpeakerMessageFormatter;
import com.hirain.aiagent.trace.AgentTraceRecorder;
import com.hirain.aiagent.trace.TraceContext;
import com.hirain.aiagent.trace.TraceSession;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import com.hirain.aiagent.core.component.ModelCaller;
import com.hirain.aiagent.core.component.PostProcessor;
import com.hirain.aiagent.core.component.PreProcessor;
import com.hirain.aiagent.core.component.ResultCollector;
import com.hirain.aiagent.core.component.SafetyGuard;
import com.hirain.aiagent.core.component.ToolExecutor;
import com.hirain.aiagent.prompt.PromptManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import langchain4j.chat_memory_sqlite.PersistentChatMemorySqlite;

import static com.hirain.aiagent.core.AgentResult.ErrorType;

/**
 * Agent 主循环引擎 — 迭代式 Tool Calling Loop。
 * <p>
 * 这是项目中唯一的 Agent 执行入口。所有"人格"（chat / scene / vision_qa）
 * 通过 {@link AgentConfig} 配置驱动，无需为不同场景创建独立的 Agent 类。
 * <p>
 * 核心流程：
 * <pre>
 *   execute(userInput, extraContext)
 *     → 刷新 SystemPrompt（含长期记忆）
 *     → 写入用户消息到记忆
 *     → for i in 0..maxIterations:
 *         ① PreProcessor 链 → 生成临时上下文消息
 *         ② ModelCaller → LLM 调用
 *         ③ LLM 返回 ToolCall → SafetyGuard → ToolExecutor → 回填结果 → continue
 *         ④ LLM 返回文本 → PostProcessor → 记忆提取 → LoopTerminator → ResultCollector → return
 *     → max iterations → AgentResult.error(MAX_ITERATIONS)
 * </pre>
 */
public class AgentLoopOrchestrator {

    private static final String TAG = "AgentLoopOrchestrator";

    private final AgentConfig config;
    private final PromptManager promptManager;
    private final MemoryOrchestrator memoryOrchestrator;
    private final ChatMemory fallbackChatMemory;
    private final AgentLoopState state = new AgentLoopState();
    private final List<ToolSpecification> effectiveToolSpecs;
    private final ContextAssemblyGateway contextAssemblyGateway;

    /**
     * @param config         Agent 配置
     * @param context        Android Context（用于 SQLite 记忆持久化）
     * @param promptManager  Prompt 管理器
     * @param memoryOrchestrator 记忆协调器
     * @param allToolSpecs   全部可用工具规格（config.toolSubset 为 null 时使用）
     */
    public AgentLoopOrchestrator(AgentConfig config, Context context,
                                 PromptManager promptManager,
                                 MemoryOrchestrator memoryOrchestrator,
                                 List<ToolSpecification> allToolSpecs) {
        this(config, context, promptManager, memoryOrchestrator, allToolSpecs, null);
    }

    /**
     * 带 ContextAssemblyGateway 的构造器。
     * Phase 3 影子装配使用：gateway 不为 null 时，每轮模型调用前执行 shadow assemble。
     * @deprecated TEXT 路径已由 execute(RequestSession, ContextPrepareResult) 接管，
     * PromptManager 和 allToolSpecs 参数仅 SCENE/VL 路径仍需使用。
     */
    @Deprecated
    public AgentLoopOrchestrator(AgentConfig config, Context context,
                                 PromptManager promptManager,
                                 MemoryOrchestrator memoryOrchestrator,
                                 List<ToolSpecification> allToolSpecs,
                                 ContextAssemblyGateway contextAssemblyGateway) {
        this.config = config;
        this.promptManager = promptManager;
        this.memoryOrchestrator = memoryOrchestrator;
        this.fallbackChatMemory = createChatMemory(context);
        this.effectiveToolSpecs = config.toolSubset() != null
                ? config.toolSubset() : allToolSpecs;
        this.contextAssemblyGateway = contextAssemblyGateway;
    }

    // ── 公开接口 ──

    /**
     * 执行 Agent 循环。
     *
     * @param userInput    用户文本输入（可为空，如场景触发）
     * @param extraContext 额外上下文（scene、image_bytes 等）
     * @return 结构化结果
     */
    public AgentResult execute(String userInput, Map<String, Object> extraContext) {
        if (!state.tryStart()) {
            return AgentResult.error(ErrorType.INVALID_CONFIG, "Agent is busy");
        }

        // 获取用户 ID
        String userId = extraContext != null
                ? (String) extraContext.getOrDefault("user_id", "default_user")
                : "default_user";

        // 获取 persona ID（对话性格，影响 system prompt 模板选择）
        String personaId = extraContext != null
                ? (String) extraContext.getOrDefault("persona_id", "chat")
                : "chat";

        AgentLoopContext ctx = new AgentLoopContext(userInput, config.personaId(), extraContext);
        Log.d(TAG, "execute: persona=" + config.personaId() + " maxIter=" + config.maxIterations());
        Log.d(TAG, "execute: personaId from context=[" + personaId + "] template="
                + com.hirain.aiagent.prompt.PromptConstants.textPersonaTemplateName(personaId));

        // 提取 TraceSession（用于创建 LLM 和工具子 span）
        TraceSession traceSession = extractTraceSession(extraContext);
        AgentTraceRecorder trace = traceSession != null
                ? new AgentTraceRecorder(traceSession)
                : null;

        try {
            // 根据 memory policy 决定 ChatMemory 来源
            // PERSISTENT：必须使用 session-scoped ChatMemory（要求 resolved sessionId）
            // EPHEMERAL / NONE：使用构造时的 fallbackChatMemory
            ChatMemory chatMemory;
            String sessionId = null;
            boolean persistentMode = config.memoryPolicy() == AgentConfig.MemoryPolicy.PERSISTENT;
            if (persistentMode) {
                sessionId = extraContext != null
                        ? (String) extraContext.get("session_id")
                        : null;
                if (sessionId == null || sessionId.trim().isEmpty()) {
                    state.markError();
                    return AgentResult.error(ErrorType.INVALID_CONFIG,
                            "resolved sessionId is required before AgentLoop execution");
                }
                chatMemory = memoryOrchestrator.chatMemoryForSession(
                        sessionId, config.maxMemoryMessages());
                Log.d(TAG, "execute: sessionId=" + sessionId
                        + " chatMemory messageCount=" + chatMemory.messages().size());
            } else {
                chatMemory = fallbackChatMemory;
            }

            // 写入用户消息到短期记忆（PERSISTENT 模式带 speaker 标记）
            if (userInput != null && !userInput.isEmpty()) {
                chatMemory.add(UserMessage.from(
                        persistentMode
                                ? SpeakerMessageFormatter.formatUserMessage(userId, userInput)
                                : userInput));
            }

            for (int i = 0; i < config.maxIterations(); i++) {
                ctx.setIteration(i);
                state.setIteration(i);

                // 超时检查
                if (System.currentTimeMillis() - ctx.startTimeMs() > config.timeout().toMillis()) {
                    state.markTimeout();
                    return AgentResult.error(ErrorType.TIMEOUT, "Agent loop timed out");
                }

                // ① PreProcessor 链 → 生成临时上下文消息
                List<ChatMessage> transientMessages = new ArrayList<>();
                for (PreProcessor pp : config.preProcessors()) {
                    List<ChatMessage> msgs = pp.prepare(ctx);
                    if (msgs != null) transientMessages.addAll(msgs);
                }

                // 组装完整请求：transient SystemMessage（含长期记忆）+ 临时消息 + 会话历史
                List<ChatMessage> allMessages = new ArrayList<>();
                allMessages.add(buildSystemPromptMessage(userId, personaId));
                allMessages.addAll(transientMessages);
                allMessages.addAll(chatMemory.messages());

                Span promptSpan = trace != null
                        ? trace.startPromptAssembly(
                                config.personaId(),
                                i,
                                transientMessages,
                                chatMemory.messages(),
                                allMessages.size(),
                                effectiveToolSpecs)
                        : null;
                if (promptSpan != null) promptSpan.end();

                // Phase 6: 影子装配已删除，Context 独占链路通过新 execute() 方法运行


                // ② ModelCaller → LLM 调用
                ChatRequest request = ChatRequest.builder()
                        .messages(allMessages)
                        .toolSpecifications(effectiveToolSpecs)
                        .build();

                Span llmSpan = trace != null
                        ? trace.startLlmCall(config.modelName(), i, allMessages.size())
                        : null;
                Scope llmScope = llmSpan != null ? llmSpan.makeCurrent() : null;
                ChatResponse response;
                AiMessage aiMessage;
                try {
                    response = config.modelCaller().call(request);
                    aiMessage = response.aiMessage();

                    // 补充 LLM 输出属性到 span
                    if (llmSpan != null) {
                        trace.enrichLlmResponse(llmSpan, response);
                    }
                } catch (Exception e) {
                    if (trace != null) trace.recordException(llmSpan, e);
                    throw e;
                } finally {
                    if (llmScope != null) llmScope.close();
                    if (llmSpan != null) llmSpan.end();
                }

                chatMemory.add(aiMessage);

                // ③ LLM 请求了工具调用
                if (aiMessage.hasToolExecutionRequests()) {
                    List<ToolExecutionRequest> toolReqs = aiMessage.toolExecutionRequests();
                    int vetoCount = 0;

                    for (ToolExecutionRequest toolReq : toolReqs) {
                        // 工具执行子 span
                        Span toolSpan = trace != null
                                ? trace.startTool(toolReq, i)
                                : null;
                        Scope toolScope = toolSpan != null ? toolSpan.makeCurrent() : null;

                        SafetyVerdict verdict = SafetyVerdict.allow();
                        String result = null;
                        try {
                            // 安全审查
                            for (SafetyGuard guard : config.safetyGuards()) {
                                verdict = guard.evaluate(toolReq, ctx);
                                if (verdict.isVetoed()) break;
                            }

                            if (verdict.isVetoed()) {
                                ctx.setLastSafetyVeto(verdict);
                                result = "[SAFETY VETO] " + verdict.reason();
                                vetoCount++;
                            } else {
                                result = config.toolExecutor().execute(toolReq);
                            }
                            if (trace != null) trace.finishTool(toolSpan, result, verdict);
                            ctx.addToolResult(toolReq.name(), toolReq.arguments(), result, verdict);
                            chatMemory.add(ToolExecutionResultMessage.from(toolReq, result));
                            Log.d(TAG, "Tool[" + toolReq.name() + "] -> " + result);
                        } catch (Exception e) {
                            if (trace != null) trace.recordException(toolSpan, e);
                            throw e;
                        } finally {
                            if (toolScope != null) toolScope.close();
                            if (toolSpan != null) toolSpan.end();
                        }
                    }

                    // 所有工具都被安全否决 → 不再继续循环，直接返回
                    if (vetoCount == toolReqs.size() && vetoCount > 0) {
                        String output = "安全原因已阻止所有工具调用。";
                        for (PostProcessor pp : config.postProcessors()) {
                            output = pp.process(output, ctx);
                        }
                        if (memoryOrchestrator != null && userInput != null) {
                            if (persistentMode) {
                                memoryOrchestrator.onTurnComplete(
                                        userId, sessionId, chatMemory.messages(),
                                        estimateTokens(chatMemory.messages()),
                                        userInput, output, trace);
                            } else {
                                memoryOrchestrator.onTurnComplete(
                                        userId, chatMemory.messages(),
                                        estimateTokens(chatMemory.messages()),
                                        userInput, output, trace);
                            }
                        }
                        state.markCompleted();
                        return config.resultCollector().collect(
                                ChatResponse.builder()
                                        .aiMessage(AiMessage.from(output))
                                        .build(), ctx);
                    }

                    continue; // 下一轮迭代
                }

                // ④ LLM 输出文本 → PostProcessor
                String output = aiMessage.text();
                for (PostProcessor pp : config.postProcessors()) {
                    output = pp.process(output, ctx);
                }

                // ⑤ 记忆提取 + 压缩检查（由 MemoryOrchestrator 统一处理）
                if (memoryOrchestrator != null && userInput != null) {
                    if (persistentMode) {
                        memoryOrchestrator.onTurnComplete(
                                userId, sessionId, chatMemory.messages(),
                                estimateTokens(chatMemory.messages()),
                                userInput, output, trace);
                    } else {
                        memoryOrchestrator.onTurnComplete(
                                userId, chatMemory.messages(),
                                estimateTokens(chatMemory.messages()),
                                userInput, output, trace);
                    }
                }

                // ⑥ 终止判定
                AiMessage processedMsg = AiMessage.from(output);
                if (config.terminator().shouldStop(ctx, ChatResponse.builder()
                        .aiMessage(processedMsg).build())) {
                    state.markCompleted();
                    return config.resultCollector().collect(
                            ChatResponse.builder().aiMessage(processedMsg).build(), ctx);
                }
            }

            // 达到最大迭代次数
            state.markCompleted();
            return AgentResult.error(ErrorType.MAX_ITERATIONS_REACHED,
                    "已达到最大迭代次数，请简化您的问题。");

        } catch (Exception e) {
            Log.e(TAG, "Agent loop failed", e);
            state.markError();
            return AgentResult.error(ErrorType.MODEL_CALL_FAILED, e.getMessage());
        }
    }

    /** 清空 legacy fallback 记忆（不属于 session-scoped 主路径）。 */
    public void cleanMemory() {
        fallbackChatMemory.clear();
    }

    /**
     * 按 sessionId 清除短期记忆（TEXT 主路径使用）。
     *
     * @throws IllegalArgumentException sessionId 为 null 或 memoryOrchestrator 不可用
     */
    public void cleanMemory(String sessionId) {
        if (memoryOrchestrator != null && sessionId != null) {
            memoryOrchestrator.clearSessionMemory(sessionId);
            return;
        }
        throw new IllegalArgumentException(
                "sessionId is required for session-aware memory cleanup");
    }

    /** 获取当前状态（供 Service 层并发控制使用） */
    public AgentLoopState getState() {
        return state;
    }

    static TraceSession extractTraceSession(Map<String, Object> extraContext) {
        if (extraContext == null) return null;
        Object value = extraContext.get(TraceContext.TRACE_CONTEXT_KEY);
        if (!(value instanceof TraceContext traceContext)) return null;
        if (!traceContext.isActive()) return null;
        return traceContext.session();
    }

    // ── 内部方法 ──

    /**
     * @deprecated 仅 SCENE/CHAT 旧 execute(String, Map) 路径使用，TEXT 路径由 Context 模块接管。
        if (!state.tryStart()) {
            return AgentResult.error(ErrorType.INVALID_CONFIG, "Agent is busy");
        }
        if (session == null || prepareResult == null || !prepareResult.isSuccess()) {
            state.markError();
            return AgentResult.error(ErrorType.INVALID_CONFIG, "Invalid session or prepare result");
        }
        if (config.memoryPolicy() != AgentConfig.MemoryPolicy.PERSISTENT) {
            state.markError();
            return AgentResult.error(ErrorType.INVALID_CONFIG, "TEXT path requires PERSISTENT memory");
        }

        String sessionId = session.sessionId();
        if (sessionId == null || sessionId.trim().isEmpty()) {
            state.markError();
            return AgentResult.error(ErrorType.INVALID_CONFIG,
                    "resolved sessionId is required for TEXT path");
        }

        String userId = session.userId() != null ? session.userId() : "default_user";
        ChatMemory chatMemory = textMemoryGateway.chatMemoryForSession(
                sessionId, config.maxMemoryMessages());
        TraceSession traceSession = extractTraceSession(session.orchestratorContext());
        AgentTraceRecorder trace = traceSession != null
                ? new AgentTraceRecorder(traceSession) : null;
        ContextBudgetPolicy budgetPolicy = ModelContextWindowProfiles.qwenTurboDemo();
        AgentLoopContext loopCtx = new AgentLoopContext(
                session.userInput(), session.personaId(), session.orchestratorContext());

        try {
            // iteration 0：将当前 UserMessage 写入 ChatMemory 一次（单向提交）
            // 优先使用 ContextPrepareResult 提供的 UserMessage（来自 UserInputContextProvider）
            UserMessage currentMsg = prepareResult.currentUserMessage();
            if (currentMsg != null) {
                chatMemory.add(currentMsg);
            } else if (session.userInput() != null && !session.userInput().isEmpty()) {
                chatMemory.add(UserMessage.from(
                        SpeakerMessageFormatter.formatUserMessage(userId, session.userInput())));
            }

            for (int i = 0; i < config.maxIterations(); i++) {
                loopCtx.setIteration(i);
                state.setIteration(i);

                // 超时检查
                if (System.currentTimeMillis() - loopCtx.startTimeMs() > config.timeout().toMillis()) {
                    state.markTimeout();
                    return AgentResult.error(ErrorType.TIMEOUT, "Agent loop timed out");
                }

                // Context Assembly：获取最终消息和工具规格
                ContextCancelChecker cancelCheck = () ->
                        prepareResult.cancelChecker() != null
                                && prepareResult.cancelChecker().isCancelled();
                ContextAssemblyRequest assemblyReq = new ContextAssemblyRequest(
                        prepareResult.frame(), i, budgetPolicy,
                        cancelCheck, false, session);
                ContextAssemblyResult assemblyResult = contextAssemblyGateway != null
                        ? contextAssemblyGateway.assemble(assemblyReq)
                        : ContextAssemblyResult.failure(
                                ContextErrorCode.CONTEXT_INTERNAL_ERROR,
                                "ContextAssemblyGateway not configured",
                                new ContextAssemblyDebugInfo(List.of(), 0, 0, null));

                // 装配失败 → 返回 CONTEXT_BUILD_FAILED
                if (assemblyResult == null || !assemblyResult.success()) {
                    state.markError();
                    return AgentResult.error(ErrorType.CONTEXT_BUILD_FAILED,
                            assemblyResult != null ? assemblyResult.errorDetail()
                                    : "Context assembly result is null");
                }

                // 预算超限检查
                if (assemblyResult.budgetReport() != null
                        && !assemblyResult.budgetReport().withinBudget()) {
                    state.markError();
                    return AgentResult.error(ErrorType.CONTEXT_BUDGET_EXCEEDED,
                            "Context budget exceeded: estimated="
                                    + assemblyResult.budgetReport().estimatedInputTokens()
                                    + " max=" + assemblyResult.budgetReport().maxInputTokens());
                }

                List<ChatMessage> requestMessages = assemblyResult.messages();
                List<ToolSpecification> requestTools = assemblyResult.toolSpecifications();

                // 模型调用前检查取消
                if (cancelCheck.isCancelled()) {
                    state.markError();
                    return AgentResult.error(ErrorType.CANCELLED, "cancelled_before_model_call");
                }

                // ② ModelCaller → LLM 调用（使用 Context 装配结果）
                ChatRequest request = ChatRequest.builder()
                        .messages(requestMessages)
                        .toolSpecifications(requestTools)
                        .build();

                Span llmSpan = trace != null
                        ? trace.startLlmCall(config.modelName(), i, requestMessages.size(),
                                io.opentelemetry.context.Context.current())
                        : null;
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
                // 已取消时丢弃 AiMessage，不写入 ChatMemory，不启动工具执行
                if (cancelCheck.isCancelled()) {
                    state.markError();
                    return AgentResult.error(ErrorType.CANCELLED,
                            "cancelled_after_model_call");
                }

                chatMemory.add(aiMessage);

                // ③ LLM 请求了工具调用 → SafetyGuard → ToolExecutor → 回填
                if (aiMessage.hasToolExecutionRequests()) {
                    List<ToolExecutionRequest> toolReqs = aiMessage.toolExecutionRequests();
                    // 工具执行前检查取消
                    if (cancelCheck.isCancelled()) {
                        state.markError();
                        return AgentResult.error(ErrorType.CANCELLED,
                                "cancelled_before_tool_execution");
                    }
                    int vetoCount = 0;

                    for (ToolExecutionRequest req : toolReqs) {
                        Span toolSpan = trace != null
                                ? trace.startTool(req, i,
                                        io.opentelemetry.context.Context.current()) : null;
                        try {
                            // 工具执行前检查取消（取消后停止执行新工具，已有结果保留）
                            if (cancelCheck.isCancelled()) {
                                vetoCount = toolReqs.size();
                                break;
                            }
                            // 安全审查：逐 guard 审查，任一否决则跳过工具执行
                            SafetyVerdict verdict = SafetyVerdict.allow();
                            for (SafetyGuard guard : config.safetyGuards()) {
                                verdict = guard.evaluate(req, loopCtx);
                                if (verdict.isVetoed()) break;
                            }

                            String toolResult;
                            if (verdict.isVetoed()) {
                                loopCtx.setLastSafetyVeto(verdict);
                                toolResult = "[SAFETY VETO] " + verdict.reason();
                                vetoCount++;
                            } else {
                                toolResult = config.toolExecutor().execute(req);
                            }

                            chatMemory.add(new ToolExecutionResultMessage(
                                    req.id(), req.name(),
                                    toolResult != null ? toolResult : "{}"));
                            if (trace != null) {
                                trace.finishTool(toolSpan, toolResult, verdict);
                            }
                            loopCtx.addToolResult(req.name(), req.arguments(),
                                    toolResult, verdict);
                        } catch (Exception e) {
                            if (trace != null) trace.finishTool(toolSpan, "error", null);
                            loopCtx.addToolResult(req.name(), req.arguments(),
                                    "error: " + e.getMessage(), null);
                            // 工具异常不回抛——将错误写入 ChatMemory 以配对 ToolRequest
                            chatMemory.add(new ToolExecutionResultMessage(
                                    req.id(), req.name(),
                                    "error: " + e.getMessage()));
                        } finally {
                            if (toolSpan != null) toolSpan.end();
                        }
                    }

                    // 工具循环因取消中断 → 返回（break 后不进入模型下一轮）
                    if (cancelCheck.isCancelled()) {
                        state.markError();
                        return AgentResult.error(ErrorType.CANCELLED,
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
                        if (textMemoryGateway != null) {
                            textMemoryGateway.extractTurnMemory(userId, sessionId,
                                    session.userInput(), output, trace);
                        }
                        state.markCompleted();
                        return config.resultCollector().collect(
                                dev.langchain4j.model.chat.response.ChatResponse.builder()
                                        .aiMessage(AiMessage.from(output))
                                        .build(), loopCtx);
                    }

                    continue;
                }

                // ④ LLM 返回文本 → PostProcessor → Memory extract → ResultCollector
                String output = aiMessage.text();

                if (config.postProcessors() != null) {
                    for (PostProcessor pp : config.postProcessors()) {
                        output = pp.process(output, loopCtx);
                    }
                }

                // 结果收集前检查取消
                if (cancelCheck.isCancelled()) {
                    state.markError();
                    return AgentResult.error(ErrorType.CANCELLED,
                            "cancelled_before_result");
                }

                // ⑤ 终止判定：Terminator + ResultCollector 控制最终结果
                dev.langchain4j.data.message.AiMessage processedMsg =
                        dev.langchain4j.data.message.AiMessage.from(output);
                if (config.terminator().shouldStop(loopCtx,
                        dev.langchain4j.model.chat.response.ChatResponse.builder()
                                .aiMessage(processedMsg).build())) {
                    if (textMemoryGateway != null) {
                        textMemoryGateway.extractTurnMemory(userId, sessionId,
                                session.userInput(), output, trace);
                    }
                    state.markCompleted();
                    return config.resultCollector().collect(
                            dev.langchain4j.model.chat.response.ChatResponse.builder()
                                    .aiMessage(processedMsg).build(), loopCtx);
                }

                // 不终止 → 继续下一轮迭代
                continue;
            }

            state.markError();
            return AgentResult.error(ErrorType.MAX_ITERATIONS_REACHED,
                    "Max iterations reached: " + config.maxIterations());
        } catch (Exception e) {
            state.markError();
            Log.e(TAG, "Agent loop failed", e);
            return AgentResult.error(ErrorType.MODEL_CALL_FAILED, e.getMessage());
        }
    }

    /**
     * 构建本轮 transient SystemMessage（含长期记忆和 persona 模板）。
     * <p>
     * 设计原因：Phase 4 起长期记忆不再持久化进 session ChatMemory，改为每轮 transient 注入。
     * 避免多人共享短期历史后把 user_a 的长期记忆暴露给 user_b。
     * <p>
     * 模板选择：优先使用 {@link AgentConfig#systemPromptTemplateName()} 中声明的人格模板。
     * TEXT 路径因单个 Orchestrator 需在运行时按 personaId 动态切换（chat/friendly/concise），
     * 仅在 personaId 为 friendly/concise 时覆盖 config 模板。
     * @deprecated 仅 SCENE/CHAT 旧 execute(String, Map) 路径使用，TEXT 路径由 Context 模块接管。
     */
    @Deprecated
    private SystemMessage buildSystemPromptMessage(String userId, String personaId) {
        // 默认使用 config 中声明的人格模板（SCENE / CHAT 固定 persona 使用此路径）
        String templateName = config.systemPromptTemplateName();
        // TEXT 动态切换：friendly/concise 两种动态人格覆盖 config 模板
        if ("friendly".equals(personaId) || "concise".equals(personaId)) {
            templateName = com.hirain.aiagent.prompt.PromptConstants.textPersonaTemplateName(personaId);
        }
        String basePrompt = promptManager.render(templateName);
        String sysPrompt = memoryOrchestrator != null
                ? memoryOrchestrator.prepareSystemPrompt(userId, basePrompt)
                : basePrompt;
        return SystemMessage.from(sysPrompt);
    }

    /** 粗略估算消息列表的 Token 数（中英文混合约 2 chars/token） */
    private int estimateTokens(List<ChatMessage> messages) {
        int chars = 0;
        for (ChatMessage msg : messages) {
            if (msg instanceof UserMessage) {
                chars += ((UserMessage) msg).singleText().length();
            } else if (msg instanceof AiMessage) {
                chars += ((AiMessage) msg).text() != null
                        ? ((AiMessage) msg).text().length() : 0;
            } else if (msg instanceof SystemMessage) {
                chars += ((SystemMessage) msg).text().length();
            } else if (msg instanceof ToolExecutionResultMessage) {
                chars += ((ToolExecutionResultMessage) msg).text().length();
            }
        }
        return chars / 2;
    }

    // ── 记忆工厂 ──

    private ChatMemory createChatMemory(Context context) {
        return switch (config.memoryPolicy()) {
            case PERSISTENT -> MessageWindowChatMemory.builder()
                    .maxMessages(config.maxMemoryMessages())
                    .chatMemoryStore(new PersistentChatMemorySqlite(
                            context.getApplicationContext(), config.chatMemoryStoreId()))
                    .build();
            case EPHEMERAL -> MessageWindowChatMemory.builder()
                    .maxMessages(config.maxMemoryMessages())
                    .build();
            case NONE -> MessageWindowChatMemory.builder()
                    .maxMessages(2)
                    .build();
        };
    }
}
