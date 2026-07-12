package com.hirain.aiagent.memory;

import android.util.Log;

import com.hirain.aiagent.trace.AgentTraceRecorder;

import java.util.List;

import dev.langchain4j.data.message.ChatMessage;

/**
 * 单个用户的所有记忆状态的聚合体。
 * <p>
 * MemoryOrchestrator 以 userId 为 key 管理多个 UserMemoryContext 实例，
 * 每个用户拥有独立的 Session 记忆和长期记忆。
 */
public class UserMemoryContext {

    private final String userId;
    private final SessionManager sessionManager;
    private final MemoryCompressor compressor;
    private final MemoryExtractor extractor;
    private final LongTermMemoryStore longTermStore;

    // 统计信息（不持久化到类字段，仅运行时追踪）
    private int totalTokens;
    private int compressionCount;

    public UserMemoryContext(String userId,
                             SessionManager sessionManager,
                             MemoryCompressor compressor,
                             MemoryExtractor extractor,
                             LongTermMemoryStore longTermStore) {
        this.userId = userId;
        this.sessionManager = sessionManager;
        this.compressor = compressor;
        this.extractor = extractor;
        this.longTermStore = longTermStore;
    }

    // ── Session 管理 ──

    /** 初始化 Session（启动/恢复） */
    public void initSession() {
        sessionManager.getOrCreateSession(userId);
    }

    /** 开启新 Session */
    public void startNewSession() {
        sessionManager.startNewSession(userId);
    }

    /** 结束当前用户的 Session */
    public void endSession() {
        sessionManager.endSession(userId);
    }

    // ── 长期记忆 ──

    /** 获取格式化的长期记忆文本 */
    public String getLongTermContext() {
        return longTermStore.formatAsPromptContext(
                longTermStore.getUserMemories(userId));
    }

    /** 提取并存储长期记忆 */
    public void extractAndStore(String userMessage, String aiResponse) {
        extractAndStore(userMessage, aiResponse, null);
    }

    /** 提取并存储长期记忆，同时把提取过程挂到当前 Agent trace。 */
    public void extractAndStore(String userMessage, String aiResponse, AgentTraceRecorder trace) {
        List<MemoryCandidate> candidates = extractor.extract(userMessage, aiResponse, trace);
        for (MemoryCandidate c : candidates) {
            longTermStore.upsertMemory(userId, c.category(), c.key(), c.value(), c.confidence());
        }
        if (!candidates.isEmpty()) {
            Log.d("UserMemoryContext", "Stored " + candidates.size() + " memory items for " + userId);
        }
    }

    // ── 压缩 ──

    /** 检查并压缩消息列表 */
    public List<ChatMessage> compressIfNeeded(List<ChatMessage> messages, int currentTokens) {
        return compressIfNeeded(messages, currentTokens, null);
    }

    /** 检查并压缩消息列表，同时把压缩决策挂到当前 Agent trace。 */
    public List<ChatMessage> compressIfNeeded(List<ChatMessage> messages, int currentTokens,
                                              AgentTraceRecorder trace) {
        List<ChatMessage> compressed = compressor.compress(messages, currentTokens, trace);
        if (compressed != messages) { // 发生了压缩
            compressionCount++;
        }
        totalTokens = currentTokens;
        return compressed;
    }

    // ── 读取器 ──

    public String userId() { return userId; }
    public int totalTokens() { return totalTokens; }
    public int compressionCount() { return compressionCount; }
    public String currentSessionId() { return sessionManager.currentSessionId(); }
    public String currentMemoryId() { return sessionManager.currentMemoryId(); }
}
