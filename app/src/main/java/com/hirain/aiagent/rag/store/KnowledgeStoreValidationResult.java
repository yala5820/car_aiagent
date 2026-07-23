package com.hirain.aiagent.rag.store;

/** Store 激活前的受控校验结果；诊断仅为稳定原因码，绝不携带堆栈或路径。 */
public record KnowledgeStoreValidationResult(boolean valid, String reasonCode) {
    public static KnowledgeStoreValidationResult success() { return new KnowledgeStoreValidationResult(true, null); }
    public static KnowledgeStoreValidationResult failure(String reasonCode) { return new KnowledgeStoreValidationResult(false, reasonCode); }
}
