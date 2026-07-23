package com.hirain.aiagent.rag.indexer.config;

/** 版本化 Child Chunk 边界；禁止由 CLI 临时覆盖，保证评测与 Bundle 可复现。 */
public record ChunkingConfig(int maxChildTokens, int overlapTokens, int tableRowsPerChild) {
    public ChunkingConfig {
        if (maxChildTokens < 32 || overlapTokens < 0 || overlapTokens >= maxChildTokens || tableRowsPerChild < 1) {
            throw new IllegalArgumentException("CHUNK_POLICY_INVALID");
        }
    }

    /** 兼容既有 TEST_ONLY Fixture 的冻结基线。 */
    public static ChunkingConfig v1Default() {
        return new ChunkingConfig(256, 32, 20);
    }
}
