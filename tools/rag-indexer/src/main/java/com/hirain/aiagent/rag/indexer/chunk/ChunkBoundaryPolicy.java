package com.hirain.aiagent.rag.indexer.chunk;

/** Chunk Token 预算；Overlap 只预留给连续普通文本，表格与 Warning 不得跨边界复制。 */
public record ChunkBoundaryPolicy(int maxChildTokens, int overlapTokens, int tableRowsPerChild,
                                  int softMaxChildTokens, int hardMaxChildTokens, int idealMinChildTokens,
                                  int parentSoftMaxTokens, int parentHardMaxTokens, double parentSplitOverlapRatio,
                                  double paragraphCosineThreshold, int forceMergeMaxTokens,
                                  int directMergeMaxTokens) {
    public ChunkBoundaryPolicy {
        if (maxChildTokens < 32 || overlapTokens < 0 || overlapTokens >= maxChildTokens || tableRowsPerChild < 1
                || softMaxChildTokens < maxChildTokens || hardMaxChildTokens < softMaxChildTokens
                || idealMinChildTokens < 0 || idealMinChildTokens > maxChildTokens
                || forceMergeMaxTokens < 0 || directMergeMaxTokens < forceMergeMaxTokens
                || (idealMinChildTokens > 0 && (forceMergeMaxTokens < 1
                || forceMergeMaxTokens >= directMergeMaxTokens || directMergeMaxTokens >= idealMinChildTokens))
                || parentSoftMaxTokens < 0 || parentHardMaxTokens < parentSoftMaxTokens
                || parentSplitOverlapRatio < 0.0d || parentSplitOverlapRatio > 0.20d
                || paragraphCosineThreshold <= 0.0d || paragraphCosineThreshold > 1.0d) {
            throw new IllegalArgumentException("CHUNK_POLICY_INVALID");
        }
    }

    /** 兼容 V1 调用点：旧策略只有一个 Child 上限，因此三个长度边界保持一致。 */
    public ChunkBoundaryPolicy(int maxChildTokens, int overlapTokens, int tableRowsPerChild) {
        this(maxChildTokens, overlapTokens, tableRowsPerChild, maxChildTokens, maxChildTokens, 0, 0, 2000, 0.0d, 0.7d, 0, 0);
    }

    /** 兼容已创建的 V2 测试/调用点；默认以目标值的约七成作为理想下界。 */
    public ChunkBoundaryPolicy(int maxChildTokens, int overlapTokens, int tableRowsPerChild,
                               int softMaxChildTokens, int hardMaxChildTokens) {
        this(maxChildTokens, overlapTokens, tableRowsPerChild, softMaxChildTokens, hardMaxChildTokens,
                Math.max(0, (int) Math.floor(maxChildTokens * 0.7d)), 1200, 2000, 0.10d, 0.7d, 30, 100);
    }

    public ChunkBoundaryPolicy(int maxChildTokens, int overlapTokens, int tableRowsPerChild,
                               int softMaxChildTokens, int hardMaxChildTokens, int idealMinChildTokens) {
        this(maxChildTokens, overlapTokens, tableRowsPerChild, softMaxChildTokens, hardMaxChildTokens,
                idealMinChildTokens, 1200, 2000, 0.10d, 0.7d, 30, 100);
    }

    public ChunkBoundaryPolicy(int maxChildTokens, int overlapTokens, int tableRowsPerChild,
                               int softMaxChildTokens, int hardMaxChildTokens, int idealMinChildTokens,
                               int parentSoftMaxTokens, int parentHardMaxTokens, double parentSplitOverlapRatio) {
        this(maxChildTokens, overlapTokens, tableRowsPerChild, softMaxChildTokens, hardMaxChildTokens,
                idealMinChildTokens, parentSoftMaxTokens, parentHardMaxTokens, parentSplitOverlapRatio, 0.7d, 30, 100);
    }

    public ChunkBoundaryPolicy(int maxChildTokens, int overlapTokens, int tableRowsPerChild,
                               int softMaxChildTokens, int hardMaxChildTokens, int idealMinChildTokens,
                               int parentSoftMaxTokens, int parentHardMaxTokens, double parentSplitOverlapRatio,
                               double paragraphCosineThreshold) {
        this(maxChildTokens, overlapTokens, tableRowsPerChild, softMaxChildTokens, hardMaxChildTokens,
                idealMinChildTokens, parentSoftMaxTokens, parentHardMaxTokens, parentSplitOverlapRatio,
                paragraphCosineThreshold, 30, 100);
    }

}
