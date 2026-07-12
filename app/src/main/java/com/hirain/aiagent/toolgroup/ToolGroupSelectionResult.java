package com.hirain.aiagent.toolgroup;

import com.hirain.aiagent.intentrouter.IntentConfidence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单次工具组选择结果 — 包含候选 groupId、候选 toolName 及选择原因。
 * <p>
 * selectedGroupIds 和 selectedToolNames 使用不可修改 List 防护。
 * selectedToolNames 是“候选工具名”，不是 LangChain4j 已绑定工具。
 * 本阶段不用于限制 LLM 可见工具，仅写入 RequestSession 和 Trace 观测。
 */
public final class ToolGroupSelectionResult {
    private final List<ToolGroupId> selectedGroupIds;
    private final List<String> selectedToolNames;
    private final String selectionReason;
    private final IntentConfidence confidence;
    private final boolean fallbackUsed;
    private final List<String> requiredContextKeys;
    private final String highestRiskLevel;
    private final boolean allToolsFallback;
    private final boolean containsAggregationGroup;

    private ToolGroupSelectionResult(List<ToolGroupId> selectedGroupIds,
                                     List<String> selectedToolNames,
                                     String selectionReason,
                                     IntentConfidence confidence,
                                     boolean fallbackUsed,
                                     List<String> requiredContextKeys,
                                     String highestRiskLevel,
                                     boolean allToolsFallback,
                                     boolean containsAggregationGroup) {
        this.selectedGroupIds = Collections.unmodifiableList(new ArrayList<>(selectedGroupIds));
        this.selectedToolNames = Collections.unmodifiableList(new ArrayList<>(selectedToolNames));
        this.selectionReason = selectionReason;
        this.confidence = confidence;
        this.fallbackUsed = fallbackUsed;
        this.requiredContextKeys = Collections.unmodifiableList(new ArrayList<>(
                requiredContextKeys != null ? requiredContextKeys : List.of()));
        this.highestRiskLevel = highestRiskLevel != null ? highestRiskLevel : "LOW";
        this.allToolsFallback = allToolsFallback;
        this.containsAggregationGroup = containsAggregationGroup;
    }

    // ── 兼容工厂方法（5 参数，派生字段填默认值，不完整） ──

    /**
     * 轻量兼容工厂 — 派生字段（context keys / risk / aggregation）均填默认值。
     * 不保证与 groupIds 对应的 Registry 元信息一致。
     * 生产路径应使用 {@link #enriched(ToolGroupRegistry, List, String, IntentConfidence, boolean)}。
     */
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
                fallbackUsed,
                List.of(),    // requiredContextKeys
                "LOW",        // highestRiskLevel
                false,        // allToolsFallback
                false);       // containsAggregationGroup
    }

    public static ToolGroupSelectionResult fallback(String reason) {
        return new ToolGroupSelectionResult(
                List.of(ToolGroupId.CHAT_ONLY_GROUP), List.of(),
                reason != null ? reason : "fallback_unknown",
                IntentConfidence.NONE, true,
                List.of(), "LOW", false, false);
    }

    // ── 新工厂方法（9 参数完整元信息） ──

    /**
     * 通用完整工厂 — 调用方自行传入所有字段。
     * 不保证 groupIds 与 context keys / risk / aggregation 的一致性。
     * 需要 Registry 保证一致性的场景请使用 {@link #enriched(ToolGroupRegistry, List, String, IntentConfidence, boolean)}。
     */
    public static ToolGroupSelectionResult full(
            List<ToolGroupId> selectedGroupIds,
            List<String> selectedToolNames,
            String selectionReason,
            IntentConfidence confidence,
            boolean fallbackUsed,
            List<String> requiredContextKeys,
            String highestRiskLevel,
            boolean allToolsFallback,
            boolean containsAggregationGroup) {
        return new ToolGroupSelectionResult(
                selectedGroupIds != null ? selectedGroupIds : List.of(),
                selectedToolNames != null ? selectedToolNames : List.of(),
                selectionReason != null ? selectionReason : "unknown_reason",
                confidence != null ? confidence : IntentConfidence.NONE,
                fallbackUsed,
                requiredContextKeys,
                highestRiskLevel,
                allToolsFallback,
                containsAggregationGroup);
    }

    /**
     * Registry 保证的完整结果 — 由 Registry 根据 groupIds 统一推导派生字段。
     * <p>
     * toolNames、requiredContextKeys、highestRiskLevel、containsAggregationGroup、
     * allToolsFallback 全部由 Registry 计算，保证与 groupIds 一致。
     * 生产路径（DefaultToolGroupSelector、AgentRuntime）只使用本工厂。
     */
    public static ToolGroupSelectionResult enriched(
            ToolGroupRegistry registry,
            List<ToolGroupId> groupIds,
            String reason,
            IntentConfidence confidence,
            boolean fallbackUsed) {
        if (groupIds == null) {
            throw new IllegalArgumentException("groupIds must not be null");
        }
        List<String> toolNames = registry.toolNamesFor(groupIds);
        boolean isAllToolsFallback = groupIds.contains(ToolGroupId.ALL_SAFE_DEMO_GROUP);
        return new ToolGroupSelectionResult(
                groupIds, toolNames,
                reason != null ? reason : "unknown_reason",
                confidence != null ? confidence : IntentConfidence.NONE,
                fallbackUsed,
                registry.requiredContextKeysFor(groupIds),
                registry.highestRiskLevelFor(groupIds),
                isAllToolsFallback,
                registry.containsAggregationGroup(groupIds));
    }

    /**
     * Registry 驱动的全量兜底结果 — 全部派生字段由 Registry 计算，
     * 保证 selectedToolNames 一定是当前 enabled 业务组的完整集合。
     * <p>
     * Registry 为 null 或 allToolNames() 为空时退回轻量 fallback()，不设 allToolsFallback=true。
     */
    public static ToolGroupSelectionResult allToolsFallback(
            ToolGroupRegistry registry, String reason) {
        if (registry == null) {
            return fallback("all_tools_fallback_null_registry");
        }
        List<String> allTools = registry.allToolNames();
        if (allTools.isEmpty()) {
            return fallback("all_tools_fallback_empty_registry");
        }
        List<ToolGroupId> groupIds = List.of(ToolGroupId.ALL_SAFE_DEMO_GROUP);
        return new ToolGroupSelectionResult(
                groupIds, allTools,
                reason != null ? reason : "fallback_all_tools",
                IntentConfidence.NONE,
                true,       // fallbackUsed
                registry.requiredContextKeysFor(groupIds),
                registry.highestRiskLevelFor(groupIds),
                true,       // allToolsFallback
                registry.containsAggregationGroup(groupIds));
    }

    // ── 读取器 ──

    public List<ToolGroupId> selectedGroupIds() { return selectedGroupIds; }
    public List<String> selectedToolNames() { return selectedToolNames; }
    public String selectionReason() { return selectionReason; }
    public IntentConfidence confidence() { return confidence; }
    public boolean fallbackUsed() { return fallbackUsed; }
    public List<String> requiredContextKeys() { return requiredContextKeys; }
    public String highestRiskLevel() { return highestRiskLevel; }
    public boolean allToolsFallback() { return allToolsFallback; }
    public boolean containsAggregationGroup() { return containsAggregationGroup; }
}
