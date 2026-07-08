package com.hirain.aiagent.context.provider;

import com.hirain.aiagent.context.ContextBuildInput;
import com.hirain.aiagent.context.ContextProvider;
import com.hirain.aiagent.context.ContextProviderResult;
import com.hirain.aiagent.context.ContextSection;
import com.hirain.aiagent.context.ContextSectionType;
import com.hirain.aiagent.runtime.RequestSession;
import com.hirain.aiagent.toolgroup.ToolGroup;
import com.hirain.aiagent.toolgroup.ToolGroupId;
import com.hirain.aiagent.toolgroup.ToolGroupRegistry;
import com.hirain.aiagent.toolgroup.ToolGroupSelectionResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具组上下文 Provider — 输出选中工具组和工具名列表（轻量方案，不渲染完整 ToolSpecification）。
 * <p>
 * 一期只渲染 selected group 描述 + selected tool names，
 * 不读取完整 {@code ToolSpecification.description} 或参数 schema。
 */
public class ToolGroupContextProvider implements ContextProvider {

    @Override
    public String name() {
        return "ToolGroupContextProvider";
    }

    @Override
    public ContextSectionType type() {
        return ContextSectionType.TOOL_GROUP;
    }

    @Override
    public ContextProviderResult provide(RequestSession session, ContextBuildInput input) {
        ToolGroupSelectionResult selection = session.toolGroupSelectionResult();
        List<ToolGroupId> groupIds = selection != null
                ? selection.selectedGroupIds() : List.of();
        List<String> toolNames = selection != null
                ? selection.selectedToolNames() : List.of();

        // ToolGroupRegistry 为空时降级
        ToolGroupRegistry registry = input.toolGroupRegistry();
        if (registry == null) {
            return fallbackResult(toolNames);
        }

        String content = buildContent(groupIds, toolNames, registry);
        int charCount = content.length();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("selected_group_count", groupIds.size());
        metadata.put("selected_tool_count", toolNames.size());
        metadata.put("selection_reason", selection != null ? selection.selectionReason() : "");

        ContextSection section = new ContextSection(
                type(), name(), true, content, charCount, false, metadata);
        return ContextProviderResult.success(name(), section);
    }

    private ContextProviderResult fallbackResult(List<String> toolNames) {
        StringBuilder sb = new StringBuilder();
        sb.append("【工具组上下文】\n");
        sb.append("- selectedToolCount: ").append(toolNames.size()).append("\n");
        sb.append("- selectedToolNames:\n");
        for (String name : toolNames) {
            sb.append("  - ").append(name).append("\n");
        }
        sb.append("(group descriptions not available)");
        String content = sb.toString();
        ContextSection section = new ContextSection(
                type(), name(), true, content, content.length(), false, new LinkedHashMap<>());
        return ContextProviderResult.fallback(name(), section,
                "ToolGroupRegistry is null, only tool names available");
    }

    private static String buildContent(List<ToolGroupId> groupIds, List<String> toolNames,
                                        ToolGroupRegistry registry) {
        StringBuilder sb = new StringBuilder();
        sb.append("【工具组上下文】\n");
        sb.append("- selectedGroupIds: ");
        for (int i = 0; i < groupIds.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(groupIds.get(i).name());
        }
        sb.append("\n");
        sb.append("- selectedToolCount: ").append(toolNames.size()).append("\n");
        sb.append("- selectedToolNames:\n");
        for (String name : toolNames) {
            sb.append("  - ").append(name).append("\n");
        }
        sb.append("- groupDescriptions:\n");
        for (ToolGroupId groupId : groupIds) {
            ToolGroup group = registry.group(groupId);
            if (group != null) {
                sb.append("  - ").append(groupId.name()).append(": ")
                        .append(group.groupName()).append(" / ")
                        .append(group.description()).append(" / risk=")
                        .append(group.riskLevel()).append("\n");
            } else {
                sb.append("  - ").append(groupId.name()).append(": (unknown)\n");
            }
        }
        return sb.toString().trim();
    }
}
