package com.hirain.aiagent.rag.policy;

/**
 * 不可变知识路由决策。reasonCode 是稳定受控码，供 Runtime/Trace 使用，
 * 不包含用户原文；compoundIntentDetected 表示 V1 必须在能力规划期终止并提示拆分。
 */
public record KnowledgeIntentDecision(KnowledgeRequirement requirement, String reasonCode,
                                      String ruleVersion, boolean compoundIntentDetected) {
    public KnowledgeIntentDecision {
        requirement = requirement == null ? KnowledgeRequirement.NONE : requirement;
        reasonCode = reasonCode == null || reasonCode.isBlank() ? "KNOWLEDGE_NOT_REQUIRED" : reasonCode;
        ruleVersion = ruleVersion == null || ruleVersion.isBlank() ? "knowledge-need-v1" : ruleVersion;
    }
    public static KnowledgeIntentDecision none(String reasonCode) {
        return new KnowledgeIntentDecision(KnowledgeRequirement.NONE, reasonCode, "knowledge-need-v1", false);
    }
}
