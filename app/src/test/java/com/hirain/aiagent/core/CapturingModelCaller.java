package com.hirain.aiagent.core;

import com.hirain.aiagent.core.component.ModelCaller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * 只捕获 {@link ChatRequest} 的 {@link ModelCaller} 实现，不访问网络。
 * <p>
 * 设计原因：用于 Phase 1 基线特征测试和 Phase 3 影子差异验证。
 * 每次调用 {@link #call(ChatRequest)} 将请求追加到 {@link #capturedRequests}，
 * 并返回固定 {@code AiMessage("ok")} 响应。
 * 线程安全：内部使用 {@link CopyOnWriteArrayList}。
 */
public class CapturingModelCaller implements ModelCaller {

    private final List<ChatRequest> capturedRequests = new CopyOnWriteArrayList<>();

    @Override
    public ChatResponse call(ChatRequest request) {
        capturedRequests.add(request);
        return ChatResponse.builder()
                .aiMessage(AiMessage.from("ok"))
                .build();
    }

    /**
     * 返回所有已捕获的 {@link ChatRequest} 不可变视图。
     */
    public List<ChatRequest> capturedRequests() {
        return Collections.unmodifiableList(capturedRequests);
    }

    /**
     * 清空已捕获的请求列表。
     */
    public void reset() {
        capturedRequests.clear();
    }

    /**
     * 返回最近一次捕获的 {@link ChatRequest}，若无则返回 {@code null}。
     */
    public ChatRequest lastRequest() {
        return capturedRequests.isEmpty()
                ? null : capturedRequests.get(capturedRequests.size() - 1);
    }

    /**
     * 返回捕获请求的数量。
     */
    public int callCount() {
        return capturedRequests.size();
    }
}
