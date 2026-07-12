package com.hirain.aiagent.memory;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class SessionMemoryIdsTest {

    @Test
    public void shortTermMemoryIdUsesOnlySessionId() {
        assertEquals("session-1", SessionMemoryIds.shortTermMemoryId("session-1"));
    }

    @Test
    public void blankSessionIsRejectedBecauseRuntimeMustResolveIt() {
        try {
            SessionMemoryIds.shortTermMemoryId("");
            fail("blank sessionId should be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals("sessionId must be resolved before building short-term memory id",
                    expected.getMessage());
        }
        try {
            SessionMemoryIds.shortTermMemoryId(null);
            fail("null sessionId should be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals("sessionId must be resolved before building short-term memory id",
                    expected.getMessage());
        }
    }

    @Test
    public void userAndPersonaDoNotParticipateInShortTermKey() {
        String first = SessionMemoryIds.shortTermMemoryId("session-1");
        String second = SessionMemoryIds.shortTermMemoryId("session-1");
        assertEquals(first, second);
    }

    @Test
    public void deletePrefixMatchesOnlyOneSessionId() {
        assertEquals("session-1", SessionMemoryIds.shortTermMemoryPrefix("session-1"));
    }
}
