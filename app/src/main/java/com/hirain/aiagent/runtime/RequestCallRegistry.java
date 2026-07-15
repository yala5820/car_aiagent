package com.hirain.aiagent.runtime;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Call;

/**
 * requestId 到当前同步模型 HTTP Call 的临时映射。
 * <p>
 * 取消标记会先于 Call 存在：如果 cancel/timeout 恰好发生在 Call 创建和登记之间，
 * 后续 register 仍会立即取消该 Call，避免出现竞态漏取消。
 */
public final class RequestCallRegistry {

    private final ConcurrentHashMap<String, Call> activeCalls = new ConcurrentHashMap<>();
    private final Set<String> cancellationRequested = ConcurrentHashMap.newKeySet();

    public Registration register(String requestId, Call call) {
        if (requestId == null || requestId.trim().isEmpty()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        if (call == null) {
            throw new IllegalArgumentException("call must not be null");
        }
        Call previous = activeCalls.put(requestId, call);
        if (previous != null && previous != call) {
            previous.cancel();
        }
        if (cancellationRequested.contains(requestId)) {
            call.cancel();
        }
        return new Registration(this, requestId, call);
    }

    public boolean cancel(String requestId) {
        if (requestId == null || requestId.isEmpty()) return false;
        cancellationRequested.add(requestId);
        Call call = activeCalls.get(requestId);
        if (call != null) {
            call.cancel();
            return true;
        }
        return false;
    }

    /** worker 完全退出后清除本请求的 Call 与提前取消标记。 */
    public void clear(String requestId) {
        if (requestId == null) return;
        Call call = activeCalls.remove(requestId);
        if (call != null && !call.isCanceled()) {
            call.cancel();
        }
        cancellationRequested.remove(requestId);
    }

    public boolean hasActiveCall(String requestId) {
        return requestId != null && activeCalls.containsKey(requestId);
    }

    public boolean isCancellationRequested(String requestId) {
        return requestId != null && cancellationRequested.contains(requestId);
    }

    private void unregister(String requestId, Call call) {
        activeCalls.remove(requestId, call);
    }

    public static final class Registration implements AutoCloseable {
        private final RequestCallRegistry registry;
        private final String requestId;
        private final Call call;
        private boolean closed;

        private Registration(RequestCallRegistry registry, String requestId, Call call) {
            this.registry = registry;
            this.requestId = requestId;
            this.call = call;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            registry.unregister(requestId, call);
        }
    }
}
