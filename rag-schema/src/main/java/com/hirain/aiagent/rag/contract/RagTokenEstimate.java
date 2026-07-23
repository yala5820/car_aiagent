package com.hirain.aiagent.rag.contract;

/**
 * RAG V2 预算估算结果。
 *
 * <p>这是跨端的确定性近似值，不试图伪装为云端模型的精确 tokenizer。拆分统计用于
 * 构建诊断和 Golden 定位，真正参与预算的是 {@link #totalTokens()}。</p>
 */
public record RagTokenEstimate(
        String algorithmVersion,
        int totalTokens,
        int cjkTokens,
        int latinAndDigitTokens,
        int punctuationTokens,
        int newlineTokens,
        int otherTokens) {
    public RagTokenEstimate {
        if (algorithmVersion == null || algorithmVersion.isBlank() || totalTokens < 0
                || cjkTokens < 0 || latinAndDigitTokens < 0 || punctuationTokens < 0
                || newlineTokens < 0 || otherTokens < 0) {
            throw new IllegalArgumentException("RAG_TOKEN_ESTIMATE_INVALID");
        }
        if (totalTokens != cjkTokens + latinAndDigitTokens + punctuationTokens + newlineTokens + otherTokens) {
            throw new IllegalArgumentException("RAG_TOKEN_ESTIMATE_TOTAL_MISMATCH");
        }
    }
}
