package com.hirain.aiagent.context;

/**
 * Context 数据的模型可见性。
 * <p>
 * 定义数据是否可以进入模型消息、仅用于策略决策、或仅供 Trace 观测。
 */
public enum ContextVisibility {
    /** 允许参与消息或工具规格装配。 */
    MODEL_VISIBLE,

    /** 仅供 ContextPolicy 决策，不进入模型消息。 */
    POLICY_ONLY,

    /** 仅供诊断和 Trace，不进入模型消息也不参与策略。 */
    TRACE_ONLY
}
