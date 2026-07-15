package com.hirain.aiagent.toolgroup;

/**
 * 工具选择的稳定状态。
 * <p>
 * 设计原因：工具是否可见属于运行时安全边界，不能再依赖 selectionReason 文本推断。
 * Runtime 与 Context 只依据该状态决定继续对话、直接澄清或失败关闭。
 */
public enum ToolGroupSelectionStatus {
    /** 已选出明确且受限的业务工具集合。 */
    SELECTED,
    /** 普通对话，不向模型暴露任何工具。 */
    CHAT_ONLY,
    /** 语义可能涉及车控但目标不明确，需要先让用户澄清。 */
    CLARIFICATION_REQUIRED,
    /** 选择器异常、返回空值或结果不一致，禁止继续进入模型。 */
    FAILED_CLOSED
}
