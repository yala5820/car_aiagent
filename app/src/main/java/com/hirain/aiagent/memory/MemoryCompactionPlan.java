package com.hirain.aiagent.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.langchain4j.data.message.ChatMessage;

/**
 * Memory 压缩计划 — 由 {@code planSessionCompaction()} 生成，不调用摘要模型、不写 Store。
 * <p>
 * 设计原因：Context 只提供目标预算，不实现摘要算法。
 * Phase 4 生产影子只生成 Plan，不调用模型/Store。
 */
public final class MemoryCompactionPlan {

    private final String sessionId;
    private final int targetTokens;
    private final int currentTokens;
    private final List<ChatMessage> proposedMessages;
    private final int estimatedTokens;

    public MemoryCompactionPlan(String sessionId, int targetTokens,
                                 int currentTokens,
                                 List<ChatMessage> proposedMessages,
                                 int estimatedTokens) {
        this.sessionId = sessionId;
        this.targetTokens = targetTokens;
        this.currentTokens = currentTokens;
        this.proposedMessages = proposedMessages != null
                ? Collections.unmodifiableList(new ArrayList<>(proposedMessages))
                : List.of();
        this.estimatedTokens = estimatedTokens;
    }

    public String sessionId() { return sessionId; }
    public int targetTokens() { return targetTokens; }
    public int currentTokens() { return currentTokens; }
    public List<ChatMessage> proposedMessages() { return proposedMessages; }
    public int estimatedTokens() { return estimatedTokens; }
    public boolean isCompressionNeeded() { return estimatedTokens < currentTokens; }
}
