package com.hirain.aiagent.memory;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * SessionMemoryStore 全局删除语义验证（文档）。
 * <p>
 * 设计原因：短期记忆归属于 sessionId，删除一个 session 时应清除所有 user 的 metadata 和短期消息。
 * 由于 SessionMemoryStore 依赖 Android SQLiteOpenHelper，JVM 单测无法直接运行。
 * 此文件保留为文档，不标记为 @Test 或 @Ignore，避免产生测试覆盖已存在的假象。
 * 在 Phase 6 设备验证时需手动执行此验证场景。
 */
public class SessionMemoryStoreDeleteTest {

    public void globalDeleteRemovesAllMetadataRowsAndSharedMessages() {
        // SessionMemoryStore store = newInMemoryStore();
        // store.createActiveSession("user_a", "shared-session", "A", "chat", "launcher");
        // store.createActiveSession("user_b", "shared-session", "B", "chat", "launcher");
        // store.updateMessages("shared-session", List.of(UserMessage.from("hello")));
        //
        // assertTrue(store.deleteGlobalSession("shared-session"));
        //
        // assertNull(store.getSession("user_a", "shared-session"));
        // assertNull(store.getSession("user_b", "shared-session"));
        // assertTrue(store.getMessages("shared-session").isEmpty());
    }
}
