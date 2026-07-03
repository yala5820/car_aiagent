package com.hirain.aiagent.core;

import static org.junit.Assert.assertEquals;

import com.hirain.aiagent.core.collector.DirectTextCollector;
import com.hirain.aiagent.core.terminator.NoToolCallTerminator;

import org.junit.Test;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;

public class AgentConfigTraceTest {

    @Test
    public void builderStoresModelNameForTraceRecorder() {
        AgentConfig config = AgentConfig.builder("chat")
                .systemPromptTemplateName("system/default")
                .modelName("qwen-turbo")
                .modelCaller(request -> ChatResponse.builder()
                        .aiMessage(AiMessage.from("ok"))
                        .build())
                .toolExecutor(request -> "ok")
                .terminator(new NoToolCallTerminator())
                .resultCollector(new DirectTextCollector())
                .build();

        assertEquals("qwen-turbo", config.modelName());
    }

    @Test
    public void builderDefaultsModelNameToUnknown() {
        AgentConfig config = AgentConfig.builder("chat")
                .systemPromptTemplateName("system/default")
                .modelCaller(request -> ChatResponse.builder()
                        .aiMessage(AiMessage.from("ok"))
                        .build())
                .toolExecutor(request -> "ok")
                .terminator(new NoToolCallTerminator())
                .resultCollector(new DirectTextCollector())
                .build();

        assertEquals("unknown", config.modelName());
    }
}
