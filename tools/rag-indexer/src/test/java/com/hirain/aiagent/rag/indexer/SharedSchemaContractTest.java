package com.hirain.aiagent.rag.indexer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hirain.aiagent.rag.store.MyObjectBox;
import com.hirain.aiagent.rag.store.SchemaFingerprint;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;
import com.hirain.aiagent.rag.store.entity.KnowledgeDocumentEntity;
import com.hirain.aiagent.rag.store.entity.KnowledgeStoreMetadataEntity;
import com.hirain.aiagent.rag.store.entity.LexicalTermEntity;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * 共享 Schema 的低成本契约测试。它不创建 Store；G004 才负责真实数据写入，
 * 这里仅防止实体、1024 维 Cosine 注解和已提交 Meta Model 被无意漂移。
 */
final class SharedSchemaContractTest {

    private static final Path MODEL_FILE = Path.of("..", "..", "rag-schema", "objectbox-models", "default.json");
    private static final Path GENERATED_MODEL_SOURCE = Path.of(
            "build", "generated", "sources", "annotationProcessor", "java", "main",
            "com", "hirain", "aiagent", "rag", "store", "MyObjectBox.java"
    );
    private static final String SHARED_MODEL_FINGERPRINT = "sha256:453278ef0c1d17c8f799f2af9dc4142a802ae87bd8db381e0392bf5eb1606b4c";

    @Test
    void shouldKeepRequiredEntityContractAndGeneratedModel() throws Exception {
        assertNotNull(KnowledgeStoreMetadataEntity.class.getDeclaredField("schemaFingerprint"));
        assertNotNull(KnowledgeDocumentEntity.class.getDeclaredField("documentId"));
        assertNotNull(LexicalTermEntity.class.getDeclaredField("chunkEntityIds"));

        assertEquals(float[].class, KnowledgeChunkEntity.class.getDeclaredField("embedding").getType());

        assertTrue(Files.isRegularFile(MODEL_FILE), "共享 default.json 必须已提交，禁止测试中重建 UID");
        String model = Files.readString(MODEL_FILE);
        assertEquals(SHARED_MODEL_FINGERPRINT, SchemaFingerprint.sha256OfNormalizedUtf8(model));
        assertTrue(model.contains("KnowledgeChunkEntity"));
        assertTrue(model.contains("KnowledgeDocumentEntity"));
        assertTrue(model.contains("KnowledgeStoreMetadataEntity"));
        assertTrue(model.contains("LexicalTermEntity"));
        String generatedModel = Files.readString(GENERATED_MODEL_SOURCE);
        assertTrue(generatedModel.contains("HnswDistanceType.Cosine"));
        assertTrue(generatedModel.contains(".hnswParams(1024, 30L, 100L,"),
                "生成模型必须显式写入共享 HNSW M/efConstruction，禁止退回 Runtime 隐式默认值");
        assertNotNull(MyObjectBox.builder(), "外部 sourceSet Entity 必须可生成同一 MyObjectBox");
    }

    @Test
    void shouldProduceStableFingerprintForEquivalentLineEndings() {
        String lf = "{\n  \"version\": 1\n}\n";
        String crlfWithBom = "\uFEFF{\r\n  \"version\": 1\r\n}\r\n";
        assertEquals(
                SchemaFingerprint.sha256OfNormalizedUtf8(lf),
                SchemaFingerprint.sha256OfNormalizedUtf8(crlfWithBom)
        );
    }

    @Test
    void shouldKeepAllSharedGoldensAsValidJson() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Path vectors = Path.of("..", "..", "rag-schema", "test-vectors");
        try (var files = Files.list(vectors)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                assertNotNull(objectMapper.readTree(file.toFile()), "Golden 必须是可解析 JSON：" + file);
            }
        }
        Path manifestSchema = Path.of("..", "..", "rag-schema", "contracts", "knowledge-bundle-manifest.schema.json");
        assertNotNull(objectMapper.readTree(manifestSchema.toFile()), "Manifest Schema 必须是可解析 JSON");
    }
}
