package com.hirain.aiagent.toolgroup;

import com.hirain.aiagent.intentrouter.IntentResult;

/**
 * 工具组选择接口 — 基于 IntentResult 选择候选工具组。
 * <p>
 * 本阶段不用于限制 LLM 可见工具，仅记录和观测。
 */
public interface ToolGroupSelector {
    ToolGroupSelectionResult select(IntentResult intentResult, String userInput);
}
