package com.hirain.aiagent.context;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;

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
            String text = msg != null ? msg.toString() : "";
            total += tokenCount(text);
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
        int count = text.codePointCount(0, text.length());
        return (count + 1) / 2; // ceil division
    }

    private static int ceil(int value) {
        return (int) Math.ceil(value * SAFETY_FACTOR);
    }
}
