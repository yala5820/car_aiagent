package com.hirain.aiagent.toolgroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 不可变工具组元数据 — 描述一组功能相关的 @Tool 集合。
 * <p>
 * 只保存 groupId、toolName 列表和轻量元信息，不包含完整 Tool schema。
 * 所有 List 字段使用不可修改视图，防止外部篡改。
 */
public final class ToolGroup {
    private final ToolGroupId groupId;
    private final String groupName;
    private final String description;
    private final List<String> toolNames;
    private final List<String> requiredContextKeys;
    private final String riskLevel;
    private final boolean enabled;

    private static final java.util.Set<String> VALID_RISK_LEVELS =
            java.util.Set.of("LOW", "MEDIUM", "HIGH");

    public ToolGroup(ToolGroupId groupId, String groupName, String description,
                     List<String> toolNames, List<String> requiredContextKeys,
                     String riskLevel, boolean enabled) {
        if (riskLevel == null || !VALID_RISK_LEVELS.contains(riskLevel)) {
            throw new IllegalArgumentException(
                    "riskLevel must be LOW, MEDIUM, or HIGH, got: " + riskLevel);
        }
        this.groupId = groupId;
        this.groupName = groupName;
        this.description = description;
        this.toolNames = Collections.unmodifiableList(new ArrayList<>(toolNames));
        this.requiredContextKeys = Collections.unmodifiableList(new ArrayList<>(requiredContextKeys));
        this.riskLevel = riskLevel;
        this.enabled = enabled;
    }

    public ToolGroupId groupId() { return groupId; }
    public String groupName() { return groupName; }
    public String description() { return description; }
    public List<String> toolNames() { return toolNames; }
    public List<String> requiredContextKeys() { return requiredContextKeys; }
    public String riskLevel() { return riskLevel; }
    public boolean enabled() { return enabled; }

    /** 上下文标记组：无工具但有 requiredContextKeys（如 BASIC_STATUS_GROUP）。 */
    public boolean isContextMarker() {
        return toolNames.isEmpty() && !requiredContextKeys.isEmpty();
    }

    /** 聚合组：COMMON_VEHICLE_GROUP 或 ALL_SAFE_DEMO_GROUP。 */
    public boolean isAggregation() {
        return groupId == ToolGroupId.COMMON_VEHICLE_GROUP
                || groupId == ToolGroupId.ALL_SAFE_DEMO_GROUP;
    }
}
