package com.hirain.aiagent.memory;

/**
 * Session 内存 ID 工具 — 统一 memoryId 生成规则。
 * <p>
 * 格式：memoryId = "{userId}_{sessionId}_{personaId}"
 * 前缀 = "{userId}_{sessionId}_"
 */
public final class SessionMemoryIds {
    private SessionMemoryIds() {}

    public static String build(String userId, String sessionId, String personaId) {
        String safeUser = isBlank(userId) ? "default_user" : userId;
        String safeSession = isBlank(sessionId) ? "default_session" : sessionId;
        String safePersona = isBlank(personaId) ? "chat" : personaId;
        return safeUser + "_" + safeSession + "_" + safePersona;
    }

    public static String buildPrefix(String userId, String sessionId) {
        String safeUser = isBlank(userId) ? "default_user" : userId;
        String safeSession = isBlank(sessionId) ? "default_session" : sessionId;
        return safeUser + "_" + safeSession + "_";
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
