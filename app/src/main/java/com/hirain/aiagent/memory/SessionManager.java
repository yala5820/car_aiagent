package com.hirain.aiagent.memory;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Session 生命周期管理器。
 * <p>
 * 规则：
 * <ul>
 *   <li>程序启动时自动创建/恢复活跃 Session</li>
 *   <li>同一 Session 内暂停/唤醒不丢失上下文</li>
 *   <li>用户主动请求"新对话"时创建新 Session</li>
 *   <li>程序关闭时 Session 自动结束（数据保留）</li>
 * </ul>
 */
public class SessionManager {

    private static final String TAG = "SessionManager";

    private final SessionMemoryStore store;
    private final AtomicReference<String> currentUserId = new AtomicReference<>("default_user");
    private volatile String currentSessionId;
    private volatile long sessionStartTimeMs;

    public SessionManager(SessionMemoryStore store) {
        this.store = store;
    }

    /**
     * 获取或创建当前 Session（启动时调用）。
     * 如有活跃 Session 则恢复，否则创建新 Session。
     */
    public String getOrCreateSession(String userId) {
        currentUserId.set(userId);

        // 尝试恢复活跃 Session
        SessionMemoryStore.SessionInfo active = store.getActiveSession(userId);
        if (active != null) {
            currentSessionId = active.sessionId;
            sessionStartTimeMs = active.createdAt;
            Log.d(TAG, "Resumed session " + currentSessionId + " for user " + userId);
            return currentSessionId;
        }

        // 创建新 Session
        return createNewSession(userId);
    }

    /** 主动开启新 Session（用户指令触发） */
    public String startNewSession(String userId) {
        // 结束旧 Session
        if (currentSessionId != null) {
            store.endSession(userId, currentSessionId);
            Log.d(TAG, "Ended session " + currentSessionId);
        }
        return createNewSession(userId);
    }

    /** 结束当前 Session（程序关闭时调用） */
    public void endSession() {
        String userId = currentUserId.get();
        if (currentSessionId != null && userId != null) {
            store.endSession(userId, currentSessionId);
            Log.d(TAG, "Ended session " + currentSessionId + " on shutdown");
        }
        currentSessionId = null;
    }

    // ── 读取器 ──

    public String currentSessionId() { return currentSessionId; }
    public String currentUserId() { return currentUserId.get(); }
    public long sessionDurationMs() {
        return sessionStartTimeMs > 0 ? System.currentTimeMillis() - sessionStartTimeMs : 0;
    }
    public boolean hasActiveSession() { return currentSessionId != null; }

    /** 构建 ChatMemoryStore 用的 memoryId */
    public String currentMemoryId() {
        return currentSessionId != null
                ? SessionMemoryStore.buildMemoryId(currentUserId.get(), currentSessionId)
                : null;
    }

    // ── 内部 ──

    private String createNewSession(String userId) {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(new Date());
        currentSessionId = "S_" + timestamp;
        sessionStartTimeMs = System.currentTimeMillis();
        store.createSession(userId, currentSessionId);
        Log.d(TAG, "Created new session " + currentSessionId + " for user " + userId);
        return currentSessionId;
    }
}
