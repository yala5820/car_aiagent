package com.hirain.aiagent.conversation;

import com.hirain.aiagent.memory.MemoryOrchestrator;
import com.hirain.aiagent.memory.SessionMemoryStore;

import java.util.List;

public class MemoryConversationSessionGateway implements ConversationSessionGateway {
    private final MemoryOrchestrator memoryOrchestrator;

    public MemoryConversationSessionGateway(MemoryOrchestrator memoryOrchestrator) {
        this.memoryOrchestrator = memoryOrchestrator;
    }

    @Override
    public String createConversationSession(String userId, String title,
                                            String personaId, String sourceApp) {
        return memoryOrchestrator.createConversationSession(userId, title, personaId, sourceApp);
    }

    @Override
    public List<SessionMemoryStore.SessionInfo> listSessions(String userId) {
        return memoryOrchestrator.listSessions(userId);
    }

    @Override
    public SessionMemoryStore.SessionInfo getActiveSession(String userId) {
        return memoryOrchestrator.getActiveSession(userId);
    }

    @Override
    public SessionMemoryStore.SessionInfo getSession(String userId, String sessionId) {
        return memoryOrchestrator.getSession(userId, sessionId);
    }

    @Override
    public boolean switchSession(String userId, String sessionId) {
        return memoryOrchestrator.switchSession(userId, sessionId);
    }

    @Override
    public boolean deleteSession(String userId, String sessionId) {
        return memoryOrchestrator.deleteSession(userId, sessionId);
    }
}
