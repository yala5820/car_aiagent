package com.hirain.aiagent.conversation;

import com.hirain.aiagent.ConversationInfo;
import com.hirain.aiagent.ConversationListResponse;
import com.hirain.aiagent.ConversationOperationResult;
import com.hirain.aiagent.ConversationRequest;
import com.hirain.aiagent.memory.SessionMemoryStore;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 会话管理门面 — 面向 AIDL 层提供 create/list/delete/switch/getActive 能力。
 */
public class ConversationManager {

    private final ConversationSessionGateway sessionGateway;

    public ConversationManager(ConversationSessionGateway sessionGateway) {
        this.sessionGateway = sessionGateway;
    }

    // ── 创建 ──

    public ConversationOperationResult createConversation(ConversationRequest request) {
        String userId = normalize(request != null ? request.getUserId() : null, ConversationConstants.DEFAULT_USER_ID);
        String personaId = normalize(request != null ? request.getPersonaId() : null, ConversationConstants.DEFAULT_PERSONA_ID);
        String title = normalize(request != null ? request.getTitle() : null, "新对话");
        String sourceApp = normalize(request != null ? request.getSourceApp() : null, "unknown");
        String sessionId = sessionGateway.createConversationSession(userId, title, personaId, sourceApp);
        SessionMemoryStore.SessionInfo sessionInfo = sessionGateway.getSession(userId, sessionId);
        return ConversationOperationResult.success(
                ConversationConstants.OP_CREATE,
                toConversationInfo(sessionInfo, personaId, title));
    }

    // ── 列表 ──

    public ConversationListResponse listConversations(String userId) {
        String normalizedUserId = normalize(userId, ConversationConstants.DEFAULT_USER_ID);
        ArrayList<ConversationInfo> infos = sessionGateway.listSessions(normalizedUserId).stream()
                .map(info -> toConversationInfo(info, info.personaId, info.title))
                .collect(Collectors.toCollection(ArrayList::new));
        return ConversationListResponse.success(normalizedUserId, infos);
    }

    // ── 删除 ──

    public ConversationOperationResult deleteConversation(String userId, String sessionId) {
        String normalizedUserId = normalize(userId, ConversationConstants.DEFAULT_USER_ID);
        if (isBlank(sessionId)) {
            return ConversationOperationResult.failure(
                    ConversationConstants.OP_DELETE,
                    ConversationConstants.ERROR_INVALID_ARGUMENT,
                    "sessionId 不能为空");
        }
        boolean deleted = sessionGateway.deleteSession(normalizedUserId, sessionId);
        if (!deleted) {
            return ConversationOperationResult.failure(
                    ConversationConstants.OP_DELETE,
                    ConversationConstants.ERROR_NOT_FOUND,
                    "对话不存在");
        }
        ConversationInfo info = new ConversationInfo();
        info.setUserId(normalizedUserId);
        info.setSessionId(sessionId);
        info.setActive(false);
        return ConversationOperationResult.success(ConversationConstants.OP_DELETE, info);
    }

    // ── 切换 ──

    public ConversationOperationResult switchConversation(String userId, String sessionId) {
        String normalizedUserId = normalize(userId, ConversationConstants.DEFAULT_USER_ID);
        if (isBlank(sessionId)) {
            return ConversationOperationResult.failure(
                    ConversationConstants.OP_SWITCH,
                    ConversationConstants.ERROR_INVALID_ARGUMENT,
                    "sessionId 不能为空");
        }
        boolean switched = sessionGateway.switchSession(normalizedUserId, sessionId);
        if (!switched) {
            return ConversationOperationResult.failure(
                    ConversationConstants.OP_SWITCH,
                    ConversationConstants.ERROR_NOT_FOUND,
                    "对话不存在");
        }
        SessionMemoryStore.SessionInfo sessionInfo = sessionGateway.getSession(normalizedUserId, sessionId);
        return ConversationOperationResult.success(
                ConversationConstants.OP_SWITCH,
                toConversationInfo(sessionInfo, sessionInfo.personaId, sessionInfo.title));
    }

    // ── 查询活跃 ──

    public ConversationInfo getActiveConversation(String userId) {
        String normalizedUserId = normalize(userId, ConversationConstants.DEFAULT_USER_ID);
        SessionMemoryStore.SessionInfo sessionInfo = sessionGateway.getActiveSession(normalizedUserId);
        if (sessionInfo == null) {
            return null;
        }
        return toConversationInfo(sessionInfo, sessionInfo.personaId, sessionInfo.title);
    }

    // ── 映射 ──

    private ConversationInfo toConversationInfo(SessionMemoryStore.SessionInfo sessionInfo,
                                                String personaId,
                                                String title) {
        if (sessionInfo == null) {
            return null;
        }
        ConversationInfo info = new ConversationInfo();
        info.setUserId(sessionInfo.userId);
        info.setSessionId(sessionInfo.sessionId);
        info.setPersonaId(normalize(personaId != null ? personaId : sessionInfo.personaId,
                ConversationConstants.DEFAULT_PERSONA_ID));
        info.setTitle(isBlank(title) ? normalize(sessionInfo.title, "新对话") : title);
        info.setActive(sessionInfo.active);
        info.setCreatedAt(sessionInfo.createdAt);
        info.setUpdatedAt(sessionInfo.updatedAt);
        info.setEndedAt(sessionInfo.endedAt != null ? sessionInfo.endedAt : 0L);
        info.setMessageCount(sessionInfo.messageCount);
        info.setTokenEstimate(sessionInfo.tokenEstimate);
        info.setCompressionCount(sessionInfo.compressionCount);
        return info;
    }

    // ── 工具 ──

    private static String normalize(String value, String fallback) {
        return (value != null && !value.trim().isEmpty()) ? value.trim() : fallback;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
