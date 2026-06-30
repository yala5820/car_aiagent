package com.hirain.aiagent.core.component;

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * 模型调用器 — 封装 LangChain4j ChatModel 调用。
 * <p>
 * 单独抽象为接口的目的：单元测试时可 mock 模型调用。
 */
@FunctionalInterface
public interface ModelCaller {

    /**
     * 调用 LLM 并返回响应。
     * @param request ChatRequest（含 messages + toolSpecifications）
     * @return ChatResponse
     */
    ChatResponse call(ChatRequest request);
}
