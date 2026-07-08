package com.hirain.aiagent.core;

import android.content.Context;
import android.util.Log;

import com.hirain.aiagent.core.component.LoopTerminator;
import com.hirain.aiagent.memory.MemoryOrchestrator;
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
    private final ChatMemory chatMemory;
    private final AgentLoopState state = new AgentLoopState();
    private final List<ToolSpecification> effectiveToolSpecs;

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
        this.config = config;
        this.promptManager = promptManager;
        this.memoryOrchestrator = memoryOrchestrator;
        this.chatMemory = createChatMemory(context);
        this.effectiveToolSpecs = config.toolSubset() != null
                ? config.toolSubset() : allToolSpecs;
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
        Log.d(TAG, "execute: chatMemory hasSystemMessage="
                + (!chatMemory.messages().isEmpty() && chatMemory.messages().get(0) instanceof dev.langchain4j.data.message.SystemMessage)
                + " messageCount=" + chatMemory.messages().size());

        // 提取 TraceSession（用于创建 LLM 和工具子 span）
        TraceSession traceSession = extractTraceSession(extraContext);
        AgentTraceRecorder trace = traceSession != null
                ? new AgentTraceRecorder(traceSession)
                : null;

        try {
            // 注入 SystemPrompt（刷新长期记忆）
            injectSystemPrompt(userId, personaId);

            // 写入用户消息到记忆
            if (userInput != null && !userInput.isEmpty()) {
                chatMemory.add(UserMessage.from(userInput));
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

                // 组装完整请求：临时消息（前置）+ 记忆消息
                List<ChatMessage> allMessages = new ArrayList<>();
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
                            memoryOrchestrator.onTurnComplete(
                                    userId, chatMemory.messages(),
                                    estimateTokens(chatMemory.messages()),
                                    userInput, output, trace);
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
                    memoryOrchestrator.onTurnComplete(
                            userId, chatMemory.messages(),
                            estimateTokens(chatMemory.messages()),
                            userInput, output, trace);
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

    /** 清空记忆，下次 execute() 时自动重建 SystemPrompt */
    public void cleanMemory() {
        chatMemory.clear();
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

    /** 确保 ChatMemory 中的 SystemMessage 是最新的（含长期记忆、按 persona 选择模板） */
    private void injectSystemPrompt(String userId, String personaId) {
        List<ChatMessage> existing = chatMemory.messages();
        boolean hasSystemMessage = !existing.isEmpty()
                && existing.get(0) instanceof SystemMessage;

        // 根据 persona 选择系统提示词模板
        String templateName = com.hirain.aiagent.prompt.PromptConstants.textPersonaTemplateName(personaId);
        if (!hasSystemMessage) {
            // 无 SystemMessage → 直接写入
            String basePrompt = promptManager.render(templateName);
            String sysPrompt = memoryOrchestrator != null
                    ? memoryOrchestrator.prepareSystemPrompt(userId, basePrompt)
                    : basePrompt;
            chatMemory.add(SystemMessage.from(sysPrompt));
            return;
        }

        // 已有 SystemMessage → 替换为含最新长期记忆的版本
        String basePrompt = promptManager.render(templateName);
        String sysPrompt = memoryOrchestrator != null
                ? memoryOrchestrator.prepareSystemPrompt(userId, basePrompt)
                : basePrompt;

        // 检查 SystemPrompt 是否需要更新（长期记忆可能已变化）
        if (existing.get(0) instanceof SystemMessage
                && sysPrompt.equals(((SystemMessage) existing.get(0)).text())) {
            return; // 内容相同，无需替换
        }

        // 替换旧的 SystemMessage
        List<ChatMessage> history = new ArrayList<>(existing);
        chatMemory.clear();
        chatMemory.add(SystemMessage.from(sysPrompt));
        for (int i = 1; i < history.size(); i++) {
            chatMemory.add(history.get(i));
        }
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
