package com.hirain.aiagent.context;

/**
 * 模型 Token 预算策略 — 从受控配置注入，不读取网络或本地密钥文件。
 * <p>
 * maxInputTokens = maxContextTokens - reservedOutputTokens - safetyMarginTokens
 */
public final class ContextBudgetPolicy {

    private final int maxContextTokens;
    private final int reservedOutputTokens;
    private final int safetyMarginTokens;

    public ContextBudgetPolicy(int maxContextTokens,
                                int reservedOutputTokens,
                                int safetyMarginTokens) {
        this.maxContextTokens = maxContextTokens;
        this.reservedOutputTokens = reservedOutputTokens;
        this.safetyMarginTokens = safetyMarginTokens;
    }

    /** 模型总的上下文窗口大小（Token）。 */
    public int maxContextTokens() { return maxContextTokens; }

    /** 为模型输出保留的 Token 数。 */
    public int reservedOutputTokens() { return reservedOutputTokens; }

    /** 安全余量 Token 数。 */
    public int safetyMarginTokens() { return safetyMarginTokens; }

    /** 实际可用于输入的最大 Token 数。 */
    public int maxInputTokens() {
        return maxContextTokens - reservedOutputTokens - safetyMarginTokens;
    }
}
