package com.hirain.aiagent.context;

/**
 * 粗粒度 Context 字符和 Token 预算管理。
 * <p>
 * 设计原因：在 Provider 阶段调用 trim 和 estimateTokens 进行预算裁剪，
 * 避免单条 section 或汇总 context 超出模型输入窗口。
 */
public final class ContextBudgetManager {

    public static final int DEFAULT_SECTION_CHAR_LIMIT = 800;
    public static final int DEFAULT_MEMORY_CHAR_LIMIT = 500;
    public static final int DEFAULT_TOOL_CONTEXT_CHAR_LIMIT = 1200;
    public static final int DEFAULT_TOTAL_CHAR_LIMIT = 3000;

    private final int sectionCharLimit;
    private final int memoryCharLimit;
    private final int toolContextCharLimit;
    private final int totalCharLimit;

    public ContextBudgetManager(int sectionCharLimit,
                                int memoryCharLimit,
                                int toolContextCharLimit,
                                int totalCharLimit) {
        this.sectionCharLimit = sectionCharLimit;
        this.memoryCharLimit = memoryCharLimit;
        this.toolContextCharLimit = toolContextCharLimit;
        this.totalCharLimit = totalCharLimit;
    }

    public static ContextBudgetManager defaultBudget() {
        return new ContextBudgetManager(
                DEFAULT_SECTION_CHAR_LIMIT,
                DEFAULT_MEMORY_CHAR_LIMIT,
                DEFAULT_TOOL_CONTEXT_CHAR_LIMIT,
                DEFAULT_TOTAL_CHAR_LIMIT);
    }

    public int sectionCharLimit() { return sectionCharLimit; }
    public int memoryCharLimit() { return memoryCharLimit; }
    public int toolContextCharLimit() { return toolContextCharLimit; }
    public int totalCharLimit() { return totalCharLimit; }

    /**
     * 裁剪字符串到指定长度，并标记是否被截断。
     */
    public TrimmedText trim(String value, int limit) {
        String safe = value != null ? value : "";
        if (safe.length() <= limit) return new TrimmedText(safe, false);
        return new TrimmedText(safe.substring(0, limit), true);
    }

    /**
     * 粗粒度字符 → Token 估算（约 2 字符 = 1 token）。
     */
    public int estimateTokens(String text) {
        String safe = text != null ? text : "";
        return (safe.length() + 1) / 2;
    }

    /**
     * 裁剪结果 — 包含裁剪后文本和截断标记。
     */
    public static final class TrimmedText {
        private final String text;
        private final boolean truncated;

        public TrimmedText(String text, boolean truncated) {
            this.text = text;
            this.truncated = truncated;
        }

        public String text() { return text; }
        public boolean truncated() { return truncated; }
    }
}
