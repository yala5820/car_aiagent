package com.hirain.aiagent.context;

/**
 * Context 数据的生命周期。
 * <p>
 * 区分请求级不变数据和每轮迭代动态变化的数据。
 */
public enum ContextLifecycle {
    /** 每个 TEXT 请求构建一次，在请求期间不变。 */
    REQUEST_STATIC,

    /** 每次 AgentLoop 迭代重新获取。 */
    ITERATION_DYNAMIC
}
