package com.hirain.aiagent.rag.indexer.chunk;

/** Chunk Token 预算；Overlap 只预留给连续普通文本，表格与 Warning 不得跨边界复制。 */
public record ChunkBoundaryPolicy(int maxChildTokens, int overlapTokens, int tableRowsPerChild,
                                  int softMaxChildTokens, int hardMaxChildTokens, int idealMinChildTokens) {
    public ChunkBoundaryPolicy {
        if (maxChildTokens < 32 || overlapTokens < 0 || overlapTokens >= maxChildTokens || tableRowsPerChild < 1
                || softMaxChildTokens < maxChildTokens || hardMaxChildTokens < softMaxChildTokens
                || idealMinChildTokens < 0 || idealMinChildTokens > maxChildTokens) {
            throw new IllegalArgumentException("CHUNK_POLICY_INVALID");
        }
    }

    /** 兼容 V1 调用点：旧策略只有一个 Child 上限，因此三个长度边界保持一致。 */
    public ChunkBoundaryPolicy(int maxChildTokens, int overlapTokens, int tableRowsPerChild) {
        this(maxChildTokens, overlapTokens, tableRowsPerChild, maxChildTokens, maxChildTokens, 0);
    }

    /** 兼容已创建的 V2 测试/调用点；默认以目标值的约七成作为理想下界。 */
    public ChunkBoundaryPolicy(int maxChildTokens, int overlapTokens, int tableRowsPerChild,
                               int softMaxChildTokens, int hardMaxChildTokens) {
        this(maxChildTokens, overlapTokens, tableRowsPerChild, softMaxChildTokens, hardMaxChildTokens,
                Math.max(0, (int) Math.floor(maxChildTokens * 0.7d)));
    }
}
