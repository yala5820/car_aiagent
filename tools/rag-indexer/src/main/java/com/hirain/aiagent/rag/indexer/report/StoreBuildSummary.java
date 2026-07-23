package com.hirain.aiagent.rag.indexer.report;

/** Store 写入、重开校验及文件摘要统计。 */
public record StoreBuildSummary(long documentCount, long parentChunkCount, long childChunkCount, long lexicalTermCount,
                                long dataFileSizeBytes, String dataFileSha256, long durationMs) { }
