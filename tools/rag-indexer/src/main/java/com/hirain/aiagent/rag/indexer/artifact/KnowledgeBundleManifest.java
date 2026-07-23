package com.hirain.aiagent.rag.indexer.artifact;

import java.util.Map;

/** 与共享 JSON Schema 一一对应的 V1 Manifest 内存模型；JSON 输出由 ManifestWriter 固化键顺序。 */
public record KnowledgeBundleManifest(
        int formatVersion, String bundleId, String bundleVersion, String knowledgeScopeId, String builderVersion,
        long builtAtEpochMs, String objectBoxVersion, String schemaFingerprint, int sourceLocatorSchemaVersion,
        BundleFileHasher.FileHash dataFile, Map<String, Object> embedding, Map<String, Object> hnsw,
        Map<String, String> scope, Map<String, Object> parsers, Map<String, Object> lexical,
        Map<String, Object> chunking, Map<String, Object> corpus) { }
