package com.hirain.aiagent.memory;

import org.junit.Test;

import java.util.List;

import dev.langchain4j.data.message.UserMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * MemorySnapshot sessionId 边界测试。
 * <p>
 * 设计原因：短期记忆的 memoryId 已由 {@link SessionMemoryIds#shortTermMemoryId(String)}
 * 统一收敛为原始 sessionId（无任何前缀）。验证新旧构造器行为一致。
 */
public class MemorySnapshotSessionIdBoundaryTest {

    private static final String RAW_SESSION_ID = "session-1";

    @Test
    public void rawSessionIdFactory_preservesOriginalSessionId() {
        MemorySnapshot snapshot = new MemorySnapshot(
                RAW_SESSION_ID, List.of(), 0, "", true);

        assertEquals("rawSessionId=true should preserve sessionId",
                RAW_SESSION_ID, snapshot.sessionId());
    }

    @Test
    public void oldConstructor_producesSameSessionId() {
        MemorySnapshot snapshot = new MemorySnapshot(
                RAW_SESSION_ID, List.of(), 0, "");

        // shortTermMemoryId() 已收敛为原始 sessionId（无 memory: 前缀）
        assertEquals("Old constructor should also produce raw sessionId",
                RAW_SESSION_ID, snapshot.sessionId());
    }

    @Test
    public void rawSessionIdFactory_withMessages_preservesContent() {
        UserMessage msg = UserMessage.from("Hello");
        MemorySnapshot snapshot = new MemorySnapshot(
                RAW_SESSION_ID, List.of(msg), 42, "test summary", true);

        assertEquals(RAW_SESSION_ID, snapshot.sessionId());
        assertEquals(1, snapshot.messageCount());
        assertEquals(42, snapshot.tokenEstimate());
        assertEquals("test summary", snapshot.summary());
        assertTrue("Messages should contain the added message",
                snapshot.messages().contains(msg));
    }

    @Test
    public void bothConstructors_produceSameSessionId() {
        MemorySnapshot withRawFlag = new MemorySnapshot(
                RAW_SESSION_ID, List.of(), 0, "", true);
        MemorySnapshot withoutRawFlag = new MemorySnapshot(
                RAW_SESSION_ID, List.of(), 0, "");

        assertEquals("Both constructors should produce the same sessionId",
                withRawFlag.sessionId(), withoutRawFlag.sessionId());
    }
}
