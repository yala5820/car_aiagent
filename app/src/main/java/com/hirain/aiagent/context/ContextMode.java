package com.hirain.aiagent.context;

/**
 * Context 模块运行模式。
 */
public enum ContextMode {
    /** 只构建 ContextFrame 和 trace，不把任何 section 注入 AgentLoop。 */
    OBSERVE_ONLY,

    /** 一期默认模式：只把 runtime、intent、toolgroup、debug 等轻量上下文注入 extraContext。 */
    HYBRID_EXTRA_CONTEXT,

    /**
     * 完整 Context 接管模式。一期不真正启用，当前行为降级为 HYBRID_EXTRA_CONTEXT，
     * 并在 debugInfo 中记录 full_context_deferred=true。
     */
    FULL_CONTEXT
}
