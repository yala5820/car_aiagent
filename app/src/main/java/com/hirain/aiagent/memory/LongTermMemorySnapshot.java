package com.hirain.aiagent.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 长期记忆快照 — 向 Context 提供稳定、结构化的长期记忆条目。
 * <p>
 * 设计原因：新 Provider 读取结构化快照，禁止调用 {@code prepareSystemPrompt()} 产生角色混合字符串。
 * userId 保持原始值（不添加前缀），由 Consumer 在存储边界自行处理。
 */
public final class LongTermMemorySnapshot {

    private final String userId;
    private final List<MemoryEntry> entries;
    private final long contentVersion;

    public LongTermMemorySnapshot(String userId,
                                   List<MemoryEntry> entries,
                                   long contentVersion) {
        this.userId = userId;
        this.entries = entries != null
                ? Collections.unmodifiableList(new ArrayList<>(entries))
                : List.of();
        this.contentVersion = contentVersion;
    }

    /** 原始 userId。 */
    public String userId() { return userId; }

    /** 按 Category、key 稳定排序的不可变条目列表。 */
    public List<MemoryEntry> entries() { return entries; }

    /** 由条目稳定字段计算的内容版本号（用于 token estimate cache）。 */
    public long contentVersion() { return contentVersion; }

    /** 是否有条目。 */
    public boolean isEmpty() { return entries.isEmpty(); }

    /** 条目数量。 */
    public int entryCount() { return entries.size(); }
}
