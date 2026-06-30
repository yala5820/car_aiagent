package com.hirain.aiagent.core.component;

import com.hirain.aiagent.core.AgentLoopContext;

import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * 循环终止判定器 — 判断 Agent 循环是否应该停止。
 * <p>
 * 通过 {@link com.hirain.aiagent.core.terminator.CompositeTerminator CompositeTerminator}
 * 可组合多个终止条件（如：无工具调用 OR 安全否决 OR 达到迭代上限）。
 */
@FunctionalInterface
public interface LoopTerminator {

    /**
     * 判断是否应停止循环。
     * @param ctx      当前执行上下文
     * @param response 当前轮的 LLM 响应
     * @return true 停止，false 继续
     */
    boolean shouldStop(AgentLoopContext ctx, ChatResponse response);
}
