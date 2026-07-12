package com.hirain.aiagent.context;

/**
 * Context 数据的优先级 — 用于预算裁剪决策。
 * <p>
 * 优先级由 ContextPolicy 结合当前 Intent、ToolGroup 和 requiredContextKeys 动态确定。
 */
public enum ContextPriority {
    /** 不得删除或截断。 */
    CRITICAL,

    /** 优先保留。 */
    HIGH,

    /** 可按完整条目缩减。 */
    NORMAL,

    /** 预算超限时优先移除。 */
    OPTIONAL,

    /** 不进入预算计算。 */
    TRACE_ONLY
}
