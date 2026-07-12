package com.hirain.aiagent.memory;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

public class SessionIdResolverTest {

    @Test
    public void explicitSessionIdWins() {
        FakeSessionIdResolver resolver = new FakeSessionIdResolver("active-1");

        assertEquals("request-session",
                resolver.resolveSessionId("user_a", " request-session ", "标题", "chat", "launcher"));
    }

    @Test
    public void missingSessionUsesActiveSession() {
        FakeSessionIdResolver resolver = new FakeSessionIdResolver("active-1");

        assertEquals("active-1",
                resolver.resolveSessionId("user_a", null, "标题", "chat", "launcher"));
    }

    @Test
    public void missingSessionCreatesSessionWhenNoActiveSession() {
        FakeSessionIdResolver resolver = new FakeSessionIdResolver(null);

        String resolved = resolver.resolveSessionId("user_a", null, "标题", "chat", "launcher");

        assertNotNull(resolved);
        assertEquals(resolved, resolver.createdSessionId());
    }

    // ── FakeSessionIdResolver ──

    /**
     * 内嵌 fake 实现，用于表达 SessionIdResolver 接口语义。
     * 当 requestedSessionId 非空白时直接返回；否则返回预先设定的 active session；
     * 若 active session 也为 null，则模拟"创建新 session"行为。
     */
    static final class FakeSessionIdResolver implements SessionIdResolver {
        private final String activeSessionId;
        private String createdSessionId;

        FakeSessionIdResolver(String activeSessionId) {
            this.activeSessionId = activeSessionId;
        }

        @Override
        public String resolveSessionId(String userId, String requestedSessionId,
                                       String title, String personaId, String sourceApp) {
            if (requestedSessionId != null && !requestedSessionId.trim().isEmpty()) {
                return requestedSessionId.trim();
            }
            if (activeSessionId != null && !activeSessionId.trim().isEmpty()) {
                return activeSessionId;
            }
            createdSessionId = "new-session-for-" + userId;
            return createdSessionId;
        }

        String createdSessionId() {
            return createdSessionId;
        }
    }
}
