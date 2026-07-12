package com.hirain.aiagent.context;

import java.util.ArrayList;
import java.util.List;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

/**
 * 粗粒度 Context 字符和 Token 预算管理。
 * <p>
 * 保留旧字符级 API（{@link #trim(String, int)} / {@link #estimateTokens(String)}），
 * 同时新增结构化预算流程：贡献 → 消息 → 工具 → 压缩。
 */
public final class ContextBudgetManager {

    public static final int DEFAULT_SECTION_CHAR_LIMIT = 800;
    public static final int DEFAULT_MEMORY_CHAR_LIMIT = 500;
    public static final int DEFAULT_TOOL_CONTEXT_CHAR_LIMIT = 1200;
    public static final int DEFAULT_TOTAL_CHAR_LIMIT = 3000;

    private final int sectionCharLimit;
    private final int memoryCharLimit;
    private final int toolContextCharLimit;
    private final int totalCharLimit;

    public ContextBudgetManager(int sectionCharLimit,
                                int memoryCharLimit,
                                int toolContextCharLimit,
                                int totalCharLimit) {
        this.sectionCharLimit = sectionCharLimit;
        this.memoryCharLimit = memoryCharLimit;
        this.toolContextCharLimit = toolContextCharLimit;
        this.totalCharLimit = totalCharLimit;
    }

    public static ContextBudgetManager defaultBudget() {
        return new ContextBudgetManager(
                DEFAULT_SECTION_CHAR_LIMIT,
                DEFAULT_MEMORY_CHAR_LIMIT,
                DEFAULT_TOOL_CONTEXT_CHAR_LIMIT,
                DEFAULT_TOTAL_CHAR_LIMIT);
    }

    public int sectionCharLimit() { return sectionCharLimit; }
    public int memoryCharLimit() { return memoryCharLimit; }
    public int toolContextCharLimit() { return toolContextCharLimit; }
    public int totalCharLimit() { return totalCharLimit; }

    // ── 旧字符级 API（Phase 6 删除） ──

    public TrimmedText trim(String value, int limit) {
        String safe = value != null ? value : "";
        if (safe.length() <= limit) return new TrimmedText(safe, false);
        return new TrimmedText(safe.substring(0, limit), true);
    }

    public int estimateTokens(String text) {
        String safe = text != null ? text : "";
        return (safe.length() + 1) / 2;
    }

    // ── 新结构化预算 API（Phase 4+） ──

    /**
     * 生成完整预算报告：输入候选消息列表 → 按优先级裁剪 → 返回报告。
     */
    public ContextBudgetReport generateBudgetReport(
            List<ChatMessage> messages,
            List<ToolSpecification> toolSpecs,
            ContextBudgetPolicy policy,
            ContextTokenEstimator estimator) {
        if (policy == null) {
            return new ContextBudgetReport(
                    estimateMessages(messages, toolSpecs, estimator),
                    Integer.MAX_VALUE, true);
        }

        int maxInput = policy.maxInputTokens();
        int estimated = estimateMessages(messages, toolSpecs, estimator);

        return new ContextBudgetReport(estimated, maxInput, estimated <= maxInput);
    }

    /**
     * 执行结构化预算决策：估算 → 按优先级裁剪 → 返回决策结果。
     * <p>
     * 实现 4 级固定裁剪算法：System/当前 User 不裁剪，OPTIONAL 数据可裁，最旧 history turn 可裁，Tool 原子集合不裁。
     */
    public ContextBudgetDecision makeDecision(List<ChatMessage> messages,
                                               List<ToolSpecification> tools,
                                               ContextBudgetPolicy policy,
                                               ContextTokenEstimator estimator) {
        if (messages == null) messages = List.of();
        if (tools == null) tools = List.of();
        int maxInput = policy != null ? policy.maxInputTokens() : Integer.MAX_VALUE;
        ContextTokenEstimator est = estimator != null ? estimator : new HeuristicContextTokenEstimator();

        int beforeCount = est.estimateTotal(messages, tools);
        if (beforeCount <= maxInput) {
            // 预算满足 → 不裁剪
            return new ContextBudgetDecision(messages, tools, beforeCount, beforeCount,
                    maxInput, true, List.of(), false, 0, null, null);
        }

        // 预算超限 → 裁剪
        List<ChatMessage> trimmed = new ArrayList<>(messages);
        List<ContextBudgetDecision.TrimAction> trimActions = new ArrayList<>();

        // 识别受保护的 segments：SystemMessage + 第一条 UserMessage + 完整 ToolExchange
        // 以及受保护的 ToolSpecification 集合
        java.util.HashSet<Integer> protectedIndices = new java.util.HashSet<>();
        boolean foundUser = false;
        boolean inToolCall = false;
        java.util.HashSet<Integer> toolCallIndices = new java.util.HashSet<>();
        for (int i = 0; i < trimmed.size(); i++) {
            ChatMessage msg = trimmed.get(i);
            if (msg instanceof dev.langchain4j.data.message.SystemMessage) {
                protectedIndices.add(i); // System 不可裁剪
            } else if (!foundUser && msg instanceof dev.langchain4j.data.message.UserMessage) {
                protectedIndices.add(i); // 第一条 UserMessage 不可裁剪
                foundUser = true;
            }
            if (msg instanceof dev.langchain4j.data.message.AiMessage
                    && ((dev.langchain4j.data.message.AiMessage) msg).hasToolExecutionRequests()) {
                inToolCall = true;
                toolCallIndices.clear();
            }
            if (inToolCall) {
                toolCallIndices.add(i);
                if (msg instanceof dev.langchain4j.data.message.ToolExecutionResultMessage) {
                    inToolCall = false;
                    protectedIndices.addAll(toolCallIndices); // 完整 ToolExchange 不可裁剪
                    toolCallIndices.clear();
                }
            }
            // 非 ToolCall 的 AiMessage（文本回复）不保护
        }

        // 从最旧 turn 开始裁剪（跳过 protected indices）
        int currentEstimate = est.estimateTotal(trimmed, tools);
        int turnStart = -1;
        for (int i = 1; i < trimmed.size() && currentEstimate > maxInput; i++) {
            ChatMessage msg = trimmed.get(i);
            boolean isProtected = protectedIndices.contains(i);
            // 识别 turn 边界：UserMessage 开始一个新 turn
            if (msg instanceof dev.langchain4j.data.message.UserMessage) {
                if (turnStart >= 0 && !isProtected) {
                    // 删除整个 turn（从 turnStart 到 i-1）
                    int before = est.estimateTotal(trimmed, tools);
                    List<ChatMessage> removed = new ArrayList<>(trimmed.subList(turnStart, i));
                    trimmed.subList(turnStart, i).clear();
                    int after = est.estimateTotal(trimmed, tools);
                    int removedTokens = before - after;
                    trimActions.add(new ContextBudgetDecision.TrimAction(
                            "history_turn", "remove_turn", removedTokens, 0));
                    currentEstimate = after;
                    turnStart = i;
                } else {
                    turnStart = i;
                }
            }
        }

        // 检查 Tool 是否超限
        int finalEstimate = est.estimateTotal(trimmed, tools);
        boolean withinBudget = finalEstimate <= maxInput;

        if (!withinBudget && tools != null && !tools.isEmpty()) {
            // Tool 集合超限 → 明确失败，不部分删除
            return new ContextBudgetDecision(trimmed, tools, beforeCount, finalEstimate,
                    maxInput, false, trimActions, false, 0,
                    ContextErrorCode.CONTEXT_BUDGET_EXCEEDED, "Tool set exceeds budget");
        }

        return new ContextBudgetDecision(trimmed, tools, beforeCount, finalEstimate,
                maxInput, withinBudget, trimActions, false, 0, null, null);
    }

    private int estimateMessages(List<ChatMessage> messages,
                                  List<ToolSpecification> toolSpecs,
                                  ContextTokenEstimator estimator) {
        int msgTokens = estimator != null
                ? estimator.estimateMessages(messages)
                : estimateTokens(String.valueOf(messages));
        int toolTokens = estimator != null
                ? estimator.estimateToolSpecs(toolSpecs)
                : (toolSpecs != null ? toolSpecs.size() * 20 : 0);
        return msgTokens + toolTokens;
    }

    // ── 消息原子性工具 ──

    /**
     * 识别消息序列中的对话轮次（ConversationTurn = UserMessage + AiMessage + optional ToolExchange）。
     */
    public static List<ConversationTurn> identifyTurns(List<ChatMessage> messages) {
        List<ConversationTurn> turns = new ArrayList<>();
        List<ChatMessage> currentTurn = new ArrayList<>();
        boolean inToolCall = false;
        int toolResultCount = 0;

        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage) continue; // SystemMessage 不属于任何 turn

            if (msg instanceof AiMessage && ((AiMessage) msg).hasToolExecutionRequests()) {
                currentTurn.add(msg);
                inToolCall = true;
                continue;
            }
            if (msg instanceof ToolExecutionResultMessage) {
                currentTurn.add(msg);
                toolResultCount++;
                continue;
            }
            if (inToolCall && toolResultCount > 0) {
                // 上一组工具调用已结束，封存当前 turn
                if (!currentTurn.isEmpty()) {
                    turns.add(new ConversationTurn(new ArrayList<>(currentTurn)));
                    currentTurn.clear();
                }
                inToolCall = false;
                toolResultCount = 0;
            }
            currentTurn.add(msg);
        }
        if (!currentTurn.isEmpty()) {
            turns.add(new ConversationTurn(new ArrayList<>(currentTurn)));
        }
        return turns;
    }

    public static final class ConversationTurn {
        private final List<ChatMessage> messages;
        public ConversationTurn(List<ChatMessage> messages) {
            this.messages = messages;
        }
        public List<ChatMessage> messages() { return messages; }
        public int tokenEstimate(ContextTokenEstimator estimator) {
            return estimator != null ? estimator.estimateMessages(messages) : messages.size() * 20;
        }
    }

    // ── 内部类型 ──

    public static final class TrimmedText {
        private final String text;
        private final boolean truncated;
        public TrimmedText(String text, boolean truncated) {
            this.text = text;
            this.truncated = truncated;
        }
        public String text() { return text; }
        public boolean truncated() { return truncated; }
    }
}
