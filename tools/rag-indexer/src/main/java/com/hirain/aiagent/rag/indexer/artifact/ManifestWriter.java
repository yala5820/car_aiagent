package com.hirain.aiagent.rag.indexer.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** 仅写入明确协议字段，使用排序 Map 与无绝对路径输入保持可复现、可公开审计。 */
public final class ManifestWriter {
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    public void write(Path file, KnowledgeBundleManifest value) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>(); root.put("formatVersion", value.formatVersion()); root.put("bundleId", value.bundleId());
        root.put("bundleVersion", value.bundleVersion()); root.put("knowledgeScopeId", value.knowledgeScopeId()); root.put("builderVersion", value.builderVersion());
        root.put("builtAtEpochMs", value.builtAtEpochMs()); root.put("objectBoxVersion", value.objectBoxVersion()); root.put("schemaFingerprint", value.schemaFingerprint()); root.put("sourceLocatorSchemaVersion", value.sourceLocatorSchemaVersion());
        root.put("dataFile", Map.of("name", "data.mdb", "sizeBytes", value.dataFile().sizeBytes(), "sha256", value.dataFile().sha256()));
        root.put("embedding", value.embedding()); root.put("hnsw", value.hnsw()); root.put("scope", value.scope()); root.put("parsers", value.parsers()); root.put("lexical", value.lexical()); root.put("chunking", value.chunking()); root.put("corpus", value.corpus());
        json.writeValue(file.toFile(), root);
    }
}
