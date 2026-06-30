package com.hirain.aiagent.memory;

/**
 * 记忆提取候选 — LLM 分析后建议写入长期记忆的信息。
 */
public class MemoryCandidate {

    private final MemoryEntry.Category category;
    private final String key;
    private final String value;
    private final float confidence;

    public MemoryCandidate(MemoryEntry.Category category, String key,
                           String value, float confidence) {
        this.category = category;
        this.key = key;
        this.value = value;
        this.confidence = confidence;
    }

    public MemoryEntry.Category category() { return category; }
    public String key() { return key; }
    public String value() { return value; }
    public float confidence() { return confidence; }
}
