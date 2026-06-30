package com.hirain.aiagent.memory;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆存储 — 保存跨 Session 的用户偏好、习惯、重要事实。
 * <p>
 * 每轮对话后通过 {@link MemoryExtractor} 提取信息，写入此存储；
 * 每次 Agent 执行前读取并注入 SystemMessage。
 */
public class LongTermMemoryStore extends SQLiteOpenHelper {

    private static final String TAG = "LongTermMemoryStore";
    private static final String DB_NAME = "aiagent_longterm.db";
    private static final int DB_VERSION = 1;

    public LongTermMemoryStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS long_term_memory (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "user_id TEXT NOT NULL, " +
                        "category TEXT NOT NULL, " +
                        "key_text TEXT NOT NULL, " +
                        "value_text TEXT NOT NULL, " +
                        "confidence REAL DEFAULT 1.0, " +
                        "created_at INTEGER NOT NULL, " +
                        "updated_at INTEGER NOT NULL, " +
                        "access_count INTEGER DEFAULT 0, " +
                        "UNIQUE(user_id, category, key_text))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS long_term_memory");
        onCreate(db);
    }

    // ── 写操作 ──

    /** 添加或更新一条记忆 */
    public void upsertMemory(String userId, MemoryEntry.Category category,
                              String key, String value, float confidence) {
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();
        try {
            db.execSQL(
                    "INSERT OR REPLACE INTO long_term_memory " +
                            "(user_id, category, key_text, value_text, confidence, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, COALESCE((SELECT created_at FROM long_term_memory " +
                            "WHERE user_id = ? AND category = ? AND key_text = ?), ?), ?)",
                    new Object[]{
                            userId, category.value(), key, value, confidence,
                            userId, category.value(), key, now, now
                    });
        } catch (Exception e) {
            Log.e(TAG, "Failed to upsert memory", e);
        }
    }

    /** 增加访问计数 */
    public void incrementAccess(String userId, MemoryEntry.Category category, String key) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL(
                "UPDATE long_term_memory SET access_count = access_count + 1 " +
                        "WHERE user_id = ? AND category = ? AND key_text = ?",
                new Object[]{userId, category.value(), key});
    }

    /** 移除单条记忆 */
    public void deleteMemory(String userId, MemoryEntry.Category category, String key) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL(
                "DELETE FROM long_term_memory WHERE user_id = ? AND category = ? AND key_text = ?",
                new Object[]{userId, category.value(), key});
    }

    /** 清除用户所有记忆 */
    public void clearUserMemories(String userId) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("DELETE FROM long_term_memory WHERE user_id = ?",
                new Object[]{userId});
    }

    // ── 读操作 ──

    /** 获取用户所有记忆（按类别分组） */
    public Map<String, List<MemoryEntry>> getUserMemories(String userId) {
        Map<String, List<MemoryEntry>> result = new LinkedHashMap<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.rawQuery(
                "SELECT category, key_text, value_text, confidence, updated_at, access_count " +
                        "FROM long_term_memory WHERE user_id = ? " +
                        "ORDER BY confidence DESC, updated_at DESC LIMIT 100",
                new String[]{userId})) {
            while (cursor.moveToNext()) {
                MemoryEntry entry = readEntry(cursor);
                result.computeIfAbsent(entry.categoryValue(), k -> new ArrayList<>()).add(entry);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to read memories", e);
        }
        return result;
    }

    /** 格式化记忆为 SystemMessage 可注入的文本 */
    public String formatAsPromptContext(Map<String, List<MemoryEntry>> memories) {
        if (memories.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("\n\n【长期记忆】");
        for (Map.Entry<String, List<MemoryEntry>> group : memories.entrySet()) {
            String cnLabel = switch (group.getKey()) {
                case "preference" -> "用户偏好";
                case "fact"      -> "已知事实";
                case "habit"     -> "行为习惯";
                case "rule"      -> "用户规则";
                default          -> group.getKey();
            };
            sb.append("\n").append(cnLabel).append("：");
            for (MemoryEntry entry : group.getValue()) {
                if (entry.confidence() >= 0.5f) {
                    sb.append("\n  · ").append(entry.value());
                }
            }
        }
        return sb.toString();
    }

    // ── 内部 ──

    private MemoryEntry readEntry(Cursor c) {
        return new MemoryEntry(
                MemoryEntry.Category.from(c.getString(0)),
                c.getString(1),
                c.getString(2),
                (float) c.getDouble(3),
                c.getLong(4),
                c.getInt(5)
        );
    }
}
