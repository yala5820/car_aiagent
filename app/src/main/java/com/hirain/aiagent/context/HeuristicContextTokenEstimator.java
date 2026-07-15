package com.hirain.aiagent.context;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

/**
 * 低成本保守 Token 估算器 — 基于字符数和结构开销估算。
 * <p>
 * 规则：
 * <ul>
 *   <li>普通文本：{@code ceil(codePointCount / 2.0)}，至少 1 token</li>
 *   <li>每条 ChatMessage 增加固定结构开销 8 tokens</li>
 *   <li>每个 ToolSpecification：name+description+parameter schema 序列化后按 /2 估算，+16 tokens 结构开销</li>
 *   <li>最终估算乘以 1.15 安全系数并向上取整</li>
 * </ul>
 * 不宣称等于 Qwen tokenizer 真实结果。
 */
public class HeuristicContextTokenEstimator implements ContextTokenEstimator {

    private static final double SAFETY_FACTOR = 1.15;

    @Override
    public int estimateMessages(List<ChatMessage> messages) {
        if (messages == null) return 0;
        int total = 0;
        for (ChatMessage msg : messages) {
            total += 8; // 结构开销
            total += tokenCount(messageContent(msg));
        }
        return ceil(total);
    }

    @Override
    public int estimateToolSpecs(List<ToolSpecification> specs) {
        if (specs == null) return 0;
        int total = 0;
        for (ToolSpecification spec : specs) {
            total += 16; // 结构开销
            String name = spec.name() != null ? spec.name() : "";
            String desc = spec.description() != null ? spec.description() : "";
            total += tokenCount(name + desc);
            // 计入 parameter schema 序列化结果
            if (spec.parameters() != null) {
                String params = spec.parameters().toString();
                total += tokenCount(params);
            } else {
                // parameters 为空仍保留固定结构开销
                total += tokenCount("");
            }
        }
        return ceil(total);
    }

    @Override
    public int estimateTotal(List<ChatMessage> messages, List<ToolSpecification> specs) {
        return estimateMessages(messages) + estimateToolSpecs(specs);
    }

    private static int tokenCount(String text) {
        if (text == null || text.isEmpty()) return 0;
        int count = text.codePointCount(0, text.length());
        return (count + 1) / 2; // ceil division
    }

    /** 按 LangChain4j 的真实消息字段估算，避免调试用 toString() 格式变化影响预算。 */
    private static String messageContent(ChatMessage message) {
        if (message == null) return "";
        if (message instanceof SystemMessage system) return safe(system.text());
        if (message instanceof UserMessage user) {
            try { return safe(user.singleText()); }
            catch (Exception ignored) { return safe(user.toString()); }
        }
        if (message instanceof ToolExecutionResultMessage result) {
            return safe(result.id()) + safe(result.toolName()) + safe(result.text());
        }
        if (message instanceof AiMessage ai) {
            StringBuilder content = new StringBuilder(safe(ai.text()));
            if (ai.hasToolExecutionRequests()) {
                for (ToolExecutionRequest request : ai.toolExecutionRequests()) {
                    content.append(safe(request.id()))
                            .append(safe(request.name()))
                            .append(safe(request.arguments()));
                }
            }
            return content.toString();
        }
        return safe(message.toString());
    }

    private static String safe(String value) { return value != null ? value : ""; }

    private static int ceil(int value) {
        return (int) Math.ceil(value * SAFETY_FACTOR);
    }
}
