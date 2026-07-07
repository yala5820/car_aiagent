package com.hirain.aiagent.memory;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

/**
 * Session 级记忆存储 — 基于 ChatMemoryStore 扩展，使用 (userId, sessionId) 复合主键。
 * <p>
 * 替代原有 {@code PersistentChatMemorySqlite}，支持：
 * <ul>
 *   <li>多 Session 管理（start/end/列表示过 Session）</li>
 *   <li>Session 元数据追踪（消息数、Token 估算、压缩次数）</li>
 *   <li>多用户隔离（user_id 字段）</li>
 * </ul>
 */
public class SessionMemoryStore implements ChatMemoryStore {

    private static final String TAG = "SessionMemoryStore";
    private static final int DB_VERSION = 2;

    private final SessionDbHelper dbHelper;

    public SessionMemoryStore(Context context) {
        this.dbHelper = new SessionDbHelper(context.getApplicationContext());
    }

    // ── ChatMemoryStore 接口 ──

    /**
     * memoryId 格式："{userId}_{sessionId}"
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String id = memoryId.toString();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        try (Cursor cursor = db.rawQuery(
                "SELECT messages FROM session_messages WHERE memory_id = ?",
                new String[]{id})) {
            if (cursor.moveToFirst()) {
                String json = cursor.getString(0);
                return ChatMessageDeserializer.messagesFromJson(json);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get messages for " + id, e);
        }
        return List.of();
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String id = memoryId.toString();
        String json = ChatMessageSerializer.messagesToJson(messages);
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        try {
            db.execSQL("INSERT OR REPLACE INTO session_messages (memory_id, messages) VALUES (?, ?)",
                    new Object[]{id, json});
        } catch (Exception e) {
            Log.e(TAG, "Failed to update messages for " + id, e);
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String id = memoryId.toString();
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.execSQL("DELETE FROM session_messages WHERE memory_id = ?", new Object[]{id});
    }

    // ── Session 管理 ──

    /** 创建新 Session 记录 */
    public void createSession(String userId, String sessionId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.execSQL(
                "INSERT OR IGNORE INTO sessions (user_id, session_id, created_at, is_active) VALUES (?, ?, ?, 1)",
                new Object[]{userId, sessionId, System.currentTimeMillis()});
    }

    /** 结束 Session（标记为 inactive，记录结束时间） */
    public void endSession(String userId, String sessionId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.execSQL(
                "UPDATE sessions SET ended_at = ?, is_active = 0 WHERE user_id = ? AND session_id = ?",
                new Object[]{System.currentTimeMillis(), userId, sessionId});
    }

    /** 查询用户的活跃 Session */
    public SessionInfo getActiveSession(String userId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        try (Cursor cursor = db.rawQuery(
                "SELECT user_id, session_id, created_at, ended_at, message_count, " +
                        "token_estimate, compression_count, title, persona_id, source_app, " +
                        "updated_at, is_active FROM sessions " +
                        "WHERE user_id = ? AND is_active = 1 ORDER BY created_at DESC LIMIT 1",
                new String[]{userId})) {
            if (cursor.moveToFirst()) {
                return readSessionInfo(cursor);
            }
        }
        return null;
    }

    /** 列出用户的所有 Session */
    public List<SessionInfo> listSessions(String userId) {
        List<SessionInfo> result = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        try (Cursor cursor = db.rawQuery(
                "SELECT user_id, session_id, created_at, ended_at, message_count, " +
                        "token_estimate, compression_count, title, persona_id, source_app, " +
                        "updated_at, is_active FROM sessions " +
                        "WHERE user_id = ? ORDER BY created_at DESC LIMIT 50",
                new String[]{userId})) {
            while (cursor.moveToNext()) {
                result.add(readSessionInfo(cursor));
            }
        }
        return result;
    }

    /** 按 sessionId 查询单条 Session */
    public SessionInfo getSession(String userId, String sessionId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        try (Cursor cursor = db.rawQuery(
                "SELECT user_id, session_id, created_at, ended_at, message_count, " +
                        "token_estimate, compression_count, title, persona_id, source_app, " +
                        "updated_at, is_active FROM sessions " +
                        "WHERE user_id = ? AND session_id = ? LIMIT 1",
                new String[]{userId, sessionId})) {
            if (cursor.moveToFirst()) {
                return readSessionInfo(cursor);
            }
        }
        return null;
    }

    /** 创建新活跃 Session（将同用户其他会话置为 inactive，不写 ended_at） */
    public boolean createActiveSession(String userId, String sessionId,
                                       String title, String personaId, String sourceApp) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            db.execSQL("UPDATE sessions SET is_active = 0 WHERE user_id = ? AND is_active = 1",
                    new Object[]{userId});
            long now = System.currentTimeMillis();
            db.execSQL(
                    "INSERT INTO sessions (user_id, session_id, created_at, ended_at, title, " +
                            "persona_id, source_app, updated_at, is_active) " +
                            "VALUES (?, ?, ?, NULL, ?, ?, ?, ?, 1)",
                    new Object[]{userId, sessionId, now, title, personaId, sourceApp, now});
            db.setTransactionSuccessful();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "createActiveSession failed", e);
            return false;
        } finally {
            db.endTransaction();
        }
    }

    /** 激活指定 Session（将同用户其他会话置为 inactive） */
    public boolean activateSession(String userId, String sessionId) {
        SessionInfo existing = getSession(userId, sessionId);
        if (existing == null) {
            return false;
        }
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            long now = System.currentTimeMillis();
            db.execSQL("UPDATE sessions SET is_active = 0 WHERE user_id = ? AND is_active = 1",
                    new Object[]{userId});
            db.execSQL("UPDATE sessions SET is_active = 1, ended_at = NULL, updated_at = ? " +
                            "WHERE user_id = ? AND session_id = ?",
                    new Object[]{now, userId, sessionId});
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    /** 删除指定 Session（消息+记录） */
    public boolean deleteSession(String userId, String sessionId) {
        SessionInfo existing = getSession(userId, sessionId);
        if (existing == null) {
            return false;
        }
        String memoryIdPrefix = SessionMemoryIds.buildPrefix(userId, sessionId);
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            db.execSQL("DELETE FROM session_messages WHERE memory_id LIKE ?",
                    new Object[]{memoryIdPrefix + "%"});
            db.execSQL("DELETE FROM sessions WHERE user_id = ? AND session_id = ?",
                    new Object[]{userId, sessionId});
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    /** 更新 Session 统计信息 */
    public void updateSessionStats(String userId, String sessionId,
                                    int messageCount, int tokenEstimate, int compressionCount) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.execSQL(
                "UPDATE sessions SET message_count = ?, token_estimate = ?, " +
                        "compression_count = ? WHERE user_id = ? AND session_id = ?",
                new Object[]{messageCount, tokenEstimate, compressionCount, userId, sessionId});
    }

    /** 构建符合 ChatMemoryStore 约定的 memoryId */
    public static String buildMemoryId(String userId, String sessionId) {
        return userId + "_" + sessionId;
    }

    public String currentMemoryId(String userId) {
        SessionInfo session = getActiveSession(userId);
        return session != null ? buildMemoryId(userId, session.sessionId) : null;
    }

    // ── 内部 ──

    private SessionInfo readSessionInfo(Cursor cursor) {
        return new SessionInfo(
                cursor.getString(0),   // userId
                cursor.getString(1),   // sessionId
                cursor.getLong(2),     // createdAt
                cursor.isNull(3) ? null : cursor.getLong(3),  // endedAt
                cursor.getInt(4),      // messageCount
                cursor.getInt(5),      // tokenEstimate
                cursor.getInt(6),      // compressionCount
                cursor.isNull(7) ? null : cursor.getString(7),  // title
                cursor.isNull(8) ? null : cursor.getString(8),  // personaId
                cursor.isNull(9) ? null : cursor.getString(9),  // sourceApp
                cursor.isNull(10) ? 0 : cursor.getLong(10),     // updatedAt
                cursor.getInt(11) == 1                          // is_active
        );
    }

    // ── DB Helper ──

    private static class SessionDbHelper extends SQLiteOpenHelper {

        private static final String DB_NAME = "aiagent_memory.db";

        SessionDbHelper(Context context) {
            super(context, DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL(
                    "CREATE TABLE IF NOT EXISTS sessions (" +
                            "user_id TEXT NOT NULL, " +
                            "session_id TEXT NOT NULL, " +
                            "created_at INTEGER NOT NULL, " +
                            "ended_at INTEGER, " +
                            "message_count INTEGER DEFAULT 0, " +
                            "token_estimate INTEGER DEFAULT 0, " +
                            "compression_count INTEGER DEFAULT 0, " +
                            "title TEXT DEFAULT '新对话', " +
                            "persona_id TEXT DEFAULT 'chat', " +
                            "source_app TEXT, " +
                            "updated_at INTEGER, " +
                            "is_active INTEGER DEFAULT 1, " +
                            "PRIMARY KEY (user_id, session_id))");

            db.execSQL(
                    "CREATE TABLE IF NOT EXISTS session_messages (" +
                            "memory_id TEXT PRIMARY KEY, " +
                            "messages TEXT NOT NULL)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN title TEXT DEFAULT '新对话'");
                db.execSQL("ALTER TABLE sessions ADD COLUMN persona_id TEXT DEFAULT 'chat'");
                db.execSQL("ALTER TABLE sessions ADD COLUMN source_app TEXT");
                db.execSQL("ALTER TABLE sessions ADD COLUMN updated_at INTEGER");
            }
        }
    }

    // ── SessionInfo 值类型 ──

    public static class SessionInfo {
        public final String userId;
        public final String sessionId;
        public final long createdAt;
        public final Long endedAt;
        public final int messageCount;
        public final int tokenEstimate;
        public final int compressionCount;
        public final String title;
        public final String personaId;
        public final String sourceApp;
        public final long updatedAt;
        public final boolean active;

        public SessionInfo(String userId, String sessionId, long createdAt, Long endedAt,
                           int messageCount, int tokenEstimate, int compressionCount,
                           String title, String personaId, String sourceApp,
                           long updatedAt, boolean active) {
            this.userId = userId;
            this.sessionId = sessionId;
            this.createdAt = createdAt;
            this.endedAt = endedAt;
            this.messageCount = messageCount;
            this.tokenEstimate = tokenEstimate;
            this.compressionCount = compressionCount;
            this.title = title;
            this.personaId = personaId;
            this.sourceApp = sourceApp;
            this.updatedAt = updatedAt;
            this.active = active;
        }
    }
}
