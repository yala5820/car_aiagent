package com.hirain.aiagent.context;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 单个上下文片段 — 由 ContextProvider 构建，不可变。
 */
public final class ContextSection {

    private final ContextSectionType type;
    private final String providerName;
    private final boolean renderable;
    private final String content;
    private final int charCount;
    private final boolean truncated;
    private final Map<String, Object> metadata;

    public ContextSection(ContextSectionType type, String providerName,
                          boolean renderable, String content,
                          int charCount, boolean truncated,
                          Map<String, Object> metadata) {
        this.type = type;
        this.providerName = providerName;
        this.renderable = renderable;
        this.content = content != null ? content : "";
        this.charCount = charCount;
        this.truncated = truncated;
        this.metadata = Collections.unmodifiableMap(new HashMap<>(
                metadata != null ? metadata : Map.of()));
    }

    public ContextSectionType type() { return type; }
    public String providerName() { return providerName; }
    public boolean renderable() { return renderable; }
    public String content() { return content; }
    public int charCount() { return charCount; }
    public boolean truncated() { return truncated; }
    public Map<String, Object> metadata() { return metadata; }
}
