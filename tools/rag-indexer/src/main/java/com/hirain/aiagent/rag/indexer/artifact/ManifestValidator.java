package com.hirain.aiagent.rag.indexer.artifact;

import com.hirain.aiagent.rag.store.KnowledgeStoreContract;

import java.util.LinkedHashMap;
import java.util.List;

/** 以共享 Schema 的必填字段和 V1 常量为门禁；Android 将复用同一 JSON Schema 作激活校验。 */
public final class ManifestValidator {
    public void validate(KnowledgeBundleManifest value) {
        require(value.formatVersion() == 1 && value.sourceLocatorSchemaVersion() == 1, "Manifest 协议版本非法");
        requireText(value.bundleId()); requireText(value.bundleVersion()); requireText(value.knowledgeScopeId()); requireText(value.builderVersion());
        require(value.builtAtEpochMs() >= 0 && value.dataFile().sizeBytes() > 0 && value.dataFile().sha256().matches("[0-9a-f]{64}"), "data.mdb 哈希非法");
        require(value.schemaFingerprint().matches("sha256:[0-9a-f]{64}"), "Schema Fingerprint 非法");
        require(value.hnsw().get("configFingerprint") instanceof String hash && hash.matches("sha256:[0-9a-f]{64}"), "HNSW Hash 非法");
        var hnswDetails = new LinkedHashMap<>(value.hnsw()); hnswDetails.remove("configFingerprint");
        require(hnswDetails.equals(KnowledgeStoreContract.hnswManifestDetails()), "HNSW 参数与共享 Schema 不一致");
        require("DashScope".equals(value.embedding().get("provider")) && "text-embedding-v4".equals(value.embedding().get("model"))
                && Integer.valueOf(1024).equals(value.embedding().get("dimension")), "Embedding 协议非法");
        require(List.of("PDF", "STATIC_HTML", "MARKDOWN").equals(value.parsers().get("supportedSourceFormats")), "Parser 格式协议非法");
        for (String key : List.of("vehicleModel", "modelYear", "region", "softwareVersion", "configurationCode")) requireText(value.scope().get(key));
        require(value.parsers().get("configHash") instanceof String hash && hash.matches("sha256:[0-9a-f]{64}"), "Parser Hash 非法");
        require(value.chunking().get("configHash") instanceof String hash && hash.matches("sha256:[0-9a-f]{64}"), "Chunk Hash 非法");
        require(value.corpus().get("corpusHash") instanceof String hash && hash.matches("sha256:[0-9a-f]{64}"), "Corpus Hash 非法");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private static void requireText(String value) { require(value != null && !value.isBlank(), "Manifest 字段不能为空"); }
}
