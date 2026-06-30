package com.hirain.aiagent.core.component;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

/**
 * 工具执行器 — 根据 ToolExecutionRequest 执行对应工具方法。
 * <p>
 * 默认实现委托给 {@link com.hirain.aiagent.ai.langchain4j.tool.ToolRegistry ToolRegistry}。
 */
@FunctionalInterface
public interface ToolExecutor {

    /**
     * 执行工具调用。
     * @param request LLM 发起的工具调用请求
     * @return 工具执行结果文本
     */
    String execute(ToolExecutionRequest request);
}
