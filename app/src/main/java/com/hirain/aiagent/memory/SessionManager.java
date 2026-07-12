package com.hirain.aiagent.memory;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Session 生命周期管理器 — 多用户隔离版本。
 * <p>
 * 每个 userId 独立维护 active session 状态，互不干扰。
 */
public class SessionManager {

    private static final String TAG = "SessionManager";

    private final SessionMemoryStore store;
    private final AtomicReference<String> currentUserId = new AtomicReference<>("default_user");

    private static final class ActiveSessionState {
        final String sessionId;
        final long sessionStartTimeMs;
        ActiveSessionState(String sessionId, long sessionStartTimeMs) {
            this.sessionId = sessionId;
            this.sessionStartTimeMs = sessionStartTimeMs;
        }
    }

    private final ConcurrentHashMap<String, ActiveSessionState> activeSessions = new ConcurrentHashMap<>();

    public SessionManager(SessionMemoryStore store) {
        this.store = store;
    }

    // ── 按用户读取 ──

    public String currentSessionId(String userId) {
        ActiveSessionState state = activeSessions.get(userId);
        return state != null ? state.sessionId : null;
    }

    public boolean hasActiveSession(String userId) {
        return currentSessionId(userId) != null;
    }

    public String currentMemoryId(String userId) {
        String sessionId = currentSessionId(userId);
        return sessionId != null ? SessionMemoryStore.buildMemoryId(userId, sessionId) : null;
    }

    /** 获取或创建当前 Session（启动时调用）。 */
    public String getOrCreateSession(String userId) {
        currentUserId.set(userId);
        ActiveSessionState cached = activeSessions.get(userId);
        if (cached != null) {
            return cached.sessionId;
        }
        SessionMemoryStore.SessionInfo active = store.getActiveSession(userId);
        if (active != null) {
            activeSessions.put(userId, new ActiveSessionState(active.sessionId, active.createdAt));
            return active.sessionId;
        }
        return createConversationSession(userId, "新对话", "chat", "system");
    }

    /** 主动开启新 Session — 结束旧会话（保留 end_at） */
    public String startNewSession(String userId) {
        String oldSessionId = currentSessionId(userId);
        if (oldSessionId != null) {
            store.endSession(userId, oldSessionId);
        }
        return createConversationSession(userId, "新对话", "chat", "system");
    }

    /** 结束当前 Session（程序关闭时调用） */
    public void endSession(String userId) {
        String sessionId = currentSessionId(userId);
        if (sessionId != null) {
            store.endSession(userId, sessionId);
            activeSessions.remove(userId);
        }
    }

    /** 兼容旧版无参调用（通过 currentUserId 指向的默认用户） */
    @Deprecated
    public void endSession() {
        endSession(currentUserId.get());
    }

    // ── 会话管理（不结束旧会话） ──

    /**
     * 创建新对话会话 — 不结束旧会话。
     * 幂等重试 sessionId 碰撞。
     */
    public String createConversationSession(String userId, String title,
                                            String personaId, String sourceApp) {
        for (int attempt = 0; attempt < 3; attempt++) {
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault())
                    .format(new Date());
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            String sessionId = "S_" + timestamp + "_" + suffix;
            boolean created = store.createActiveSession(userId, sessionId, title, personaId, sourceApp);
            if (created) {
                activeSessions.put(userId, new ActiveSessionState(sessionId, System.currentTimeMillis()));
                Log.d(TAG, "Created conversation session " + sessionId + " for user " + userId);
                return sessionId;
            }
        }
        throw new IllegalStateException("Failed to create unique sessionId for user " + userId);
    }

    /** 切换到指定会话 */
    public boolean switchSession(String userId, String sessionId) {
        boolean activated = store.activateSession(userId, sessionId);
        if (!activated) {
            return false;
        }
        SessionMemoryStore.SessionInfo info = store.getSession(userId, sessionId);
        activeSessions.put(userId, new ActiveSessionState(
                sessionId,
                info != null ? info.createdAt : System.currentTimeMillis()));
        Log.d(TAG, "Switched session " + sessionId + " for user " + userId);
        return true;
    }

    /**
     * 将 sessionId 缓存为 userId 的 active session（不操作 DB）。
     * <p>
     * 设计原因：resolveSessionId 收到显式 sessionId 且 metadata 已创建后，
     * 需要更新 in-memory 缓存使 userId->activeSession 指针一致。
     */
    public void cacheSession(String userId, String sessionId) {
        activeSessions.put(userId, new ActiveSessionState(sessionId, System.currentTimeMillis()));
    }

    // ── 兼容旧读取器 ──

    public String currentSessionId() { return currentSessionId(currentUserId.get()); }
    public String currentUserId() { return currentUserId.get(); }
    public long sessionDurationMs() {
        ActiveSessionState state = activeSessions.get(currentUserId.get());
        return state != null ? System.currentTimeMillis() - state.sessionStartTimeMs : 0;
    }
    public boolean hasActiveSession() { return hasActiveSession(currentUserId.get()); }
    public String currentMemoryId() {
        return currentMemoryId(currentUserId.get());
    }
}
