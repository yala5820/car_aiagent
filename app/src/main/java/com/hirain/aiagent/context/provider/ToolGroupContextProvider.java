package com.hirain.aiagent.context.provider;

import com.hirain.aiagent.context.ContextBuildInput;
import com.hirain.aiagent.context.ContextErrorCode;
import com.hirain.aiagent.context.ContextLifecycle;
import com.hirain.aiagent.context.ContextPriority;
import com.hirain.aiagent.context.ContextProvider;
import com.hirain.aiagent.context.ContextProviderResult;
import com.hirain.aiagent.context.ContextTrustLevel;
import com.hirain.aiagent.context.ContextVisibility;
import com.hirain.aiagent.context.ToolContextContribution;
import com.hirain.aiagent.runtime.RequestSession;
import com.hirain.aiagent.toolgroup.ToolGroupId;
import com.hirain.aiagent.toolgroup.ToolGroupRegistry;
import com.hirain.aiagent.toolgroup.ToolGroupSelectionResult;
import com.hirain.aiagent.toolgroup.ToolGroupSelectionStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.langchain4j.agent.tool.ToolSpecification;

/**
 * 工具组上下文 Provider — 仅为 SELECTED 状态输出 ToolSpecification 列表。
 * 其他状态均返回 MODE_NONE，避免原因文本变化导致工具边界被意外放宽。
 */
public class ToolGroupContextProvider implements ContextProvider {

    @Override
    public String name() {
        return "ToolGroupContextProvider";
    }

    @Override
    public ContextLifecycle lifecycle() {
        return ContextLifecycle.REQUEST_STATIC;
    }

    @Override
    public boolean required(RequestSession session, ContextBuildInput input) {
        ToolGroupSelectionResult sel = session != null ? session.toolGroupSelectionResult() : null;
        return sel == null || sel.status() == ToolGroupSelectionStatus.SELECTED;
    }

    @Override
    public ContextProviderResult provide(RequestSession session, ContextBuildInput input) {
        ToolGroupSelectionResult selection = session.toolGroupSelectionResult();
        List<ToolGroupId> groupIds = selection != null
                ? selection.selectedGroupIds() : List.of();
        List<String> toolNames = selection != null
                ? selection.selectedToolNames() : List.of();

        // Runtime 正常情况下会提前截断澄清与失败关闭；Provider 仍坚持空工具防线。
        if (selection == null || selection.status() != ToolGroupSelectionStatus.SELECTED) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("selection_status", selection != null ? selection.status().name() : "MISSING");
            metadata.put("selection_reason", selection != null ? selection.selectionReason() : "");
            return ContextProviderResult.success(name(), List.of(
                    new ToolContextContribution("tool_group", ContextVisibility.MODEL_VISIBLE,
                            ContextTrustLevel.TRUSTED_SYSTEM, ContextPriority.CRITICAL,
                            ContextLifecycle.REQUEST_STATIC, false, name(),
                            ToolContextContribution.MODE_NONE, List.of(), metadata)));
        }

        // 非 CHAT_ONLY: 从 ToolRegistry 解析规格
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("selected_group_count", groupIds.size());
        metadata.put("selected_tool_count", toolNames.size());
        metadata.put("selection_status", selection.status().name());
        metadata.put("selection_reason", selection != null ? selection.selectionReason() : "");

        List<ToolSpecification> specs;
        try {
            specs = resolveToolSpecs(toolNames, input);
        } catch (Exception e) {
            return ContextProviderResult.failure(name(), e.getMessage(),
                    ContextErrorCode.TOOL_SPEC_RESOLUTION_FAILED);
        }

        return ContextProviderResult.success(name(), List.of(
                new ToolContextContribution("tool_group", ContextVisibility.MODEL_VISIBLE,
                        ContextTrustLevel.TRUSTED_SYSTEM, ContextPriority.CRITICAL,
                        ContextLifecycle.REQUEST_STATIC, true, name(),
                        specs.isEmpty() ? ToolContextContribution.MODE_NONE
                                : ToolContextContribution.MODE_SELECTED,
                        specs, metadata)));
    }

    private static List<ToolSpecification> resolveToolSpecs(List<String> toolNames,
                                                            ContextBuildInput input) {
        com.hirain.aiagent.ai.langchain4j.tool.ToolRegistry registry = input.toolRegistry();
        if (registry == null) {
            throw new RuntimeException("ToolRegistry is null");
        }
        if (toolNames == null || toolNames.isEmpty()) {
            return List.of();
        }
        return registry.toolSpecificationsByNames(toolNames);
    }
}
