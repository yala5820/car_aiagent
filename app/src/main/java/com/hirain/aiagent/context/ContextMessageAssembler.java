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
 * 不访问数据库、时间、车辆、Trace 或模型。
 * iteration 参数控制是否包含 SOURCE_CURRENT_USER（iteration=0 时包含，>0 时不包含）。
 * <p>
 * 固定消息顺序为：
 * <ol>
 *   <li>SystemMessage（仅 TRUSTED_SYSTEM + SYSTEM 目标）</li>
 *   <li>Context Data UserMessage（长期记忆、车辆状态、时间、caller extra 合并，使用 ContextDataFormatter）</li>
 *   <li>SessionMemory 消息序列</li>
 *   <li>iteration=0 时：Current UserMessage（来自 SOURCE_CURRENT_USER）</li>
 * </ol>
 */
public final class ContextMessageAssembler {

    private ContextMessageAssembler() {}

    /**
     * 从 ContextFrame 的 contributions 装配最终消息和工具规格。
     *
     * @param frame        上下文快照（含所有 Contribution）
     * @param budgetPolicy 预算策略
     * @param tokenEstimator Token 估算器
     * @param iteration    当前迭代次数：0 表示包含 SOURCE_CURRENT_USER，>0 表示不包含
     * @return 装配结果
     */
    public static ContextAssemblyResult assemble(ContextFrame frame,
                                                   ContextBudgetPolicy budgetPolicy,
                                                   ContextTokenEstimator tokenEstimator,
                                                   int iteration) {
        if (frame == null) {
            return ContextAssemblyResult.failure(ContextErrorCode.CONTEXT_INTERNAL_ERROR,
                    "ContextFrame is null", new ContextAssemblyDebugInfo(List.of(), 0, 0, "null frame"));
        }

        boolean includeCurrentUser = (iteration == 0);

        List<ContextContribution> contributions = frame.contributions();
        List<ContextProviderOutcome> outcomes = new ArrayList<>();
        List<ChatMessage> messages = new ArrayList<>();
        List<ToolSpecification> toolSpecs = new ArrayList<>();
        List<TextContextContribution> contextDataContribs = new ArrayList<>();
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

        // Context Data: 收集模型可见的 CONTEXT_DATA 文本贡献，使用 ContextDataFormatter 格式化
        for (ContextContribution contrib : contributions) {
            if (contrib instanceof TextContextContribution) {
                TextContextContribution textContrib = (TextContextContribution) contrib;
                if (TextContextContribution.TARGET_CONTEXT_DATA.equals(textContrib.targetArea())
                        && textContrib.visibility() == ContextVisibility.MODEL_VISIBLE) {
                    String content = textContrib.content();
                    if (content != null && !content.isEmpty()) {
                        contextDataContribs.add(textContrib);
                    }
                }
            }
        }
        String contextDataText = ContextDataFormatter.format(contextDataContribs);
        if (!contextDataText.isEmpty()) {
            messages.add(UserMessage.from(contextDataText));
        }

        // SessionMemory Contribution
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

        // Current UserMessage（仅 iteration=0 时）
        if (includeCurrentUser) {
            MessageContextContribution currentUser = null;
            for (ContextContribution contrib : contributions) {
                if (contrib instanceof MessageContextContribution) {
                    MessageContextContribution msgContrib = (MessageContextContribution) contrib;
                    if (MessageContextContribution.SOURCE_CURRENT_USER.equals(msgContrib.messageSource())) {
                        if (currentUser != null) {
                            return ContextAssemblyResult.failure(
                                    ContextErrorCode.MESSAGE_SEQUENCE_INVALID,
                                    "Duplicate CURRENT_USER contribution",
                                    new ContextAssemblyDebugInfo(outcomes, 0, 0, "dup current_user"));
                        }
                        currentUser = msgContrib;
                    }
                }
            }
            if (currentUser == null) {
                return ContextAssemblyResult.failure(
                        ContextErrorCode.MESSAGE_SEQUENCE_INVALID,
                        "No CURRENT_USER contribution found for iteration=0",
                        new ContextAssemblyDebugInfo(outcomes, 0, 0, "missing current_user"));
            }
            List<ChatMessage> cuMsgs = currentUser.messages();
            if (cuMsgs == null || cuMsgs.size() != 1 || !(cuMsgs.get(0) instanceof UserMessage)) {
                return ContextAssemblyResult.failure(
                        ContextErrorCode.MESSAGE_SEQUENCE_INVALID,
                        "CURRENT_USER contribution must contain exactly one UserMessage",
                        new ContextAssemblyDebugInfo(outcomes, 0, 0, "invalid current_user"));
            }
            messages.add(cuMsgs.get(0));
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
