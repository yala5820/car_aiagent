package com.hirain.aiagent.runtime;

/**
 * Runtime 之前创建的 TEXT 请求准入快照。
 * <p>
 * 该对象只包含完成单槽位判断和早期响应所需的身份信息，避免为了判断 BUSY
 * 先执行 IntentRouter、ToolGroupSelector 或 Context 相关逻辑。
 */
public final class RequestAdmission {

    private final String requestId;
    private final String sessionId;
    private final String userId;
    private final String personaId;
    private final String clientMessageId;
    private final RequestDeadline deadline;

    public RequestAdmission(String requestId,
                            String sessionId,
                            String userId,
                            String personaId,
                            String clientMessageId,
                            RequestDeadline deadline) {
        if (requestId == null || requestId.trim().isEmpty()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        if (deadline == null) {
            throw new IllegalArgumentException("deadline must not be null");
        }
        this.requestId = requestId;
        this.sessionId = sessionId;
        this.userId = userId;
        this.personaId = personaId;
        this.clientMessageId = clientMessageId;
        this.deadline = deadline;
    }

    public String requestId() { return requestId; }
    public String sessionId() { return sessionId; }
    public String userId() { return userId; }
    public String personaId() { return personaId; }
    public String clientMessageId() { return clientMessageId; }
    public RequestDeadline deadline() { return deadline; }
}
