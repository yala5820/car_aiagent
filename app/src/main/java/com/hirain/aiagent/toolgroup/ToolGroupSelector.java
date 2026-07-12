package com.hirain.aiagent.toolgroup;

import com.hirain.aiagent.intentrouter.IntentResult;

/**
 * 工具组选择接口 — 基于 IntentResult 选择候选工具组。
 * <p>
 * 本阶段不用于限制 LLM 可见工具，仅记录和观测。
 * 未来可实现为规则选择器、小 LLM 选择器或子 agent 选择器。
 */
public interface ToolGroupSelector {

    /**
     * 主选择方法 — 基于意图和用户输入。
     * 设计原因：保持二参为 SAM，使现有 lambda {@code (intentResult, userInput) -> ...} 继续工作。
     */
    ToolGroupSelectionResult select(IntentResult intentResult, String userInput);

    /**
     * 新主接口 — 接受完整选择输入对象。
     * 未来选择器实现此方法即可获取更多上下文。
     * 默认委托二参抽象方法，兼容尚未实现新接口的选择器。
     */
    default ToolGroupSelectionResult select(ToolGroupSelectionInput input) {
        return select(input.intentResult(), input.userInput());
    }
}
