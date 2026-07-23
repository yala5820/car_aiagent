package com.hirain.aiagent.rag.indexer.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hirain.aiagent.rag.indexer.RagIndexerMain;
import com.hirain.aiagent.rag.indexer.artifact.ArtifactPublisher;
import com.hirain.aiagent.rag.indexer.artifact.ArtifactOutputAlreadyExistsException;
import com.hirain.aiagent.rag.indexer.artifact.BundleLayout;
import com.hirain.aiagent.rag.indexer.artifact.ManifestBuilder;
import com.hirain.aiagent.rag.indexer.artifact.ManifestWriter;
import com.hirain.aiagent.rag.indexer.chunk.ChildChunk;
import com.hirain.aiagent.rag.indexer.config.ConfigLoader;
import com.hirain.aiagent.rag.indexer.config.RagBuildConfig;
import com.hirain.aiagent.rag.indexer.corpus.CorpusDefinition;
import com.hirain.aiagent.rag.indexer.corpus.CorpusLoader;
import com.hirain.aiagent.rag.indexer.corpus.CorpusResourceBudget;
import com.hirain.aiagent.rag.indexer.embedding.DashScopeDocumentEmbeddingClient;
import com.hirain.aiagent.rag.indexer.embedding.DocumentEmbeddingClient;
import com.hirain.aiagent.rag.indexer.embedding.EmbeddingBuildCoordinator;
import com.hirain.aiagent.rag.indexer.embedding.FileEmbeddingCache;
import com.hirain.aiagent.rag.indexer.lexical.CjkLatinLexicalAnalyzer;
import com.hirain.aiagent.rag.indexer.lexical.LexicalAnalyzerConfig;
import com.hirain.aiagent.rag.indexer.lexical.LexicalDocument;
import com.hirain.aiagent.rag.indexer.lexical.LexicalIndex;
import com.hirain.aiagent.rag.indexer.lexical.LexicalIndexBuilder;
import com.hirain.aiagent.rag.indexer.report.BuildReport;
import com.hirain.aiagent.rag.indexer.report.BuildReportWriter;
import com.hirain.aiagent.rag.indexer.report.ChunkBuildSummary;
import com.hirain.aiagent.rag.indexer.report.DocumentBuildReport;
import com.hirain.aiagent.rag.indexer.report.EmbeddingBuildSummary;
import com.hirain.aiagent.rag.indexer.report.ParserBuildSummary;
import com.hirain.aiagent.rag.indexer.report.StoreBuildSummary;
import com.hirain.aiagent.rag.indexer.store.ObjectBoxKnowledgeStoreWriter;
import com.hirain.aiagent.rag.indexer.store.StoreMetadataInput;
import com.hirain.aiagent.rag.indexer.store.StoreWriteModel;
import com.hirain.aiagent.rag.indexer.util.DeterministicJson;
import com.hirain.aiagent.rag.indexer.util.Sha256;
import com.hirain.aiagent.rag.store.KnowledgeStoreContract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * G404 的真实离线构建实现。它只生成文档向量，所有 Query Embedding 与 Android Runtime 行为均不在此处。
 * TEST_ONLY 配置保留已 Verify 的隐藏开发候选目录，但绝不执行正式 output 原子发布。
 */
public final class DevelopmentBuildPipeline {
    private final DocumentEmbeddingClient embeddingClient;

    public DevelopmentBuildPipeline() { this(new DashScopeDocumentEmbeddingClient()); }
    DevelopmentBuildPipeline(DocumentEmbeddingClient embeddingClient) { this.embeddingClient = embeddingClient; }

    public BuildResult build(BuildExecutionContext context) throws Exception {
        RagBuildConfig config = new ConfigLoader().load(context.arguments().config());
        CorpusDefinition corpus = new CorpusLoader().load(context.arguments().corpus());
        Path corpusRoot = context.arguments().corpus().getParent();
        if (corpusRoot == null) throw new IllegalArgumentException("Corpus 文件缺少父目录");
        Path output = context.arguments().output();
        if (Files.exists(output)) throw new ArtifactOutputAlreadyExistsException();
        Path run = new BuildWorkspace().create(context.arguments().workDirectory(), context.runId());
        Path staging = createStaging(output, context.runId());
        try {
            context.cancellationToken().throwIfCancelled();
            List<DocumentBuildState> states = new CorpusBuildInputPreparer().prepare(corpusRoot, corpus,
                    new CorpusResourceBudget(6, 50L * 1024L * 1024L), run.resolve("normalized-sources"));
            checkpoint(run, BuildPhase.VALIDATED, config);

            BuildComponentFactory components = new BuildComponentFactory();
            DocumentBuildStageRunner documentRunner = new DocumentBuildStageRunner(
                    components.createParsingPipeline(config), components.createChunker(config));
            List<DocumentBuildState> chunked = new ArrayList<>();
            for (DocumentBuildState state : states) chunked.add(documentRunner.parseAndChunk(state, context.cancellationToken()));
            checkpoint(run, BuildPhase.CHUNKED, config);

            StableIdStageRunner idRunner = new StableIdStageRunner();
            EmbeddingRequestStageBuilder requestBuilder = new EmbeddingRequestStageBuilder();
            Map<String, ChunkStableIds> idsByDocument = new LinkedHashMap<>();
            List<DocumentBuildState> embedded = new ArrayList<>();
            EmbeddingStageRunner embeddingRunner = new EmbeddingStageRunner(new EmbeddingBuildCoordinator(embeddingClient,
                    new FileEmbeddingCache(run.resolve("embedding-cache")), 8, 2));
            for (DocumentBuildState state : chunked) {
                ChunkStableIds ids = idRunner.assign(state); idsByDocument.put(state.corpusDocument().documentId(), ids);
                embedded.add(embeddingRunner.embed(state, requestBuilder.build(state, ids), context.cancellationToken()));
            }
            checkpoint(run, BuildPhase.EMBEDDED, config);

            LexicalIndex lexical = globalLexical(embedded, idsByDocument);
            StoreWriteModel model = new StoreWriteModelAssembler().assemble(embedded, idsByDocument, lexical,
                    metadataInput(context, config, corpus, lexical, embedded));
            new ObjectBoxKnowledgeStoreWriter().write(staging, model);
            checkpoint(run, BuildPhase.STORE_WRITTEN, config);

            Map<String, Object> hnsw = new LinkedHashMap<>(KnowledgeStoreContract.hnswManifestDetails());
            hnsw.put("configFingerprint", hnswFingerprint());
            new ManifestWriter().write(BundleLayout.manifest(staging), new ManifestBuilder().build(BundleLayout.data(staging), model,
                    scope(corpus), parserDetails(config), Map.copyOf(hnsw)));
            boolean approved = "APPROVED".equals(config.canonicalJson().path("qualityGate").path("state").asText());
            new BuildReportWriter().write(BundleLayout.report(staging), report(context, corpus, embedded, lexical, model, approved));
            new VerifyPipeline().verifyUsingBundledSchema(staging);
            checkpoint(run, BuildPhase.VERIFIED, config);

            if (approved) return new BuildResult(new ArtifactPublisher().publish(staging, output, true).outputDirectory(), true);
            return new BuildResult(new ArtifactPublisher().publishDevelopmentCandidate(staging, output).outputDirectory(), false);
        } catch (Exception error) {
            // 失败候选始终留在 staging/工作目录，禁止将部分 Bundle 移到目标 output。
            throw error;
        }
    }

    private static Path createStaging(Path output, String runId) throws IOException {
        Path parent = output.getParent(); if (parent == null) throw new IllegalArgumentException("输出路径缺少父目录");
        Files.createDirectories(parent); Path staging = parent.resolve("." + output.getFileName() + ".staging-" + runId);
        if (Files.exists(staging)) throw new IllegalArgumentException("staging 目录已存在");
        return Files.createDirectory(staging);
    }

    private static LexicalIndex globalLexical(List<DocumentBuildState> states, Map<String, ChunkStableIds> idsByDocument) {
        List<LexicalDocument> documents = new ArrayList<>();
        for (DocumentBuildState state : states) {
            ChunkStableIds ids = idsByDocument.get(state.corpusDocument().documentId());
            for (ChildChunk child : state.chunkResult().children()) documents.add(new LexicalDocument(ids.childIds().get(child), child.text()));
        }
        return new LexicalIndexBuilder(new CjkLatinLexicalAnalyzer(LexicalAnalyzerConfig.v1()), LexicalAnalyzerConfig.v1()).build(documents);
    }

    private static StoreMetadataInput metadataInput(BuildExecutionContext context, RagBuildConfig config, CorpusDefinition corpus,
                                                     LexicalIndex lexical, List<DocumentBuildState> states) throws Exception {
        long parents = states.stream().mapToLong(value -> value.chunkResult().parents().size()).sum();
        long children = states.stream().mapToLong(value -> value.chunkResult().children().size()).sum();
        Map<String, Long> formats = sourceFormatCounts(corpus);
        return new StoreMetadataInput(corpus.bundle().bundleId(), corpus.bundle().bundleVersion(), corpus.bundle().knowledgeScopeId(),
                config.fingerprint(), RagIndexerMain.VERSION, "5.4.0", hnswFingerprint(),
                fingerprint(config.canonicalJson().path("parser")), fingerprint(config.canonicalJson().path("chunking")),
                "sha256:" + Sha256.file(context.arguments().corpus()), Set.of("PDF", "STATIC_HTML", "MARKDOWN"), formats,
                states.size(), parents, children, lexical.postingsByTerm().size(), lexical.averageDocumentLength(), context.startedAt().toEpochMilli());
    }

    private static BuildReport report(BuildExecutionContext context, CorpusDefinition corpus, List<DocumentBuildState> states,
                                      LexicalIndex lexical, StoreWriteModel model, boolean approved) throws IOException {
        long parents = states.stream().mapToLong(value -> value.chunkResult().parents().size()).sum();
        long children = states.stream().mapToLong(value -> value.chunkResult().children().size()).sum();
        List<DocumentBuildReport> documents = states.stream().map(state -> new DocumentBuildReport(state.corpusDocument().documentId(),
                state.corpusDocument().relativePath(), state.corpusDocument().expectedSha256(), "PDF".equals(state.corpusDocument().sourceFormat()) ? "" : "UTF-8",
                state.corpusDocument().sourceFormat(), true, state.parseResult().blocks().size(), state.parseResult().tables().size(),
                state.parseResult().diagnostics().size(), state.parseResult().blocks().size() + state.parseResult().tables().size(), List.of())).toList();
        var file = new com.hirain.aiagent.rag.indexer.artifact.BundleFileHasher().hash(BundleLayout.data(context.arguments().output().getParent()
                .resolve("." + context.arguments().output().getFileName() + ".staging-" + context.runId())));
        return new BuildReport(context.runId(), approved, List.of(), List.of(), sourceFormatCounts(corpus),
                Map.of("documents", (long) states.size(), "parents", parents, "children", children, "terms", (long) lexical.postingsByTerm().size()),
                new ParserBuildSummary(sourceFormatCounts(corpus), sourceFormatCounts(corpus), Map.of(), Map.of("PDF", "v1", "STATIC_HTML", "v1", "MARKDOWN", "v1")),
                new ChunkBuildSummary(parents, children, 0, 0, 0), new EmbeddingBuildSummary(children, 0, children, 0, 0),
                new StoreBuildSummary(states.size(), parents, children, lexical.postingsByTerm().size(), file.sizeBytes(), file.sha256(), 0), documents);
    }

    private static Map<String, Long> sourceFormatCounts(CorpusDefinition corpus) {
        Map<String, Long> result = new java.util.TreeMap<>();
        corpus.documents().forEach(document -> result.merge(document.sourceFormat(), 1L, Long::sum)); return Map.copyOf(result);
    }
    private static Map<String, String> scope(CorpusDefinition corpus) { var value = corpus.bundle().scope(); return Map.of("vehicleModel", value.vehicleModel(), "modelYear", value.modelYear(), "region", value.region(), "softwareVersion", value.softwareVersion(), "configurationCode", value.configurationCode()); }
    private static Map<String, Object> parserDetails(RagBuildConfig config) { ObjectMapper mapper = new ObjectMapper(); return Map.of("pdf", mapper.convertValue(config.canonicalJson().path("parser").path("pdf"), Map.class), "html", mapper.convertValue(config.canonicalJson().path("parser").path("html"), Map.class), "markdown", mapper.convertValue(config.canonicalJson().path("parser").path("markdown"), Map.class)); }
    private static String fingerprint(com.fasterxml.jackson.databind.JsonNode value) { return DeterministicJson.sha256(value); }
    private static String hnswFingerprint() { return DeterministicJson.sha256(new ObjectMapper().valueToTree(KnowledgeStoreContract.hnswManifestDetails())); }
    private static void checkpoint(Path run, BuildPhase phase, RagBuildConfig config) throws IOException { new BuildCheckpointStore().save(run, new BuildCheckpoint(phase, config.fingerprint(), fingerprint(config.canonicalJson().path("parser")), fingerprint(config.canonicalJson().path("chunking")), "embedding-template-v1")); }

    public record BuildResult(Path bundleDirectory, boolean publishable) { }
}
