package com.hirain.aiagent.memory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

/**
 * Session 级 ChatMemory 提供者。
 * <p>
 * 设计原因：短期记忆归属于座舱会话 sessionId，而不是 userId 或 personaId；
 * LangChain4j 继续负责 ChatMemory 窗口语义，AIAgent 负责选择和持久化 session key。
 */
public class SessionChatMemoryProvider {

    private static final int DEFAULT_MAX_CACHED_SESSIONS = 50;

    private final ChatMemoryStore store;
    private final int defaultMaxMessages;
    private final Map<String, ChatMemory> cache;

    public SessionChatMemoryProvider(ChatMemoryStore store, int defaultMaxMessages) {
        this(store, defaultMaxMessages, DEFAULT_MAX_CACHED_SESSIONS);
    }

    public SessionChatMemoryProvider(ChatMemoryStore store, int defaultMaxMessages, int maxCachedSessions) {
        this.store = store;
        this.defaultMaxMessages = Math.max(1, defaultMaxMessages);
        int safeMaxCachedSessions = Math.max(1, maxCachedSessions);
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ChatMemory> eldest) {
                return size() > safeMaxCachedSessions;
            }
        };
    }

    /**
     * 获取或创建指定 session 的 ChatMemory（使用默认窗口大小）。
     */
    public synchronized ChatMemory getOrCreate(String sessionId) {
        return getOrCreate(sessionId, defaultMaxMessages);
    }

    /**
     * 获取或创建指定 session 的 ChatMemory（指定窗口大小）。
     * <p>
     * 设计原因：TEXT 主路径应传入 {@code config.maxMemoryMessages()}；
     * 后续如需不同 persona/场景窗口，应把窗口大小固化为 session memory policy，
     * 而不是让同一 session 在不同轮次切换窗口。
     */
    public synchronized ChatMemory getOrCreate(String sessionId, int maxMessages) {
        String memoryId = SessionMemoryIds.shortTermMemoryId(sessionId);
        ChatMemory existing = cache.get(memoryId);
        if (existing != null) {
            return existing;
        }
        ChatMemory created = MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(Math.max(1, maxMessages))
                .chatMemoryStore(store)
                .build();
        cache.put(memoryId, created);
        return created;
    }

    /**
     * 替换指定 session 的短期消息（压缩写回使用）。
     * <p>
     * 使用 {@code replaceMessagesOrThrow} 单次原子写入，成功后失效旧缓存。
     * 失败时保持 cache 和 store 不变。
     */
    public synchronized void replaceMessages(String sessionId, List<ChatMessage> messages) {
        String memoryId = SessionMemoryIds.shortTermMemoryId(sessionId);
        if (store instanceof SessionMemoryStore) {
            ((SessionMemoryStore) store).replaceMessagesOrThrow(memoryId, messages);
        } else {
            // 回退到旧行为（非 SessionMemoryStore 场景）
            ChatMemory memory = getOrCreate(sessionId);
            memory.clear();
            for (ChatMessage message : messages) {
                memory.add(message);
            }
            return;
        }
        // 成功后失效缓存
        cache.remove(memoryId);
    }

    /**
     * 清除指定 session 的短期消息和缓存。
     */
    public synchronized void clear(String sessionId) {
        String memoryId = SessionMemoryIds.shortTermMemoryId(sessionId);
        cache.remove(memoryId);
        store.deleteMessages(memoryId);
    }
}
