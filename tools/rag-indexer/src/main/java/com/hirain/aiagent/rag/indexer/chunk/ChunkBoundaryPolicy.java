package com.hirain.aiagent.rag.indexer.chunk;

/** Chunk Token 预算；Overlap 只预留给连续普通文本，表格与 Warning 不得跨边界复制。 */
public record ChunkBoundaryPolicy(int maxChildTokens, int overlapTokens, int tableRowsPerChild) {
    public ChunkBoundaryPolicy {
        if (maxChildTokens < 32 || overlapTokens < 0 || overlapTokens >= maxChildTokens || tableRowsPerChild < 1) {
            throw new IllegalArgumentException("CHUNK_POLICY_INVALID");
        }
    }
}
