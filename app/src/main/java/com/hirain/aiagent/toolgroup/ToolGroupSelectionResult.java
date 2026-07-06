package com.hirain.aiagent.toolgroup;

import com.hirain.aiagent.intentrouter.IntentConfidence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单次工具组选择结果 — 包含选中的 groupId、toolName 及选择原因。
 * <p>
 * selectedGroupIds 和 selectedToolNames 使用不可修改 List 防护。
 * 本阶段不用于限制 LLM 可见工具，仅写入 RequestSession 和 Trace 观测。
 */
public final class ToolGroupSelectionResult {
    private final List<ToolGroupId> selectedGroupIds;
    private final List<String> selectedToolNames;
    private final String selectionReason;
    private final IntentConfidence confidence;
    private final boolean fallbackUsed;

    private ToolGroupSelectionResult(List<ToolGroupId> selectedGroupIds,
                                     List<String> selectedToolNames,
                                     String selectionReason,
                                     IntentConfidence confidence,
                                     boolean fallbackUsed) {
        this.selectedGroupIds = Collections.unmodifiableList(new ArrayList<>(selectedGroupIds));
        this.selectedToolNames = Collections.unmodifiableList(new ArrayList<>(selectedToolNames));
        this.selectionReason = selectionReason;
        this.confidence = confidence;
        this.fallbackUsed = fallbackUsed;
    }

    public static ToolGroupSelectionResult of(List<ToolGroupId> selectedGroupIds,
                                              List<String> selectedToolNames,
                                              String selectionReason,
                                              IntentConfidence confidence,
                                              boolean fallbackUsed) {
        return new ToolGroupSelectionResult(
                selectedGroupIds != null ? selectedGroupIds : List.of(),
                selectedToolNames != null ? selectedToolNames : List.of(),
                selectionReason != null ? selectionReason : "unknown_reason",
                confidence != null ? confidence : IntentConfidence.NONE,
                fallbackUsed);
    }

    public static ToolGroupSelectionResult fallback(String reason) {
        return new ToolGroupSelectionResult(
                List.of(ToolGroupId.CHAT_ONLY_GROUP), List.of(),
                reason != null ? reason : "fallback_unknown",
                IntentConfidence.NONE, true);
    }

    public List<ToolGroupId> selectedGroupIds() { return selectedGroupIds; }
    public List<String> selectedToolNames() { return selectedToolNames; }
    public String selectionReason() { return selectionReason; }
    public IntentConfidence confidence() { return confidence; }
    public boolean fallbackUsed() { return fallbackUsed; }
}
