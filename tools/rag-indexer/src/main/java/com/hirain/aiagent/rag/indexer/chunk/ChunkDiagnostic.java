package com.hirain.aiagent.rag.indexer.chunk;

/** Chunk 阶段仅记录稳定原因码，禁止以部分文本继续构建。 */
public record ChunkDiagnostic(String reasonCode, String summary) {
}
