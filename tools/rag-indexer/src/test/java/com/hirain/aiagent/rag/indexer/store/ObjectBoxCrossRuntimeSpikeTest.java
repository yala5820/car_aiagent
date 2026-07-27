package com.hirain.aiagent.rag.indexer.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.hirain.aiagent.rag.store.MyObjectBox;
import com.hirain.aiagent.rag.store.SchemaFingerprint;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;
import com.hirain.aiagent.rag.store.entity.KnowledgeDocumentEntity;
import com.hirain.aiagent.rag.store.entity.KnowledgeStoreMetadataEntity;
import com.hirain.aiagent.rag.store.entity.LexicalTermEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.objectbox.Box;
import io.objectbox.BoxStore;
import org.junit.jupiter.api.Test;

/**
 * G004 离线生产侧 Spike：只写入受控合成数据，证明共享 Meta Model 能包含三种
 * SourceFormat、Parent/Child、1024 维向量与 Lexical postings。Android 消费验证不在
 * 此测试内完成，必须由调度中的 G006-G008 使用同一产物执行。
 */
final class ObjectBoxCrossRuntimeSpikeTest {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Path SCHEMA_ROOT = Path.of("..", "..", "rag-schema");

    @Test
    void shouldWriteAndReopenSyntheticFixture() throws Exception {
        Path configuredOutput = configuredOutput();
        Path temporaryRoot = configuredOutput == null ? Files.createTempDirectory("objectbox-v1-spike-") : configuredOutput;
        if (configuredOutput != null) {
            if (Files.exists(configuredOutput)) {
                try (var existingFiles = Files.list(configuredOutput)) {
                    assertFalse(existingFiles.findAny().isPresent(), "Fixture 已存在，拒绝覆盖；Schema 变化必须创建新版本并重新走 Gate");
                }
            }
        }
        Files.createDirectories(temporaryRoot);
        FixtureSummary summary = createFixture(temporaryRoot);
        assertTrue(Files.isRegularFile(temporaryRoot.resolve("data.mdb")));
        assertTrue(Files.isRegularFile(temporaryRoot.resolve("manifest.json")));
        assertTrue(Files.isRegularFile(temporaryRoot.resolve("fixture-report.json")));
        assertFalse(Files.exists(temporaryRoot.resolve("lock.mdb")), "交付目录只能保留协议允许文件");
        assertEquals(3, summary.documentCount());
        assertEquals(3, summary.childChunkCount());
    }

    private static FixtureSummary createFixture(Path outputDirectory) throws Exception {
        Path storeDirectory = Files.createTempDirectory("objectbox-v1-store-");
        FixtureSummary summary;
        try (BoxStore store = MyObjectBox.builder().directory(storeDirectory.toFile()).build()) {
            Box<KnowledgeDocumentEntity> documents = store.boxFor(KnowledgeDocumentEntity.class);
            Box<KnowledgeChunkEntity> chunks = store.boxFor(KnowledgeChunkEntity.class);
            Box<LexicalTermEntity> terms = store.boxFor(LexicalTermEntity.class);
            Box<KnowledgeStoreMetadataEntity> metadata = store.boxFor(KnowledgeStoreMetadataEntity.class);

            documents.put(List.of(
                    document("pdf-maintenance", "PDF", "车辆保养手册", "UTF-8", 20),
                    document("html-maintenance", "STATIC_HTML", "静态维护页面", "UTF-8", 0),
                    document("md-warning", "MARKDOWN", "Markdown 警告说明", "UTF-8", 0)
            ));
            KnowledgeChunkEntity pdfParent = parent("pdf-parent", "pdf-maintenance", "PDF", 12, 12, 0, 0);
            KnowledgeChunkEntity htmlParent = parent("html-parent", "html-maintenance", "STATIC_HTML", 0, 0, 0, 0);
            KnowledgeChunkEntity markdownParent = parent("md-parent", "md-warning", "MARKDOWN", 0, 0, 41, 41);
            chunks.put(List.of(pdfParent, htmlParent, markdownParent));
            long pdfChildId = chunks.put(child("pdf-child", "pdf-parent", "pdf-maintenance", "PDF", 12, 12, 0, 0, 0.01f));
            long htmlChildId = chunks.put(child("html-child", "html-parent", "html-maintenance", "STATIC_HTML", 0, 0, 0, 0, 0.02f));
            long markdownChildId = chunks.put(child("md-child", "md-parent", "md-warning", "MARKDOWN", 0, 0, 41, 45, 0.03f));

            // 当前 Android BM25 协议按 TITLE/BODY 独立倒排；Fixture 必须使用与正式 Bundle
            // 相同的字段前缀和中文二/三元词，避免测试绕过真实检索协议。
            for (String field : List.of("TITLE", "BODY")) {
                for (String rawTerm : List.of("制动", "动液", "制动液")) {
                    LexicalTermEntity term = new LexicalTermEntity();
                    term.term = field + ":" + rawTerm;
                    term.field = field;
                    term.rawTerm = rawTerm;
                    term.documentFrequency = 3;
                    term.chunkEntityIds = new long[]{pdfChildId, htmlChildId, markdownChildId};
                    term.termFrequencies = new int[]{1, 1, 1};
                    terms.put(term);
                }
            }

            KnowledgeStoreMetadataEntity current = new KnowledgeStoreMetadataEntity();
            current.bundleId = "synthetic-objectbox-v1";
            current.bundleVersion = "fixture-v1";
            current.knowledgeScopeId = "demo-model-2026-cn-demo-version-default";
            current.formatVersion = 1;
            current.schemaFingerprint = schemaFingerprint();
            current.builderVersion = "rag-indexer-fixture";
            current.objectBoxVersion = "5.4.0";
            current.embeddingProvider = "DashScope";
            current.embeddingModel = "text-embedding-v4";
            current.embeddingDimension = 1024;
            current.distanceType = "COSINE";
            current.hnswConfigFingerprint = "sha256:fixture-hnsw-not-for-release";
            current.embeddingTemplateVersion = 1;
            current.lexicalAnalyzerVersion = 1;
            current.sourceLocatorSchemaVersion = 1;
            current.parserConfigHash = "sha256:fixture-parser-not-for-release";
            current.supportedSourceFormats = "[\"PDF\",\"STATIC_HTML\",\"MARKDOWN\"]";
            current.sourceFormatCounts = "{\"MARKDOWN\":1,\"PDF\":1,\"STATIC_HTML\":1}";
            current.chunkingConfigHash = "sha256:fixture-chunking-not-for-release";
            current.corpusHash = "sha256:fixture-corpus-not-for-release";
            current.documentCount = 3;
            current.parentChunkCount = 3;
            current.childChunkCount = 3;
            current.lexicalTermCount = 6;
            current.averageLexicalDocumentLength = 5.0;
            current.averageLexicalTitleLength = 5.0;
            current.averageLexicalBodyLength = 5.0;
            current.bm25TitleWeight = 1.0;
            current.bm25BodyWeight = 1.0;
            current.lexicalFieldVersion = 2;
            current.builtAtEpochMs = 0;
            metadata.put(current);

            assertEquals(3, documents.count());
            assertEquals(6, chunks.count());
            assertEquals(6, terms.count());
            assertEquals(1, metadata.count());
            assertEquals(1024, chunks.get(pdfChildId).embedding.length);
            assertTrue(chunks.get(pdfParent.id).embedding == null);
            summary = new FixtureSummary(3, 3, 3, 6, schemaFingerprint());
        }

        Files.copy(storeDirectory.resolve("data.mdb"), outputDirectory.resolve("data.mdb"));
        writeJson(outputDirectory.resolve("manifest.json"), manifest(summary, outputDirectory.resolve("data.mdb")));
        writeJson(outputDirectory.resolve("fixture-report.json"), fixtureReport(summary));
        return summary;
    }

    private static KnowledgeDocumentEntity document(String id, String format, String title, String charset, int pageCount) {
        KnowledgeDocumentEntity document = new KnowledgeDocumentEntity();
        document.documentId = id;
        document.documentTitle = title;
        document.documentType = "OWNER_MANUAL";
        document.documentVersion = "fixture-v1";
        document.language = "zh-CN";
        document.vehicleModel = "DEMO_MODEL";
        document.modelYear = "2026";
        document.region = "CN";
        document.softwareVersion = "DEMO_VERSION";
        document.configurationCode = "DEFAULT";
        document.sourceFormat = format;
        document.sourceFileName = id + ".fixture";
        document.sourceSha256 = "fixture-" + id;
        document.sourceCharset = charset;
        document.pageCount = pageCount;
        return document;
    }

    private static KnowledgeChunkEntity parent(String id, String documentId, String format, int pageStart, int pageEnd, int lineStart, int lineEnd) {
        return chunk(id, "PARENT", "", documentId, format, pageStart, pageEnd, lineStart, lineEnd, null);
    }

    private static KnowledgeChunkEntity child(String id, String parentId, String documentId, String format, int pageStart, int pageEnd, int lineStart, int lineEnd, float seed) {
        return chunk(id, "CHILD", parentId, documentId, format, pageStart, pageEnd, lineStart, lineEnd, vector(seed));
    }

    private static KnowledgeChunkEntity chunk(String id, String level, String parentId, String documentId, String format, int pageStart, int pageEnd, int lineStart, int lineEnd, float[] embedding) {
        KnowledgeChunkEntity chunk = new KnowledgeChunkEntity();
        chunk.chunkId = id;
        chunk.chunkLevel = level;
        chunk.parentChunkId = parentId;
        chunk.documentId = documentId;
        chunk.documentTitle = documentId;
        chunk.documentVersion = "fixture-v1";
        chunk.documentType = "OWNER_MANUAL";
        chunk.language = "zh-CN";
        chunk.vehicleModel = "DEMO_MODEL";
        chunk.modelYear = "2026";
        chunk.region = "CN";
        chunk.softwareVersion = "DEMO_VERSION";
        chunk.configurationCode = "DEFAULT";
        chunk.chunkType = "PROCEDURE";
        chunk.headingPath = "维护 > 制动系统";
        chunk.chapter = "维护";
        chunk.section = "制动系统";
        chunk.content = "制动液检查的受控合成 Fixture 文本。";
        chunk.sourceFormat = format;
        chunk.pdfPageStart = pageStart;
        chunk.pdfPageEnd = pageEnd;
        chunk.printedPageStartLabel = pageStart == 0 ? "" : String.valueOf(pageStart);
        chunk.printedPageEndLabel = pageEnd == 0 ? "" : String.valueOf(pageEnd);
        chunk.htmlElementId = "STATIC_HTML".equals(format) ? "fixture-brake-fluid" : "";
        chunk.sourceLineStart = lineStart;
        chunk.sourceLineEnd = lineEnd;
        chunk.sectionOrdinal = 1;
        chunk.ordinal = "CHILD".equals(level) ? 2 : 1;
        chunk.tokenEstimate = 10;
        chunk.lexicalDocumentLength = 5;
        chunk.embedding = embedding;
        return chunk;
    }

    private static float[] vector(float seed) {
        float[] vector = new float[1024];
        for (int index = 0; index < vector.length; index++) {
            vector[index] = seed + index / 100_000f;
        }
        return vector;
    }

    private static Path configuredOutput() {
        String configured = System.getProperty("rag.fixture.output");
        return configured == null || configured.isBlank() ? null : Path.of(configured);
    }

    private static String schemaFingerprint() throws IOException {
        return SchemaFingerprint.sha256OfNormalizedUtf8(Files.readString(SCHEMA_ROOT.resolve("objectbox-models/default.json")));
    }

    private static Map<String, Object> manifest(FixtureSummary summary, Path dataFile) throws Exception {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("formatVersion", 1);
        manifest.put("bundleId", "synthetic-objectbox-v1");
        manifest.put("bundleVersion", "fixture-v1");
        manifest.put("knowledgeScopeId", "demo-model-2026-cn-demo-version-default");
        manifest.put("builderVersion", "rag-indexer-fixture");
        manifest.put("builtAtEpochMs", 0);
        manifest.put("schemaFingerprint", summary.schemaFingerprint());
        manifest.put("objectBoxVersion", "5.4.0");
        manifest.put("sourceLocatorSchemaVersion", 1);
        manifest.put("dataFile", Map.of("name", "data.mdb", "sizeBytes", Files.size(dataFile), "sha256", sha256(dataFile)));
        manifest.put("embedding", Map.of("provider", "DashScope", "model", "text-embedding-v4", "dimension", 1024, "distanceType", "COSINE", "templateVersion", 1));
        manifest.put("hnsw", Map.of("configFingerprint", hashText("fixture-hnsw-v1")));
        manifest.put("scope", Map.of("vehicleModel", "DEMO_MODEL", "modelYear", "2026", "region", "CN", "softwareVersion", "DEMO_VERSION", "configurationCode", "DEFAULT"));
        manifest.put("parsers", Map.of(
                "configHash", hashText("fixture-parsers-v1"),
                "supportedSourceFormats", List.of("PDF", "STATIC_HTML", "MARKDOWN"),
                "pdf", Map.of("fixture", true),
                "html", Map.of("mode", "STATIC_DOM_ONLY", "networkAccess", false, "scriptExecution", false),
                "markdown", Map.of("syntax", "COMMONMARK", "extensions", List.of("GFM_TABLE"))
        ));
        manifest.put("lexical", Map.of("analyzerVersion", 1, "languages", List.of("zh", "en"), "algorithm", "BM25", "tokenization", "CJK_BIGRAM_TRIGRAM_WITH_EXACT_LATIN"));
        manifest.put("chunking", Map.of("configVersion", 1, "configHash", hashText("fixture-chunking-v1")));
        manifest.put("corpus", Map.of(
                "corpusHash", hashText("fixture-corpus-v1"),
                "documentCount", summary.documentCount(),
                "sourceFormatCounts", Map.of("PDF", 1, "STATIC_HTML", 1, "MARKDOWN", 1),
                "parentChunkCount", summary.parentChunkCount(),
                "childChunkCount", summary.childChunkCount(),
                "lexicalTermCount", summary.lexicalTermCount()
        ));
        return manifest;
    }

    private static Map<String, Object> fixtureReport(FixtureSummary summary) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("fixture", true);
        report.put("publishable", false);
        report.put("source", "synthetic-controlled-data");
        report.put("generationCommand", "gradlew.bat generateObjectBoxFixture");
        report.put("schemaFingerprint", summary.schemaFingerprint());
        report.put("sourceFormats", List.of("PDF", "STATIC_HTML", "MARKDOWN"));
        report.put("documentCount", summary.documentCount());
        report.put("parentChunkCount", summary.parentChunkCount());
        report.put("childChunkCount", summary.childChunkCount());
        report.put("lexicalTermCount", summary.lexicalTermCount());
        return report;
    }

    private static void writeJson(Path file, Map<String, Object> content) throws IOException {
        JSON.writeValue(file.toFile(), content);
    }

    private static String sha256(Path file) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        StringBuilder result = new StringBuilder();
        for (byte value : digest) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    private static String hashText(String input) throws Exception {
        return "sha256:" + toHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static String toHex(byte[] digest) {
        StringBuilder result = new StringBuilder();
        for (byte value : digest) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    private record FixtureSummary(int documentCount, int parentChunkCount, int childChunkCount, int lexicalTermCount, String schemaFingerprint) {
    }
}
