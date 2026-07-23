package com.hirain.aiagent.rag.indexer.corpus;

import com.hirain.aiagent.rag.indexer.cli.CliCommandException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** G102 Corpus JSON 的核心正反例：协议拼写错误与跨 Scope 声明必须在读取阶段失败。 */
final class CorpusLoaderTest {
    @TempDir Path temporaryDirectory;

    @Test
    void shouldLoadGoldenScopeAndAllowReviewedWildcard() throws Exception {
        Path input = write(validJson("*", "doc-1"));
        CorpusDefinition corpus = new CorpusLoader().load(input);
        assertEquals("demo-model-2026-cn-demo-version-default", corpus.bundle().knowledgeScopeId());
        assertEquals(1, corpus.documents().size());
    }

    @Test
    void shouldRejectUnknownFields() throws Exception {
        CliCommandException error = assertThrows(CliCommandException.class,
                () -> new CorpusLoader().load(write(validJson("*", "doc-1").replace("\"documents\":[", "\"typo\":true,\"documents\":["))));
        assertEquals("CORPUS_JSON_UNKNOWN_FIELD", error.reasonCode());
    }

    @Test
    void shouldRejectConcreteDocumentScopeOutsideBundle() throws Exception {
        CliCommandException error = assertThrows(CliCommandException.class,
                () -> new CorpusLoader().load(write(validJson("US", "doc-1"))));
        assertEquals("CORPUS_DOCUMENT_SCOPE_CONFLICT", error.reasonCode());
    }

    @Test
    void shouldRejectDuplicateDocumentId() throws Exception {
        String json = validJson("*", "doc-1");
        final String duplicateIdJson = json.replace("]\n}", "," + document("*", "doc-1") + "]\n}");
        CliCommandException error = assertThrows(CliCommandException.class, () -> new CorpusLoader().load(write(duplicateIdJson)));
        assertEquals("CORPUS_DOCUMENT_ID_INVALID", error.reasonCode());
    }

    private Path write(String json) throws Exception { Path file=temporaryDirectory.resolve("corpus.json"); Files.writeString(file, json); return file; }
    private static String validJson(String region, String id) { return "{" +
            "\"schemaVersion\":1,\"bundleId\":\"demo\",\"bundleVersion\":\"v1\",\"knowledgeScopeId\":\"demo-model-2026-cn-demo-version-default\",\n" +
            "\"scope\":{\"vehicleModel\":\"DEMO_MODEL\",\"modelYear\":\"2026\",\"region\":\"CN\",\"softwareVersion\":\"DEMO_VERSION\",\"configurationCode\":\"DEFAULT\"},\n" +
            "\"documents\":[" + document(region, id) + "]\n}"; }
    private static String document(String region, String id) { return "{\"documentId\":\"" + id + "\",\"title\":\"t\",\"documentType\":\"OWNER_MANUAL\",\"documentVersion\":\"v1\",\"language\":\"zh-CN\",\"sourceFormat\":\"MARKDOWN\",\"relativePath\":\"a.md\",\"expectedSha256\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"applicability\":{\"vehicleModel\":\"*\",\"modelYear\":\"*\",\"region\":\"" + region + "\",\"softwareVersion\":\"*\",\"configurationCode\":\"*\"}}"; }
}
