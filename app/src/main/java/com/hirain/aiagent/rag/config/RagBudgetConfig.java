package com.hirain.aiagent.rag.config;

/** Rerank 与供模型阅读的 Evidence 使用独立预算，防止任一环节吞噬另一个环节容量。 */
public record RagBudgetConfig(int rerankMaxCharacters, int evidenceMaxCharacters, int transportMaxCharacters) {
    public static RagBudgetConfig v1() { return new RagBudgetConfig(12_000, 8_000, 16_000); }
}
