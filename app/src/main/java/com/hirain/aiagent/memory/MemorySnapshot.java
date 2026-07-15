package com.hirain.aiagent.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.langchain4j.data.message.ChatMessage;

/**
 * Context 预留用的只读短期记忆快照。
 * <p>
 * 设计原因：Memory 模块只负责提供当前 session 的记忆数据，不决定这些数据是否进入本轮 prompt。
 */
public final class MemorySnapshot {

    private final String sessionId;
    private final List<ChatMessage> messages;
    private final int tokenEstimate;
    private final String summary;
    private final boolean repaired;
    private final int removedMessageCount;
    private final String repairReason;

    /**
     * 构造短期记忆快照。
     * <p>
     * sessionId 会被转换为 {@code memory:<sessionId>} 格式。
     * 若需要保留原始 sessionId，使用 {@link #rawSessionId(String, List, int, String)}。
     */
    public MemorySnapshot(String sessionId, List<ChatMessage> messages,
                          int tokenEstimate, String summary) {
        this(sessionId, messages, tokenEstimate, summary, false, false, 0, null);
    }

    /**
     * 构造保留原始 sessionId 的短期记忆快照。
     * <p>
     * 设计原因：Context 模块需要使用原始 sessionId 来关联会话，
     * 不需要 {@code memory:} 前缀。只有 Store/Provider 边界才需要调用 {@code shortTermMemoryId()}。
     */
    public MemorySnapshot(String sessionId, List<ChatMessage> messages,
                          int tokenEstimate, String summary, boolean rawSessionId) {
        this(sessionId, messages, tokenEstimate, summary, rawSessionId, false, 0, null);
    }

    public MemorySnapshot(String sessionId, List<ChatMessage> messages,
                          int tokenEstimate, String summary, boolean rawSessionId,
                          boolean repaired, int removedMessageCount, String repairReason) {
        this.sessionId = rawSessionId ? sessionId : SessionMemoryIds.shortTermMemoryId(sessionId);
        this.messages = messages != null
                ? Collections.unmodifiableList(new ArrayList<>(messages))
                : List.of();
        this.tokenEstimate = tokenEstimate;
        this.summary = summary != null ? summary : "";
        this.repaired = repaired;
        this.removedMessageCount = Math.max(0, removedMessageCount);
        this.repairReason = repairReason;
    }

    public String sessionId() { return sessionId; }

    public List<ChatMessage> messages() { return messages; }

    public int messageCount() { return messages.size(); }

    public int tokenEstimate() { return tokenEstimate; }

    /**
     * 返回已有压缩摘要的 best-effort 结果。
     * <p>
     * 设计原因：当前摘要仍存放在普通 UserMessage 中，尚不是稳定结构化字段；
     * Context 阶段不能依赖该字段必定存在。
     */
    public String summary() { return summary; }

    public boolean repaired() { return repaired; }

    public int removedMessageCount() { return removedMessageCount; }

    public String repairReason() { return repairReason; }
}
