package com.hirain.aiagent.core.component;

import com.hirain.aiagent.core.AgentLoopContext;
import com.hirain.aiagent.core.AgentResult;

import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * 结果收集器 — 循环终止后将最终 LLM 响应组装为 {@link AgentResult}。
 * <p>
 * 不同人格使用不同策略：
 * <ul>
 *   <li>{@code chat} / {@code vision_qa} → 直接返回 LLM 文本</li>
 *   <li>{@code scene} → 将 LLM 输出 + 硬编码动作合并为 80 字摘要</li>
 * </ul>
 */
@FunctionalInterface
public interface ResultCollector {

    /**
     * 组装最终结果。
     * @param response 最终轮的 LLM 响应
     * @param ctx      当前执行上下文（含 toolExecutionHistory）
     * @return 结构化结果
     */
    AgentResult collect(ChatResponse response, AgentLoopContext ctx);
}
