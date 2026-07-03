package com.hirain.aiagent.trace;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

public class TraceMessageFormatterTest {

    @Test
    public void formatsSystemAndUserMessagesWithRoles() {
        TraceMessageFormatter formatter = new TraceMessageFormatter(200);
        List<ChatMessage> messages = List.of(
                SystemMessage.from("你是车机助手"),
                UserMessage.from("打开空调"));

        String result = formatter.formatMessages(messages);

        assertTrue(result.contains("system:你是车机助手"));
        assertTrue(result.contains("user:打开空调"));
    }

    @Test
    public void formatsAssistantTextMessage() {
        TraceMessageFormatter formatter = new TraceMessageFormatter(200);

        String result = formatter.formatMessages(List.of(AiMessage.from("空调已打开")));

        assertTrue(result.contains("assistant:空调已打开"));
    }

    @Test
    public void truncatesLongMessageOutput() {
        TraceMessageFormatter formatter = new TraceMessageFormatter(40);

        String result = formatter.formatMessages(List.of(UserMessage.from(repeat("a", 120))));

        assertTrue(result.length() <= 60);
        assertTrue(result.contains("truncated"));
    }

    @Test
    public void formatsToolSpecificationNames() {
        TraceMessageFormatter formatter = new TraceMessageFormatter(200);

        String result = formatter.formatToolNames(List.of("set_ac_temperature", "get_weather"));

        assertTrue(result.contains("set_ac_temperature"));
        assertTrue(result.contains("get_weather"));
    }

    @Test
    public void formatsToolExecutionResultWithToolNameRole() {
        TraceMessageFormatter formatter = new TraceMessageFormatter(200);
        ToolExecutionResultMessage message = ToolExecutionResultMessage.from(
                "call-1",
                "set_ac_temperature",
                "温度已设置为 24 度");

        String result = formatter.formatMessages(List.of(message));

        assertTrue(result.contains("tool:set_ac_temperature"));
        assertTrue(result.contains("温度已设置为 24 度"));
    }

    private static String repeat(String value, int count) {
        StringBuilder sb = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(value);
        }
        return sb.toString();
    }
}
