package com.hirain.aiagent.memory;

import org.junit.Test;

import java.util.List;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SessionHistorySequenceValidatorTest {

    @Test
    public void completeParallelToolExchangeIsPreserved() {
        ToolExecutionRequest first = call("id-1", "tool-a");
        ToolExecutionRequest second = call("id-2", "tool-b");
        List<ChatMessage> history = List.of(
                UserMessage.from("do it"), AiMessage.from(List.of(first, second)),
                ToolExecutionResultMessage.from(first, "ok-a"),
                ToolExecutionResultMessage.from(second, "ok-b"), AiMessage.from("done"));

        SessionHistorySequenceValidator.ValidationResult result =
                SessionHistorySequenceValidator.validate(history);

        assertFalse(result.repaired());
        assertEquals(history, result.messages());
    }

    @Test
    public void tailUnclosedExchangeRemovesWholeConversationTurn() {
        ToolExecutionRequest broken = call("id-2", "tool-b");
        List<ChatMessage> history = List.of(
                UserMessage.from("first"), AiMessage.from("answer"),
                UserMessage.from("second"), AiMessage.from(broken));

        SessionHistorySequenceValidator.ValidationResult result =
                SessionHistorySequenceValidator.validate(history);

        assertTrue(result.repaired());
        assertEquals(2, result.removedMessageCount());
        assertEquals(List.of(UserMessage.from("first"), AiMessage.from("answer")), result.messages());
    }

    @Test(expected = SessionHistorySequenceValidator.InvalidSessionHistoryException.class)
    public void unknownToolResultFailsHard() {
        ToolExecutionRequest request = call("id-1", "tool-a");
        SessionHistorySequenceValidator.validate(List.of(
                UserMessage.from("run"), AiMessage.from(request),
                new ToolExecutionResultMessage("unknown", "tool-a", "bad")));
    }

    @Test(expected = SessionHistorySequenceValidator.InvalidSessionHistoryException.class)
    public void duplicateToolCallIdFailsHard() {
        ToolExecutionRequest request = call("same", "tool-a");
        SessionHistorySequenceValidator.validate(List.of(
                UserMessage.from("run"), AiMessage.from(request),
                ToolExecutionResultMessage.from(request, "ok"),
                AiMessage.from(request), ToolExecutionResultMessage.from(request, "ok")));
    }

    private static ToolExecutionRequest call(String id, String name) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments("{}").build();
    }
}
