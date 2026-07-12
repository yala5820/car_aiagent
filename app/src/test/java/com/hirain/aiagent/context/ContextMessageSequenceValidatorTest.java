package com.hirain.aiagent.context;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import static org.junit.Assert.assertThrows;

public class ContextMessageSequenceValidatorTest {

    @Test
    public void validSingleSystemMessage_passes() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("You are a helpful assistant"));
        messages.add(UserMessage.from("Hello"));
        ContextMessageSequenceValidator.validate(messages);
    }

    @Test
    public void twoSystemMessages_fails() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("System one"));
        messages.add(UserMessage.from("Hello"));
        messages.add(SystemMessage.from("System two"));
        assertThrows(ContextMessageSequenceValidator.InvalidMessageSequenceException.class,
                () -> ContextMessageSequenceValidator.validate(messages));
    }

    @Test
    public void systemMessageNotFirst_fails() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("Hello"));
        assertThrows(ContextMessageSequenceValidator.InvalidMessageSequenceException.class,
                () -> ContextMessageSequenceValidator.validate(messages));
    }

    @Test
    public void nullMessages_fails() {
        assertThrows(ContextMessageSequenceValidator.InvalidMessageSequenceException.class,
                () -> ContextMessageSequenceValidator.validate(null));
    }

    @Test
    public void emptyList_fails() {
        assertThrows(ContextMessageSequenceValidator.InvalidMessageSequenceException.class,
                () -> ContextMessageSequenceValidator.validate(List.of()));
    }

    @Test
    public void toolCallWithResult_passes() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("System"));
        messages.add(UserMessage.from("Turn on AC"));
        messages.add(AiMessage.from(ToolExecutionRequest.builder()
                .id("req-1").name("set_ac_status").arguments("{}").build()));
        messages.add(new ToolExecutionResultMessage("req-1", "set_ac_status", "success"));
        ContextMessageSequenceValidator.validate(messages);
    }

    @Test
    public void twoToolComplete_passes() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("System"));
        messages.add(UserMessage.from("Do two things"));
        messages.add(AiMessage.from(
                ToolExecutionRequest.builder().id("r1").name("tool_a").arguments("{}").build(),
                ToolExecutionRequest.builder().id("r2").name("tool_b").arguments("{}").build()));
        messages.add(new ToolExecutionResultMessage("r1", "tool_a", "ok"));
        messages.add(new ToolExecutionResultMessage("r2", "tool_b", "ok"));
        ContextMessageSequenceValidator.validate(messages);
    }

    @Test
    public void wrongId_fails() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("System"));
        messages.add(UserMessage.from("Hi"));
        messages.add(AiMessage.from(ToolExecutionRequest.builder()
                .id("req-1").name("tool").arguments("{}").build()));
        messages.add(new ToolExecutionResultMessage("wrong-id", "tool", "result"));
        assertThrows(ContextMessageSequenceValidator.InvalidMessageSequenceException.class,
                () -> ContextMessageSequenceValidator.validate(messages));
    }

    @Test
    public void wrongName_fails() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("System"));
        messages.add(UserMessage.from("Hi"));
        messages.add(AiMessage.from(ToolExecutionRequest.builder()
                .id("req-1").name("expected_tool").arguments("{}").build()));
        messages.add(new ToolExecutionResultMessage("req-1", "wrong_tool", "result"));
        assertThrows(ContextMessageSequenceValidator.InvalidMessageSequenceException.class,
                () -> ContextMessageSequenceValidator.validate(messages));
    }

    @Test
    public void nullToolName_fails() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("System"));
        messages.add(UserMessage.from("Hi"));
        messages.add(AiMessage.from(ToolExecutionRequest.builder()
                .id("req-1").name("expected_tool").arguments("{}").build()));
        messages.add(new ToolExecutionResultMessage("req-1", null, "result"));
        assertThrows(ContextMessageSequenceValidator.InvalidMessageSequenceException.class,
                () -> ContextMessageSequenceValidator.validate(messages));
    }

    @Test
    public void duplicateResult_fails() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("System"));
        messages.add(UserMessage.from("Hi"));
        messages.add(AiMessage.from(ToolExecutionRequest.builder()
                .id("req-1").name("tool").arguments("{}").build()));
        messages.add(new ToolExecutionResultMessage("req-1", "tool", "ok"));
        messages.add(new ToolExecutionResultMessage("req-1", "tool", "dup"));
        assertThrows(ContextMessageSequenceValidator.InvalidMessageSequenceException.class,
                () -> ContextMessageSequenceValidator.validate(messages));
    }

    @Test
    public void missingSecondResult_fails() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("System"));
        messages.add(UserMessage.from("Hi"));
        messages.add(AiMessage.from(
                ToolExecutionRequest.builder().id("r1").name("tool_a").arguments("{}").build(),
                ToolExecutionRequest.builder().id("r2").name("tool_b").arguments("{}").build()));
        messages.add(new ToolExecutionResultMessage("r1", "tool_a", "ok"));
        // r2 result missing
        assertThrows(ContextMessageSequenceValidator.InvalidMessageSequenceException.class,
                () -> ContextMessageSequenceValidator.validate(messages));
    }

    @Test
    public void newUserBeforeResults_fails() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("System"));
        messages.add(UserMessage.from("Hi"));
        messages.add(AiMessage.from(ToolExecutionRequest.builder()
                .id("req-1").name("tool").arguments("{}").build()));
        messages.add(UserMessage.from("New input before results"));
        assertThrows(ContextMessageSequenceValidator.InvalidMessageSequenceException.class,
                () -> ContextMessageSequenceValidator.validate(messages));
    }

    @Test
    public void duplicateSystem_fails() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("System one"));
        messages.add(SystemMessage.from("System two"));
        assertThrows(ContextMessageSequenceValidator.InvalidMessageSequenceException.class,
                () -> ContextMessageSequenceValidator.validate(messages));
    }

    @Test
    public void cancelledToolResult_sameRulesAsNormal() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("System"));
        messages.add(UserMessage.from("Do it"));
        messages.add(AiMessage.from(ToolExecutionRequest.builder()
                .id("req-1").name("my_tool").arguments("{}").build()));
        messages.add(new ToolExecutionResultMessage("req-1", "my_tool", "cancelled_before_execution"));
        ContextMessageSequenceValidator.validate(messages);
    }
}
