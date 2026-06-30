package com.hirain.aiagent.core.model;

import com.hirain.aiagent.core.component.ModelCaller;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * LangChain4j {@link ChatModel} 的薄封装。
 */
public class Lc4jModelCaller implements ModelCaller {

    private final ChatModel model;

    public Lc4jModelCaller(ChatModel model) {
        this.model = model;
    }

    @Override
    public ChatResponse call(ChatRequest request) {
        return model.chat(request);
    }
}
