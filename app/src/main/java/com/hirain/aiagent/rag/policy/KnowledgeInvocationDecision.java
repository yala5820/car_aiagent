package com.hirain.aiagent.rag.policy;

/** 单次知识 Tool 调用的受控准入结果；拒绝发生在网络或本地检索之前。 */
public record KnowledgeInvocationDecision(boolean allowed, String reasonCode) {
    public static KnowledgeInvocationDecision allow() { return new KnowledgeInvocationDecision(true, "ALLOWED"); }
    public static KnowledgeInvocationDecision deny(String reasonCode) { return new KnowledgeInvocationDecision(false, reasonCode); }
}
