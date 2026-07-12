package com.hirain.aiagent.memory;

/**
 * Runtime 解析本轮真实 sessionId 的窄接口。
 * <p>
 * 设计原因：Context 构建和响应映射都必须看到 resolvedSessionId，
 * 但 Runtime 不应直接操作 SessionMemoryStore 的表结构。
 */
@FunctionalInterface
public interface SessionIdResolver {

    /**
     * 解析本轮请求的真实 sessionId。
     *
     * @param userId             当前发言人 userId（已规范化）
     * @param requestedSessionId 请求中携带的 sessionId（可能为 null 或空白）
     * @param title              会话标题（通常取请求文本的前几个字）
     * @param personaId          当前使用的 persona
     * @param sourceApp          请求来源应用
     * @return 解析后的非空 sessionId
     */
    String resolveSessionId(String userId, String requestedSessionId,
                            String title, String personaId, String sourceApp);
}
