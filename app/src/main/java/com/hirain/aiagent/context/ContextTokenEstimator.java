package com.hirain.aiagent.context;

import java.util.List;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;

/**
 * Token 估算器接口 — 低成本估算消息和工具规格的 Token 数。
 * <p>
 * 设计原因：将 Token 估算与预算决策分离；估算器只返回数值，不决定裁剪谁。
 */
public interface ContextTokenEstimator {

    int estimateMessages(List<ChatMessage> messages);

    int estimateToolSpecs(List<ToolSpecification> specs);

    int estimateTotal(List<ChatMessage> messages, List<ToolSpecification> specs);
}
