package com.hirain.aiagent.memory;

/**
 * 长期记忆条目 — 描述一个用户偏好、事实、习惯或规则。
 */
public class MemoryEntry {

    public enum Category {
        PREFERENCE("preference"),
        FACT("fact"),
        HABIT("habit"),
        RULE("rule");

        private final String value;
        Category(String value) { this.value = value; }
        public String value() { return value; }

        public static Category from(String s) {
            for (Category c : values()) {
                if (c.value.equals(s)) return c;
            }
            return FACT;
        }
    }

    private final Category category;
    private final String key;
    private final String value;
    private final float confidence;
    private final long updatedAt;
    private final int accessCount;

    public MemoryEntry(Category category, String key, String value,
                       float confidence, long updatedAt, int accessCount) {
        this.category = category;
        this.key = key;
        this.value = value;
        this.confidence = confidence;
        this.updatedAt = updatedAt;
        this.accessCount = accessCount;
    }

    public Category category() { return category; }
    public String key() { return key; }
    public String value() { return value; }
    public float confidence() { return confidence; }
    public long updatedAt() { return updatedAt; }
    public int accessCount() { return accessCount; }

    public String categoryValue() { return category.value(); }
}
