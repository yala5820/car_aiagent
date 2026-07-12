package com.hirain.aiagent.context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;

/**
 * 结构化预算决策结果 — 表达预算后的最终消息、工具、裁剪记录、压缩建议和最终预算状态。
 * <p>
 * 所有裁剪保持原始 List 不变，返回新的不可变列表。
 */
public final class ContextBudgetDecision {

    private final List<ChatMessage> messages;
    private final List<ToolSpecification> toolSpecifications;
    private final int estimatedBeforeTokens;
    private final int estimatedAfterTokens;
    private final int maxInputTokens;
    private final boolean withinBudget;
    private final List<TrimAction> trimActions;
    private final boolean compressionRecommended;
    private final int targetSessionMemoryTokens;
    private final ContextErrorCode failureCode;
    private final String failureDetail;

    public ContextBudgetDecision(List<ChatMessage> messages,
                                  List<ToolSpecification> toolSpecifications,
                                  int estimatedBeforeTokens,
                                  int estimatedAfterTokens,
                                  int maxInputTokens,
                                  boolean withinBudget,
                                  List<TrimAction> trimActions,
                                  boolean compressionRecommended,
                                  int targetSessionMemoryTokens,
                                  ContextErrorCode failureCode,
                                  String failureDetail) {
        this.messages = messages != null
                ? Collections.unmodifiableList(new ArrayList<>(messages))
                : List.of();
        this.toolSpecifications = toolSpecifications != null
                ? Collections.unmodifiableList(new ArrayList<>(toolSpecifications))
                : List.of();
        this.estimatedBeforeTokens = estimatedBeforeTokens;
        this.estimatedAfterTokens = estimatedAfterTokens;
        this.maxInputTokens = maxInputTokens;
        this.withinBudget = withinBudget;
        this.trimActions = trimActions != null
                ? Collections.unmodifiableList(new ArrayList<>(trimActions))
                : List.of();
        this.compressionRecommended = compressionRecommended;
        this.targetSessionMemoryTokens = targetSessionMemoryTokens;
        this.failureCode = failureCode;
        this.failureDetail = failureDetail;
    }

    public List<ChatMessage> messages() { return messages; }
    public List<ToolSpecification> toolSpecifications() { return toolSpecifications; }
    public int estimatedBeforeTokens() { return estimatedBeforeTokens; }
    public int estimatedAfterTokens() { return estimatedAfterTokens; }
    public int maxInputTokens() { return maxInputTokens; }
    public boolean withinBudget() { return withinBudget; }
    public List<TrimAction> trimActions() { return trimActions; }
    public boolean compressionRecommended() { return compressionRecommended; }
    public int targetSessionMemoryTokens() { return targetSessionMemoryTokens; }
    public ContextErrorCode failureCode() { return failureCode; }
    public String failureDetail() { return failureDetail; }

    /** 单个裁剪动作记录。 */
    public static final class TrimAction {
        private final String sourceKey;
        private final String actionType;
        private final int beforeTokens;
        private final int afterTokens;

        public TrimAction(String sourceKey, String actionType, int beforeTokens, int afterTokens) {
            this.sourceKey = sourceKey;
            this.actionType = actionType;
            this.beforeTokens = beforeTokens;
            this.afterTokens = afterTokens;
        }

        public String sourceKey() { return sourceKey; }
        public String actionType() { return actionType; }
        public int beforeTokens() { return beforeTokens; }
        public int afterTokens() { return afterTokens; }
    }
}
