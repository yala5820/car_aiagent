package com.hirain.aiagent.core.component;

import com.hirain.aiagent.core.AgentLoopContext;

import java.util.List;

import dev.langchain4j.data.message.ChatMessage;

/**
 * 预处理器 — 在每轮 LLM 调用前向请求中注入临时上下文消息。
 * <p>
 * 返回的消息会 PREPEND 到 chatMemory.messages() 之前，但不会写入持久化记忆。
 * 用于注入车辆状态、场景描述、时间等瞬时上下文。
 */
@FunctionalInterface
public interface PreProcessor {

    /**
     * 生成本轮 LLM 调用所需的临时上下文消息。
     *
     * @param ctx 当前执行上下文
     * @return 临时消息列表（可为空）
     */
    List<ChatMessage> prepare(AgentLoopContext ctx);
}
