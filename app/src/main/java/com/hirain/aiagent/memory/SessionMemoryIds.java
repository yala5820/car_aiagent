package com.hirain.aiagent.memory;

/**
 * Session 内存 ID 工具 — 统一 memoryId 生成规则。
 * <p>
 * 短期记忆 key 只由 sessionId 决定，不参与 userId 或 personaId。
 * 旧格式 {@code "{userId}_{sessionId}_{personaId}"} 已废弃，保留仅作兼容。
 */
public final class SessionMemoryIds {
    private SessionMemoryIds() {}

    /**
     * 短期记忆 key：仅由 sessionId 决定。
     * 设计原因：短期记忆归属于座舱会话 sessionId，不是 userId 或 personaId。
     *
     * @param sessionId Runtime 已解析的非空 sessionId
     * @return 规范化短期 memoryId
     * @throws IllegalArgumentException sessionId 为 null 或空白
     */
    public static String shortTermMemoryId(String sessionId) {
        if (isBlank(sessionId)) {
            throw new IllegalArgumentException(
                    "sessionId must be resolved before building short-term memory id");
        }
        return sessionId.trim();
    }

    /**
     * 短期记忆前缀：与 {@link #shortTermMemoryId(String)} 返回值相同。
     * 设计原因：短期记忆 key 已收敛为 sessionId 本身，不再需要 LIKE 前缀匹配。
     */
    public static String shortTermMemoryPrefix(String sessionId) {
        return shortTermMemoryId(sessionId);
    }

    /**
     * 兼容旧调用；短期记忆不再使用 userId/personaId 参与 key。
     */
    @Deprecated
    public static String build(String userId, String sessionId, String personaId) {
        return shortTermMemoryId(sessionId);
    }

    /**
     * 兼容旧删除逻辑；调用方应传入 sessionId。
     */
    @Deprecated
    public static String buildPrefix(String userId, String sessionId) {
        return shortTermMemoryPrefix(sessionId);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
