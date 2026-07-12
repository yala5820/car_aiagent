package com.hirain.aiagent.memory;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import static org.junit.Assert.assertEquals;

public class MemorySnapshotTest {

    @Test
    public void snapshotDefensivelyCopiesMessages() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("hello"));

        MemorySnapshot snapshot = new MemorySnapshot("session-1", messages, 10, "summary");
        messages.clear();

        assertEquals(1, snapshot.messages().size());
        assertEquals("session-1", snapshot.sessionId());
        assertEquals(10, snapshot.tokenEstimate());
        assertEquals("summary", snapshot.summary());
    }
}
