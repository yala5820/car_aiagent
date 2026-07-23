package com.hirain.aiagent.rag.indexer.report;

/** Chunk 阶段的可审计统计，不保存 Parent/Child 正文。 */
public record ChunkBuildSummary(long parentCount, long childCount, long warningChunkCount, long tableChunkCount, long durationMs) { }
