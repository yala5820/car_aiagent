package com.hirain.aiagent.rag.store;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/** 使用离线 G404 实际生成的开发 Manifest 验证 Android 侧解析器不发生协议漂移。 */
public class KnowledgeBundleManifestParserTest {
    @Test public void parsesDevelopmentCandidateManifest() throws Exception {
        String json = new String(Files.readAllBytes(Path.of("..", "rag-schema", "test-fixtures", "development-v1", "candidate-v1", "manifest.json")), java.nio.charset.StandardCharsets.UTF_8);
        KnowledgeBundleManifest manifest = new KnowledgeBundleManifestParser().parse(json);
        assertEquals("dev-fixture", manifest.bundleId());
        assertEquals(1024, manifest.embedding().dimension());
        assertEquals("m1-2026-cn-1-0-base", manifest.knowledgeScopeId());
    }
    @Test public void rejectsUnknownTopLevelField() {
        try { new KnowledgeBundleManifestParser().parse("{\"formatVersion\":1,\"unknown\":true}"); fail("必须拒绝未知/缺失协议"); }
        catch (IllegalArgumentException expected) { assertEquals("MANIFEST_REQUIRED_FIELD_MISSING", expected.getMessage()); }
    }
    @Test public void rejectsHnswParameterDrift() throws Exception {
        String json = new String(Files.readAllBytes(Path.of("..", "rag-schema", "test-fixtures", "development-v1", "candidate-v1", "manifest.json")), java.nio.charset.StandardCharsets.UTF_8);
        try {
            new KnowledgeBundleManifestParser().parse(json.replace("\"neighborsPerNode\":30", "\"neighborsPerNode\":16"));
            fail("候选 Bundle 的 HNSW 参数与共享 Entity 不一致时必须拒绝激活");
        } catch (IllegalArgumentException expected) {
            assertEquals("MANIFEST_HNSW_INCOMPATIBLE", expected.getMessage());
        }
    }
    @Test public void acceptsDeclaredStaticHtmlExtractionStrategyButRejectsUnknownStrategy() throws Exception {
        String json = new String(Files.readAllBytes(Path.of("..", "rag-schema", "test-fixtures", "development-v1", "candidate-v1", "manifest.json")), java.nio.charset.StandardCharsets.UTF_8);
        String declared = json.replace("\"scriptExecution\":false", "\"embeddedDataExtraction\":\"TESLA_SERVICE_CENTERS_V1\",\"scriptExecution\":false");
        assertEquals("dev-fixture", new KnowledgeBundleManifestParser().parse(declared).bundleId());
        try {
            new KnowledgeBundleManifestParser().parse(declared.replace("TESLA_SERVICE_CENTERS_V1", "UNREVIEWED_DYNAMIC_EXECUTION"));
            fail("未审核 HTML 提取策略不得被 Android 激活");
        } catch (IllegalArgumentException expected) {
            assertEquals("MANIFEST_HTML_EXTRACTION_UNSUPPORTED", expected.getMessage());
        }
    }
}
