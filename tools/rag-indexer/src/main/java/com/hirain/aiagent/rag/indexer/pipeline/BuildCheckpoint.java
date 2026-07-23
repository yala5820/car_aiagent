package com.hirain.aiagent.rag.indexer.pipeline;

/** Checkpoint 只在全部指纹匹配时允许恢复，防止 Schema/Parser/Chunk/Template 漂移。 */
public record BuildCheckpoint(BuildPhase phase, String schemaFingerprint, String parserFingerprint, String chunkFingerprint, String embeddingTemplateFingerprint) { }
