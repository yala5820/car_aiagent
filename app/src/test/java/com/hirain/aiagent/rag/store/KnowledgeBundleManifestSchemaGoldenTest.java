package com.hirain.aiagent.rag.store;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** 共享 Schema 与离线候选必须共同约束 Android 解析器，禁止端侧自行放宽协议。 */
public class KnowledgeBundleManifestSchemaGoldenTest {
    private static final Path SCHEMA = Path.of("..", "rag-schema", "contracts", "knowledge-bundle-manifest.schema.json");
    private static final Path CANDIDATE = Path.of("..", "rag-schema", "test-fixtures", "development-v1", "candidate-v1", "manifest.json");

    @Test public void sharedSchemaAndDevelopmentGoldenAreAccepted() throws Exception {
        String schema = new String(Files.readAllBytes(SCHEMA), StandardCharsets.UTF_8);
        assertTrue(schema.contains("\"additionalProperties\": false"));
        assertTrue(schema.contains("\"text-embedding-v4\""));
        assertTrue(schema.contains("\"STATIC_HTML\""));
        assertTrue(schema.contains("\"embeddedDataExtraction\""));
        String manifest = new String(Files.readAllBytes(CANDIDATE), StandardCharsets.UTF_8);
        assertEquals("dev-fixture", new KnowledgeBundleManifestParser().parse(manifest).bundleId());
    }

    @Test public void rejectsUnknownNestedProtocolField() throws Exception {
        String manifest = new String(Files.readAllBytes(CANDIDATE), StandardCharsets.UTF_8);
        String invalid = manifest.replace("\"hnsw\":{", "\"hnsw\":{\"unexpected\":true,");
        try {
            new KnowledgeBundleManifestParser().parse(invalid);
            fail("嵌套协议字段必须遵循共享 Schema 的 additionalProperties 约束");
        } catch (IllegalArgumentException expected) {
            assertEquals("MANIFEST_UNKNOWN_FIELD", expected.getMessage());
        }
    }
}
