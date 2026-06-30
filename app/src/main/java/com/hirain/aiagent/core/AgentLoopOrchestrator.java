package com.hirain.aiagent.core;

import android.content.Context;
import android.util.Log;

import com.hirain.aiagent.core.component.LoopTerminator;
import com.hirain.aiagent.memory.MemoryOrchestrator;
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
 *     → 确保系统提示词（记忆为空时注入）
 *     → 写入用户消息到记忆
 *     → for i in 0..maxIterations:
 *         ① PreProcessor 链 → 生成临时上下文消息
 *         ② ModelCaller → LLM 调用
 *         ③ LLM 返回 ToolCall → SafetyGuard → ToolExecutor → 回填结果 → continue
 *         ④ LLM 返回文本 → PostProcessor → LoopTerminator → ResultCollector → return
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

        AgentLoopContext ctx = new AgentLoopContext(userInput, config.personaId(), extraContext);
        Log.d(TAG, "execute: persona=" + config.personaId() + " maxIter=" + config.maxIterations());

        try {
            // 确保系统提示词（含长期记忆注入）
            if (chatMemory.messages().isEmpty()) {
                String basePrompt = promptManager.render(config.systemPromptTemplateName());
                String sysPrompt = memoryOrchestrator != null
                        ? memoryOrchestrator.prepareSystemPrompt(userId, basePrompt)
                        : basePrompt;
                chatMemory.add(SystemMessage.from(sysPrompt));
            }

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

                // ② ModelCaller → LLM 调用
                ChatRequest request = ChatRequest.builder()
                        .messages(allMessages)
                        .toolSpecifications(effectiveToolSpecs)
                        .build();
                ChatResponse response = config.modelCaller().call(request);
                AiMessage aiMessage = response.aiMessage();
                chatMemory.add(aiMessage);

                // ③ LLM 请求了工具调用
                if (aiMessage.hasToolExecutionRequests()) {
                    boolean hadVeto = false;
                    for (ToolExecutionRequest toolReq : aiMessage.toolExecutionRequests()) {
                        // 安全审查
                        SafetyVerdict verdict = SafetyVerdict.allow();
                        for (SafetyGuard guard : config.safetyGuards()) {
                            verdict = guard.evaluate(toolReq, ctx);
                            if (verdict.isVetoed()) break;
                        }

                        String result;
                        if (verdict.isVetoed()) {
                            ctx.setLastSafetyVeto(verdict);
                            result = "[SAFETY VETO] " + verdict.reason();
                            hadVeto = true;
                        } else {
                            result = config.toolExecutor().execute(toolReq);
                        }
                        ctx.addToolResult(toolReq.name(), toolReq.arguments(), result, verdict);
                        chatMemory.add(ToolExecutionResultMessage.from(toolReq, result));
                        Log.d(TAG, "Tool[" + toolReq.name() + "] -> " + result);
                    }
                    continue; // 下一轮迭代
                }

                // ④ LLM 输出文本 → PostProcessor
                String output = aiMessage.text();
                for (PostProcessor pp : config.postProcessors()) {
                    output = pp.process(output, ctx);
                }

                // ⑤ 记忆提取（后台异步）
                if (memoryOrchestrator != null && userInput != null) {
                    memoryOrchestrator.onTurnComplete(
                            userId, chatMemory.messages(), 0, userInput, output);
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

    /** 清空记忆并重建系统提示词 */
    public void cleanMemory() {
        chatMemory.clear();
        String sysPrompt = promptManager.render(config.systemPromptTemplateName());
        chatMemory.add(SystemMessage.from(sysPrompt));
    }

    /** 获取当前状态（供 Service 层并发控制使用） */
    public AgentLoopState getState() {
        return state;
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
