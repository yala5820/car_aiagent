package com.hirain.aiagent.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.langchain4j.data.message.ChatMessage;

/**
 * Memory 压缩计划 — 由 {@code planSessionCompaction()} 生成，不调用摘要模型、不写 Store。
 * <p>
 * 设计原因：Context 只提供目标预算，不实现摘要算法。
 * 计划携带原始快照和完整 turn 边界，执行阶段通过 CAS 防止覆盖并发更新。
 */
public final class MemoryCompactionPlan {

    private final String sessionId;
    private final int targetTokens;
    private final int currentTokens;
    private final List<ChatMessage> proposedMessages;
    private final int estimatedTokens;
    private final String originalSnapshotFingerprint;
    private final List<ChatMessage> originalMessages;
    private final String existingSummary;
    private final List<ChatMessage> compactableMessages;
    private final List<ChatMessage> protectedMessages;

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
        this.originalSnapshotFingerprint = "";
        this.originalMessages = List.of();
        this.existingSummary = "";
        this.compactableMessages = List.of();
        this.protectedMessages = this.proposedMessages;
    }

    public MemoryCompactionPlan(String sessionId, int targetTokens, int currentTokens,
                                String originalSnapshotFingerprint,
                                List<ChatMessage> originalMessages,
                                String existingSummary,
                                List<ChatMessage> compactableMessages,
                                List<ChatMessage> protectedMessages) {
        this.sessionId = sessionId;
        this.targetTokens = targetTokens;
        this.currentTokens = currentTokens;
        this.originalSnapshotFingerprint = originalSnapshotFingerprint != null
                ? originalSnapshotFingerprint : "";
        this.originalMessages = immutable(originalMessages);
        this.existingSummary = existingSummary != null ? existingSummary : "";
        this.compactableMessages = immutable(compactableMessages);
        this.protectedMessages = immutable(protectedMessages);
        this.proposedMessages = List.of();
        this.estimatedTokens = currentTokens;
    }

    public String sessionId() { return sessionId; }
    public int targetTokens() { return targetTokens; }
    public int currentTokens() { return currentTokens; }
    public List<ChatMessage> proposedMessages() { return proposedMessages; }
    public int estimatedTokens() { return estimatedTokens; }
    public boolean isCompressionNeeded() {
        return hasCompactableHistory() || estimatedTokens < currentTokens;
    }
    public String originalSnapshotFingerprint() { return originalSnapshotFingerprint; }
    public List<ChatMessage> originalMessages() { return originalMessages; }
    public String existingSummary() { return existingSummary; }
    public List<ChatMessage> compactableMessages() { return compactableMessages; }
    public List<ChatMessage> protectedMessages() { return protectedMessages; }
    public boolean hasCompactableHistory() { return !compactableMessages.isEmpty(); }

    private static List<ChatMessage> immutable(List<ChatMessage> messages) {
        return messages != null
                ? Collections.unmodifiableList(new ArrayList<>(messages)) : List.of();
    }
}
