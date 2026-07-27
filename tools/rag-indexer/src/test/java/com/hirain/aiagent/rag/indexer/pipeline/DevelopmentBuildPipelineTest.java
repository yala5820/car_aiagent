package com.hirain.aiagent.rag.indexer.pipeline;

import com.hirain.aiagent.rag.indexer.cli.CliArguments;
import com.hirain.aiagent.rag.indexer.artifact.ArtifactOutputAlreadyExistsException;
import com.hirain.aiagent.rag.indexer.corpus.KnowledgeScopeDefinition;
import com.hirain.aiagent.rag.indexer.corpus.KnowledgeScopeIdGenerator;
import com.hirain.aiagent.rag.indexer.embedding.EmbeddingBatchResult;
import com.hirain.aiagent.rag.indexer.util.Sha256;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 以固定向量替代网络调用，验证 TEST_ONLY 仍可形成可供 Android 开发联调的完整候选 Bundle。 */
final class DevelopmentBuildPipelineTest {
    @TempDir Path root;

    @Test
    void shouldBuildAndVerifyTestOnlyDevelopmentBundleWithoutPublishingOutput() throws Exception {
        String fixtureOutput = System.getProperty("rag.development.bundle.output");
        Path corpusRoot = Files.createDirectories(root.resolve("corpus"));
        Path source = corpusRoot.resolve("manual.md");
        Files.writeString(source, "# 空调\n\n请使用空调控制功能调节温度。\n");
        KnowledgeScopeDefinition scope = new KnowledgeScopeDefinition("M1", "2026", "CN", "1.0", "BASE");
        String scopeId = KnowledgeScopeIdGenerator.generate(scope);
        Path corpus = corpusRoot.resolve("corpus.json");
        Files.writeString(corpus, "{\"schemaVersion\":1,\"bundleId\":\"dev-fixture\",\"bundleVersion\":\"0.0.1\",\"knowledgeScopeId\":\"" + scopeId + "\",\"scope\":{\"vehicleModel\":\"M1\",\"modelYear\":\"2026\",\"region\":\"CN\",\"softwareVersion\":\"1.0\",\"configurationCode\":\"BASE\"},\"documents\":[{\"documentId\":\"manual-1\",\"title\":\"车辆手册\",\"documentType\":\"manual\",\"documentVersion\":\"1.0\",\"language\":\"zh\",\"sourceFormat\":\"MARKDOWN\",\"relativePath\":\"manual.md\",\"expectedSha256\":\"" + Sha256.file(source) + "\",\"applicability\":{\"vehicleModel\":\"M1\",\"modelYear\":\"2026\",\"region\":\"CN\",\"softwareVersion\":\"1.0\",\"configurationCode\":\"BASE\"}}]}");
        Path config = root.resolve("rag-build.json");
        Files.writeString(config, "{\"schemaVersion\":1,\"parser\":{\"pdf\":{\"tableStrategy\":\"AUTO\",\"tableStrategyOverrides\":[]},\"html\":{\"mode\":\"STATIC_DOM_ONLY\",\"networkAccess\":false,\"scriptExecution\":false,\"contentRootSelector\":\"\",\"excludeSelectors\":[]},\"markdown\":{\"syntax\":\"COMMONMARK\",\"extensions\":[\"GFM_TABLE\"]},\"limits\":{}},\"chunking\":{},\"embedding\":{\"provider\":\"DashScope\",\"model\":\"text-embedding-v4\",\"dimension\":1024,\"templateVersion\":2},\"objectBox\":{},\"lexical\":{\"algorithm\":\"BM25\",\"analyzerVersion\":2},\"qualityGate\":{\"state\":\"TEST_ONLY\"}}");
        Path output = fixtureOutput == null ? root.resolve("published-output") : Path.of(fixtureOutput);
        BuildExecutionContext context = new BuildExecutionContext("development-build", Instant.ofEpochMilli(1_700_000_000_000L),
                new CliArguments("build", corpus, config, output, root.resolve("work"), null, null, null), new BuildCancellationToken());

        DevelopmentBuildPipeline.BuildResult result = new DevelopmentBuildPipeline(requests -> {
            List<float[]> vectors = new ArrayList<>();
            for (int index = 0; index < requests.size(); index++) { float[] vector = new float[1024]; vector[0] = 1F; vectors.add(vector); }
            return new EmbeddingBatchResult(vectors);
        }).build(context);

        assertFalse(result.publishable());
        assertTrue(Files.isDirectory(result.bundleDirectory()));
        assertTrue(Files.isRegularFile(result.bundleDirectory().resolve("data.mdb")));
        assertTrue(Files.isRegularFile(result.bundleDirectory().resolve("manifest.json")));
        assertTrue(Files.isRegularFile(result.bundleDirectory().resolve("build-report.json")));
        assertTrue(Files.isDirectory(output));
        assertTrue(output.equals(result.bundleDirectory()));
        new VerifyPipeline().verifyUsingBundledSchema(output);
        assertEquals(Set.of("data.mdb", "manifest.json", "build-report.json"), files(result.bundleDirectory()));

        BuildExecutionContext duplicateOutput = new BuildExecutionContext("duplicate-output", Instant.ofEpochMilli(1_700_000_000_001L),
                new CliArguments("build", corpus, config, output, root.resolve("work"), null, null, null), new BuildCancellationToken());
        assertThrows(ArtifactOutputAlreadyExistsException.class,
                () -> new DevelopmentBuildPipeline(requests -> new EmbeddingBatchResult(List.of())).build(duplicateOutput));

        // 生成任务只需留下通过 Verify 的 TEST_ONLY 交接候选；后续破坏/正式发布断言不应污染它。
        if (fixtureOutput != null) return;

        Files.writeString(result.bundleDirectory().resolve("data.mdb"), "tampered");
        assertThrows(IllegalArgumentException.class,
                () -> new VerifyPipeline().verifyUsingBundledSchema(result.bundleDirectory()));

        Files.writeString(config, Files.readString(config).replace("TEST_ONLY", "APPROVED"));
        Path approvedOutput = root.resolve("approved-output");
        BuildExecutionContext approvedContext = new BuildExecutionContext("approved-build", Instant.ofEpochMilli(1_700_000_000_001L),
                new CliArguments("build", corpus, config, approvedOutput, root.resolve("work"), null, null, null), new BuildCancellationToken());
        DevelopmentBuildPipeline.BuildResult approved = new DevelopmentBuildPipeline(requests -> {
            List<float[]> vectors = new ArrayList<>();
            for (int index = 0; index < requests.size(); index++) { float[] vector = new float[1024]; vector[0] = 1F; vectors.add(vector); }
            return new EmbeddingBatchResult(vectors);
        }).build(approvedContext);
        assertTrue(approved.publishable());
        assertTrue(Files.isDirectory(approvedOutput));
        assertTrue(approvedOutput.equals(approved.bundleDirectory()));
        new VerifyPipeline().verifyUsingBundledSchema(approvedOutput);
        assertEquals(Set.of("data.mdb", "manifest.json", "build-report.json"), files(approvedOutput));
    }

    private static Set<String> files(Path directory) throws Exception {
        try (var paths = Files.list(directory)) {
            return paths.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet());
        }
    }
}
