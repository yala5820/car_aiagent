package com.hirain.aiagent.conversation;

import com.hirain.aiagent.memory.SessionMemoryStore;

import java.util.List;

public interface ConversationSessionGateway {
    String createConversationSession(String userId, String title, String personaId, String sourceApp);
    List<SessionMemoryStore.SessionInfo> listSessions(String userId);
    SessionMemoryStore.SessionInfo getActiveSession(String userId);
    SessionMemoryStore.SessionInfo getSession(String userId, String sessionId);
    boolean switchSession(String userId, String sessionId);
    boolean deleteSession(String userId, String sessionId);
}
