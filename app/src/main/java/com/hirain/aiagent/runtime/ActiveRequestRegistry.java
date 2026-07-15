package com.hirain.aiagent.runtime;

import com.hirain.aiagent.CancelRequestResult;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 单 Session TEXT 请求注册表。
 * <p>
 * Registry 同时维护一个执行槽位和短期 finished requestId 缓存。终态 CAS 只决定谁发送响应，
 * 槽位必须等 worker 完全退出后由 release 释放，避免旧网络调用尚未结束时新请求进入。
 */
public final class ActiveRequestRegistry {

    private static final long FINISHED_CACHE_TTL_MS = 60_000L;

    private final Map<String, Long> finishedRequests = new HashMap<>();
    private ActiveRequest activeRequest;

    public synchronized RequestAdmissionResult tryAcquire(RequestAdmission admission, long nowMs) {
        if (admission == null) {
            throw new IllegalArgumentException("admission must not be null");
        }
        cleanupFinishedLocked(nowMs);
        if (finishedRequests.containsKey(admission.requestId())) {
            return RequestAdmissionResult.rejected(
                    RequestAdmissionResult.Status.DUPLICATE_FINISHED_REQUEST_ID);
        }
        if (activeRequest != null) {
            RequestAdmissionResult.Status status = activeRequest.requestId().equals(admission.requestId())
                    ? RequestAdmissionResult.Status.DUPLICATE_ACTIVE_REQUEST_ID
                    : RequestAdmissionResult.Status.BUSY;
            return RequestAdmissionResult.rejected(status);
        }
        activeRequest = new ActiveRequest(admission);
        return RequestAdmissionResult.accepted(activeRequest);
    }

    /** 取消请求。终态可立即通知，但此方法不会释放执行槽位。 */
    public synchronized CancelRequestResult cancel(String requestId, String reason, long timestamp) {
        if (requestId == null || requestId.isEmpty()) {
            return CancelRequestResult.notFound(requestId, reason, timestamp);
        }
        if (activeRequest == null || !requestId.equals(activeRequest.requestId())) {
            Long finishedAt = finishedRequests.get(requestId);
            if (finishedAt != null && timestamp - finishedAt <= FINISHED_CACHE_TTL_MS) {
                return CancelRequestResult.alreadyFinished(requestId, reason, timestamp);
            }
            return CancelRequestResult.notFound(requestId, reason, timestamp);
        }
        return activeRequest.tryComplete(ActiveRequest.TerminalState.CANCELLED, reason)
                ? CancelRequestResult.accepted(requestId, reason, timestamp)
                : CancelRequestResult.alreadyFinished(requestId, reason, timestamp);
    }

    public synchronized boolean tryComplete(String requestId,
                                             ActiveRequest.TerminalState terminalState) {
        if (activeRequest == null || requestId == null
                || !requestId.equals(activeRequest.requestId())) {
            return false;
        }
        return activeRequest.tryComplete(terminalState, null);
    }

    public synchronized ActiveRequest get(String requestId) {
        if (activeRequest == null || requestId == null
                || !requestId.equals(activeRequest.requestId())) {
            return null;
        }
        return activeRequest;
    }

    public synchronized ActiveRequest current() {
        return activeRequest;
    }

    /**
     * worker 完全退出后释放槽位并写入 finished 缓存。
     * requestId 不匹配时不释放，防止迟到 finally 清理掉后续请求。
     */
    public synchronized boolean release(String requestId, long nowMs) {
        if (activeRequest == null || requestId == null
                || !requestId.equals(activeRequest.requestId())) {
            return false;
        }
        activeRequest = null;
        finishedRequests.put(requestId, nowMs);
        cleanupFinishedLocked(nowMs);
        return true;
    }

    public synchronized void cleanupFinished(long nowMs) {
        cleanupFinishedLocked(nowMs);
    }

    private void cleanupFinishedLocked(long nowMs) {
        Iterator<Map.Entry<String, Long>> iterator = finishedRequests.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (nowMs - entry.getValue() > FINISHED_CACHE_TTL_MS) {
                iterator.remove();
            }
        }
    }
}
