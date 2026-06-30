package com.hirain.aiagent.core.collector;

import com.hirain.aiagent.core.AgentLoopContext;
import com.hirain.aiagent.core.AgentResult;
import com.hirain.aiagent.core.component.ResultCollector;
import com.hirain.aiagent.prompt.PromptConstants;
import com.hirain.aiagent.prompt.PromptManager;

import java.util.Map;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * 场景动作汇总收集器 — 将 LLM 工具执行后的输出与硬编码动作文本合并，
 * 调用一次轻量级模型将其总结为 80 字以内的摘要。
 * <p>
 * 替代 SceneServer 的二次 LLM 调用 + 文本整合逻辑。
 */
public class SummarizeMergeCollector implements ResultCollector {

    private final PromptManager promptManager;
    private final ChatModel summaryModel;
    private final int maxChars;

    public SummarizeMergeCollector(PromptManager promptManager, ChatModel summaryModel, int maxChars) {
        this.promptManager = promptManager;
        this.summaryModel = summaryModel;
        this.maxChars = maxChars;
    }

    @Override
    public AgentResult collect(ChatResponse response, AgentLoopContext ctx) {
        long duration = System.currentTimeMillis() - ctx.startTimeMs();
        String llmText = response.aiMessage().text();
        String hardcodedAction = ctx.getContextData("hardcoded_action", String.class);

        if (hardcodedAction == null || hardcodedAction.isEmpty()) {
            // 无硬编码动作，直接返回 LLM 文本
            return AgentResult.success(llmText, ctx.iteration() + 1, duration,
                    ctx.toolExecutionHistory());
        }

        // 调用模型合并 LLM 输出 + 硬编码动作 → 80 字摘要
        String summaryPrompt = promptManager.render(PromptConstants.USER_SUMMARIZE,
                Map.of("first_part", llmText, "second_part", hardcodedAction));
        ChatResponse summary = summaryModel.chat(
                ChatRequest.builder()
                        .messages(UserMessage.from(summaryPrompt))
                        .build());
        String merged = summary.aiMessage().text();
        return AgentResult.success(merged, ctx.iteration() + 1, duration,
                ctx.toolExecutionHistory());
    }
}
