package com.hirain.aiagent.context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

/**
 * 纯消息装配组件 — 将 Contribution 转换为 LangChain4j ChatMessage 和 ToolSpecification 列表。
 * <p>
 * 不访问数据库、时间、车辆、Trace 或模型。固定消息顺序为：
 * <ol>
 *   <li>SystemMessage（仅 TRUSTED_SYSTEM + SYSTEM 目标）</li>
 *   <li>Context Data UserMessage（长期记忆、车辆状态、时间、caller extra 合并为一条）</li>
 *   <li>Session ChatMemory（含当前 UserMessage、历史对话、工具交换）</li>
 * </ol>
 */
public final class ContextMessageAssembler {

    private ContextMessageAssembler() {}

    /**
     * 从 ContextFrame 的 contributions 装配最终消息和工具规格。
     * <p>
     * 固定消息顺序：唯一 SystemMessage -> Context Data UserMessage（存在时）-> SessionMemory Contribution 消息序列。
     *
     * @param frame        上下文快照（含所有 Contribution，其中必须包含 SOURCE_SESSION_MEMORY MessageContribution）
     * @param budgetPolicy 预算策略
     * @return 装配结果
     */
    public static ContextAssemblyResult assemble(ContextFrame frame,
                                                   ContextBudgetPolicy budgetPolicy,
                                                   ContextTokenEstimator tokenEstimator) {
        if (frame == null) {
            return ContextAssemblyResult.failure(ContextErrorCode.CONTEXT_INTERNAL_ERROR,
                    "ContextFrame is null", new ContextAssemblyDebugInfo(List.of(), 0, 0, "null frame"));
        }

        List<ContextContribution> contributions = frame.contributions();
        List<ContextProviderOutcome> outcomes = new ArrayList<>();
        List<ChatMessage> messages = new ArrayList<>();
        List<ToolSpecification> toolSpecs = new ArrayList<>();
        List<String> contextDataParts = new ArrayList<>();
        Map<String, ToolSpecification> toolSpecMap = new LinkedHashMap<>();

        // 提取 TOOL_SPECIFICATIONS Contribution
        for (ContextContribution contrib : contributions) {
            if (contrib instanceof ToolContextContribution) {
                ToolContextContribution toolContrib = (ToolContextContribution) contrib;
                for (ToolSpecification spec : toolContrib.toolSpecifications()) {
                    String name = spec.name();
                    if (toolSpecMap.containsKey(name)) {
                        ToolSpecification existing = toolSpecMap.get(name);
                        if (!existing.equals(spec)) {
                            return ContextAssemblyResult.failure(
                                    ContextErrorCode.MESSAGE_SEQUENCE_INVALID,
                                    "Duplicate tool name with different schema: " + name,
                                    new ContextAssemblyDebugInfo(outcomes, 0, 0,
                                            "tool schema conflict: " + name));
                        }
                    } else {
                        toolSpecMap.put(name, spec);
                    }
                }
            }
        }
        toolSpecs.addAll(toolSpecMap.values());

        // SystemMessage: 仅 TRUSTED_SYSTEM + MODEL_VISIBLE + SYSTEM target，且必须唯一
        boolean systemAdded = false;
        for (ContextContribution contrib : contributions) {
            if (contrib instanceof TextContextContribution) {
                TextContextContribution textContrib = (TextContextContribution) contrib;
                if (TextContextContribution.TARGET_SYSTEM.equals(textContrib.targetArea())
                        && textContrib.visibility() == ContextVisibility.MODEL_VISIBLE
                        && textContrib.trustLevel() == ContextTrustLevel.TRUSTED_SYSTEM) {
                    if (!systemAdded) {
                        String content = textContrib.content();
                        if (content == null || content.trim().isEmpty()) {
                            return ContextAssemblyResult.failure(
                                    ContextErrorCode.MESSAGE_SEQUENCE_INVALID,
                                    "System contribution is empty: provider="
                                            + textContrib.sourceKey(),
                                    new ContextAssemblyDebugInfo(outcomes, 0, 0, "empty system"));
                        }
                        messages.add(SystemMessage.from(content));
                        systemAdded = true;
                    } else {
                        // 重复 System Contribution 不允许
                        return ContextAssemblyResult.failure(
                                ContextErrorCode.MESSAGE_SEQUENCE_INVALID,
                                "Duplicate System contribution: provider="
                                        + textContrib.sourceKey(),
                                new ContextAssemblyDebugInfo(outcomes, 0, 0,
                                        "duplicate system: " + textContrib.sourceKey()));
                    }
                }
            }
        }
        if (!systemAdded) {
            return ContextAssemblyResult.failure(
                    ContextErrorCode.MESSAGE_SEQUENCE_INVALID,
                    "No valid SystemMessage contribution found",
                    new ContextAssemblyDebugInfo(outcomes, 0, 0, "missing system"));
        }

        // Context Data: 模型可见的 CONTEXT_DATA 文本合并为一条 UserMessage
        for (ContextContribution contrib : contributions) {
            if (contrib instanceof TextContextContribution) {
                TextContextContribution textContrib = (TextContextContribution) contrib;
                if (TextContextContribution.TARGET_CONTEXT_DATA.equals(textContrib.targetArea())
                        && textContrib.visibility() == ContextVisibility.MODEL_VISIBLE) {
                    String content = textContrib.content();
                    if (content != null && !content.isEmpty()) {
                        contextDataParts.add(content);
                    }
                }
            }
        }
        if (!contextDataParts.isEmpty()) {
            messages.add(UserMessage.from(String.join("\n\n", contextDataParts)));
        }

        // SessionMemory Contribution: 从 Frame 提取 SOURCE_SESSION_MEMORY 消息序列
        boolean sessionMemoryAdded = false;
        for (ContextContribution contrib : contributions) {
            if (contrib instanceof MessageContextContribution) {
                MessageContextContribution msgContrib = (MessageContextContribution) contrib;
                if (MessageContextContribution.SOURCE_SESSION_MEMORY.equals(msgContrib.messageSource())) {
                    if (sessionMemoryAdded) {
                        return ContextAssemblyResult.failure(
                                ContextErrorCode.MESSAGE_SEQUENCE_INVALID,
                                "Duplicate SESSION_MEMORY contribution: provider="
                                        + msgContrib.sourceKey(),
                                new ContextAssemblyDebugInfo(outcomes, 0, 0,
                                        "dup session_memory"));
                    }
                    List<ChatMessage> sessionMsgs = msgContrib.messages();
                    if (sessionMsgs != null) {
                        messages.addAll(sessionMsgs);
                    }
                    sessionMemoryAdded = true;
                }
            }
        }
        if (!sessionMemoryAdded) {
            return ContextAssemblyResult.failure(
                    ContextErrorCode.MESSAGE_SEQUENCE_INVALID,
                    "No SESSION_MEMORY contribution found",
                    new ContextAssemblyDebugInfo(outcomes, 0, 0, "missing session_memory"));
        }

        // 校验
        try {
            ContextMessageSequenceValidator.validate(messages);
        } catch (ContextMessageSequenceValidator.InvalidMessageSequenceException e) {
            return ContextAssemblyResult.failure(
                    ContextErrorCode.MESSAGE_SEQUENCE_INVALID,
                    e.getMessage(),
                    new ContextAssemblyDebugInfo(outcomes, messages.size(), toolSpecs.size(),
                            e.getMessage()));
        }

        ContextTokenEstimator estimator = tokenEstimator != null
                ? tokenEstimator : new HeuristicContextTokenEstimator();
        int estimatedTokens = estimator.estimateTotal(messages, toolSpecs);
        int maxInput = budgetPolicy != null ? budgetPolicy.maxInputTokens() : Integer.MAX_VALUE;
        ContextBudgetReport budgetReport = new ContextBudgetReport(
                estimatedTokens, maxInput, estimatedTokens <= maxInput);

        return ContextAssemblyResult.success(messages, toolSpecs, budgetReport,
                new ContextAssemblyDebugInfo(outcomes, messages.size(), toolSpecs.size(), null),
                outcomes);
    }
}

