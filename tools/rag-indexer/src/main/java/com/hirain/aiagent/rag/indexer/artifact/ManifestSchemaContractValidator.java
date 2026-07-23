package com.hirain.aiagent.rag.indexer.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** 读取共享 JSON Schema 的 required/额外字段约束，防止 CLI 私自漂移协议顶层字段。 */
public final class ManifestSchemaContractValidator {
    public void validate(Path schemaFile, Path manifestFile) throws IOException {
        try (InputStream input = java.nio.file.Files.newInputStream(schemaFile)) { validate(input, manifestFile); }
    }
    public void validate(InputStream schemaInput, Path manifestFile) throws IOException {
        ObjectMapper mapper = new ObjectMapper(); JsonNode schema = mapper.readTree(schemaInput); JsonNode manifest = mapper.readTree(manifestFile.toFile());
        if (!manifest.isObject()) throw new IllegalArgumentException("Manifest 必须是对象");
        Set<String> required = new HashSet<>(); schema.path("required").forEach(node -> required.add(node.asText()));
        Set<String> actual = new HashSet<>(); manifest.fieldNames().forEachRemaining(actual::add);
        if (!actual.containsAll(required)) throw new IllegalArgumentException("Manifest 缺少共享 Schema 必填字段");
        if (schema.path("additionalProperties").isBoolean() && !schema.path("additionalProperties").asBoolean()) {
            Set<String> allowed = new HashSet<>(); schema.path("properties").fieldNames().forEachRemaining(allowed::add);
            if (!allowed.containsAll(actual)) throw new IllegalArgumentException("Manifest 包含共享 Schema 未定义字段");
        }
    }
}
