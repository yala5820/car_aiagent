package com.hirain.aiagent.conversation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.hirain.aiagent.ConversationInfo;
import com.hirain.aiagent.ConversationListResponse;
import com.hirain.aiagent.ConversationOperationResult;
import com.hirain.aiagent.ConversationRequest;
import com.hirain.aiagent.memory.SessionMemoryStore;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConversationManagerTest {

    @Test
    public void createConversation_usesDefaultUserAndChatPersonaWhenMissing() {
        FakeConversationSessionGateway gateway = new FakeConversationSessionGateway();
        ConversationManager manager = new ConversationManager(gateway);

        ConversationOperationResult result = manager.createConversation(new ConversationRequest());

        assertTrue(result.isSuccess());
        assertEquals("CREATE", result.getOperation());
        assertNotNull(result.getConversationInfo());
        assertEquals(ConversationConstants.DEFAULT_USER_ID, result.getConversationInfo().getUserId());
        assertEquals(ConversationConstants.DEFAULT_PERSONA_ID, result.getConversationInfo().getPersonaId());
        assertNotNull(result.getConversationInfo().getSessionId());
    }

    @Test
    public void listConversations_returnsOnlyRequestedUserSessions() {
        FakeConversationSessionGateway gateway = new FakeConversationSessionGateway();
        ConversationManager manager = new ConversationManager(gateway);

        manager.createConversation(requestFor("user-A"));
        manager.createConversation(requestFor("user-B"));
        manager.createConversation(requestFor("user-A"));

        ConversationListResponse userAResponse = manager.listConversations("user-A");
        ConversationListResponse userBResponse = manager.listConversations("user-B");

        assertTrue(userAResponse.isSuccess());
        assertTrue(userBResponse.isSuccess());
        assertEquals(2, userAResponse.getConversations().size());
        assertEquals(1, userBResponse.getConversations().size());
    }

    @Test
    public void switchConversation_returnsNotFoundForMissingSession() {
        FakeConversationSessionGateway gateway = new FakeConversationSessionGateway();
        ConversationManager manager = new ConversationManager(gateway);

        ConversationOperationResult result = manager.switchConversation("user-A", "non_existent_session");

        assertFalse(result.isSuccess());
        assertEquals("SWITCH", result.getOperation());
        assertEquals(ConversationConstants.ERROR_NOT_FOUND, result.getErrorType());
    }

    @Test
    public void deleteConversation_deletesSessionAndMessages() {
        FakeConversationSessionGateway gateway = new FakeConversationSessionGateway();
        ConversationManager manager = new ConversationManager(gateway);

        ConversationInfo created = manager.createConversation(requestFor("user-A")).getConversationInfo();
        assertNotNull(created);

        ConversationOperationResult deleteResult = manager.deleteConversation("user-A", created.getSessionId());

        assertTrue(deleteResult.isSuccess());
        assertEquals("DELETE", deleteResult.getOperation());

        ConversationListResponse listResponse = manager.listConversations("user-A");
        assertTrue(listResponse.getConversations().isEmpty());
    }

    @Test
    public void switchSession_crossUserDoesNotCorruptOtherUser() {
        FakeConversationSessionGateway gateway = new FakeConversationSessionGateway();
        ConversationManager manager = new ConversationManager(gateway);

        ConversationInfo userA = manager.createConversation(requestFor("user-A")).getConversationInfo();
        ConversationInfo userB = manager.createConversation(requestFor("user-B")).getConversationInfo();

        manager.switchConversation("user-B", userB.getSessionId());

        assertEquals(userA.getSessionId(),
                manager.getActiveConversation("user-A").getSessionId());
        assertEquals(userB.getSessionId(),
                manager.getActiveConversation("user-B").getSessionId());
    }

    // ── Test helpers ──

    private static ConversationRequest requestFor(String userId) {
        ConversationRequest request = new ConversationRequest();
        request.setUserId(userId);
        request.setPersonaId("chat");
        request.setTitle("测试对话");
        request.setSourceApp("unit-test");
        request.setTimestamp(1000L);
        return request;
    }

    private static final class FakeConversationSessionGateway implements ConversationSessionGateway {
        private final Map<String, List<SessionMemoryStore.SessionInfo>> sessions = new HashMap<>();
        private int nextId = 1;

        @Override
        public String createConversationSession(String userId, String title,
                                                String personaId, String sourceApp) {
            String sessionId = "S_fake_" + nextId++;
            List<SessionMemoryStore.SessionInfo> list =
                    sessions.computeIfAbsent(userId, ignored -> new ArrayList<>());
            list.replaceAll(info -> copyWithActive(info, false));
            SessionMemoryStore.SessionInfo info = new SessionMemoryStore.SessionInfo(
                    userId, sessionId, 1000L + nextId, null, 0, 0, 0,
                    title, personaId, sourceApp, 1000L + nextId, true);
            list.add(info);
            return sessionId;
        }

        @Override
        public List<SessionMemoryStore.SessionInfo> listSessions(String userId) {
            return sessions.getOrDefault(userId, List.of());
        }

        @Override
        public SessionMemoryStore.SessionInfo getActiveSession(String userId) {
            return listSessions(userId).stream().filter(info -> info.active).findFirst().orElse(null);
        }

        @Override
        public SessionMemoryStore.SessionInfo getSession(String userId, String sessionId) {
            return listSessions(userId).stream()
                    .filter(info -> info.sessionId.equals(sessionId))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public boolean switchSession(String userId, String sessionId) {
            List<SessionMemoryStore.SessionInfo> list = sessions.get(userId);
            if (list == null || getSession(userId, sessionId) == null) {
                return false;
            }
            List<SessionMemoryStore.SessionInfo> updated = new ArrayList<>();
            for (SessionMemoryStore.SessionInfo info : list) {
                updated.add(copyWithActive(info, info.sessionId.equals(sessionId)));
            }
            sessions.put(userId, updated);
            return true;
        }

        @Override
        public boolean deleteSession(String userId, String sessionId) {
            return sessions.getOrDefault(userId, new ArrayList<>())
                    .removeIf(info -> info.sessionId.equals(sessionId));
        }

        private SessionMemoryStore.SessionInfo copyWithActive(SessionMemoryStore.SessionInfo info,
                                                              boolean active) {
            return new SessionMemoryStore.SessionInfo(
                    info.userId, info.sessionId, info.createdAt, info.endedAt,
                    info.messageCount, info.tokenEstimate, info.compressionCount,
                    info.title, info.personaId, info.sourceApp, info.updatedAt, active);
        }
    }
}
