package com.hirain.aiagent.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

/**
 * 不按消息数量淘汰历史的 Session ChatMemory。
 * <p>
 * TEXT 模型输入的取舍由 Context Token 预算负责；Memory 写入层必须保留完整历史，
 * 否则窗口淘汰可能把一个 ConversationTurn 或 ToolExchange 从中间切断。
 */
public final class PersistentSessionChatMemory implements ChatMemory {

    private final Object memoryId;
    private final ChatMemoryStore store;
    private final Object sessionLock;

    public PersistentSessionChatMemory(Object memoryId, ChatMemoryStore store, Object sessionLock) {
        if (memoryId == null) throw new IllegalArgumentException("memoryId must not be null");
        if (store == null) throw new IllegalArgumentException("store must not be null");
        this.memoryId = memoryId;
        this.store = store;
        this.sessionLock = sessionLock != null ? sessionLock : new Object();
    }

    @Override
    public Object id() {
        return memoryId;
    }

    @Override
    public void add(ChatMessage message) {
        if (message == null) throw new IllegalArgumentException("message must not be null");
        synchronized (sessionLock) {
            List<ChatMessage> updated = new ArrayList<>(store.getMessages(memoryId));
            updated.add(message);
            replaceOrThrow(updated);
        }
    }

    @Override
    public void set(Iterable<ChatMessage> messages) {
        synchronized (sessionLock) {
            List<ChatMessage> replacement = new ArrayList<>();
            if (messages != null) {
                for (ChatMessage message : messages) {
                    if (message == null) throw new IllegalArgumentException("message must not be null");
                    replacement.add(message);
                }
            }
            replaceOrThrow(replacement);
        }
    }

    @Override
    public List<ChatMessage> messages() {
        synchronized (sessionLock) {
            return Collections.unmodifiableList(new ArrayList<>(store.getMessages(memoryId)));
        }
    }

    @Override
    public void clear() {
        synchronized (sessionLock) {
            store.deleteMessages(memoryId);
        }
    }

    void replaceAll(List<ChatMessage> messages) {
        synchronized (sessionLock) {
            replaceOrThrow(messages != null ? new ArrayList<>(messages) : List.of());
        }
    }

    Object sessionLock() {
        return sessionLock;
    }

    private void replaceOrThrow(List<ChatMessage> messages) {
        if (store instanceof SessionMemoryStore) {
            ((SessionMemoryStore) store).replaceMessagesOrThrow(memoryId, messages);
        } else {
            store.updateMessages(memoryId, messages);
        }
    }
}
