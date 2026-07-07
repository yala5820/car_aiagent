package com.hirain.aiagent.runtime;

import com.hirain.aiagent.CancelRequestResult;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行中请求注册表 — 管理活跃请求的终态抢占。
 * <p>
 * 所有响应发送方（timeout/success/failure/cancel）必须通过
 * tryComplete 抢占终态，只有抢占成功方才允许发送 listener 响应。
 */
public final class ActiveRequestRegistry {

    private final ConcurrentHashMap<String, ActiveRequest> activeRequests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> finishedRequests = new ConcurrentHashMap<>();
    private static final long FINISHED_CACHE_TTL_MS = 60_000L;

    /** 注册新请求，移除同 requestId 的 finished 缓存 */
    public ActiveRequest register(RequestSession session) {
        ActiveRequest active = new ActiveRequest(
                session.requestId(),
                session.sessionId(),
                session.userId(),
                session.personaId(),
                session.clientMessageId(),
                session.startedAtMs());
        finishedRequests.remove(session.requestId());
        activeRequests.put(session.requestId(), active);
        return active;
    }

    /** 取消请求。返回 ACCEPTED / NOT_FOUND / ALREADY_FINISHED */
    public CancelRequestResult cancel(String requestId, String reason, long timestamp) {
        if (requestId == null || requestId.isEmpty()) {
            return CancelRequestResult.notFound(requestId, reason, timestamp);
        }
        ActiveRequest active = activeRequests.get(requestId);
        if (active == null) {
            Long finishedAt = finishedRequests.get(requestId);
            if (finishedAt != null && timestamp - finishedAt <= FINISHED_CACHE_TTL_MS) {
                return CancelRequestResult.alreadyFinished(requestId, reason, timestamp);
            }
            return CancelRequestResult.notFound(requestId, reason, timestamp);
        }
        return active.tryComplete(ActiveRequest.TerminalState.CANCELLED, reason)
                ? CancelRequestResult.accepted(requestId, reason, timestamp)
                : CancelRequestResult.alreadyFinished(requestId, reason, timestamp);
    }

    /** 抢占终态（无 reason），供 timeout/success/failure 使用 */
    public boolean tryComplete(String requestId, ActiveRequest.TerminalState terminalState) {
        ActiveRequest active = activeRequests.get(requestId);
        if (active == null) {
            return false;
        }
        return active.tryComplete(terminalState, null);
    }

    public ActiveRequest get(String requestId) {
        return activeRequests.get(requestId);
    }

    /** 完成请求：移出活跃表，加入 finished 缓存 */
    public void finish(String requestId) {
        if (requestId == null) return;
        ActiveRequest removed = activeRequests.remove(requestId);
        if (removed != null) {
            finishedRequests.put(requestId, System.currentTimeMillis());
        }
    }

    /** 清理过期 finished 缓存 */
    public void cleanupFinished(long now) {
        finishedRequests.entrySet().removeIf(entry -> now - entry.getValue() > FINISHED_CACHE_TTL_MS);
    }
}
