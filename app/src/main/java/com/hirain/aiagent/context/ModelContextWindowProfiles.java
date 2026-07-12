package com.hirain.aiagent.context;

/**
 * 模型上下文窗口配置 — 集中提供各模型的受控窗口配置。
 * <p>
 * 设计原因：数值从受控配置注入，不读取网络或本地密钥文件。
 * 后续若确认部署模型窗口变化，只替换此处 profile，不改变算法。
 */
public final class ModelContextWindowProfiles {

    private ModelContextWindowProfiles() {}

    /**
     * qwen-turbo Demo 配置。
     * <ul>
     *   <li>maxContextTokens = 32768</li>
     *   <li>reservedOutputTokens = 2048</li>
     *   <li>safetyMarginTokens = 1024</li>
     *   <li>maxInputTokens = 29696</li>
     * </ul>
     */
    public static ContextBudgetPolicy qwenTurboDemo() {
        return new ContextBudgetPolicy(32768, 2048, 1024);
    }
}
