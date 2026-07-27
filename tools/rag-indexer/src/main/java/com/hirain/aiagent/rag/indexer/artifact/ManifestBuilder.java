package com.hirain.aiagent.rag.indexer.artifact;

import com.hirain.aiagent.rag.indexer.store.StoreWriteModel;
import com.hirain.aiagent.rag.indexer.config.ChunkingConfig;
import com.hirain.aiagent.rag.store.KnowledgeStoreContract;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 从已关闭 Store 的 Metadata 与锁定构建输入生成 Manifest，禁止从路径或正文推断协议字段。 */
public final class ManifestBuilder {
    public KnowledgeBundleManifest build(Path dataFile, StoreWriteModel model, Map<String, String> scope,
                                        Map<String, Object> parserDetails, Map<String, Object> hnswDetails) throws java.io.IOException {
        return build(dataFile, model, scope, parserDetails, hnswDetails, ChunkingConfig.v1Default());
    }
    /** V2 构建必须将完整分块参数写入 Manifest，不能只留一个不可解释 Hash。 */
    public KnowledgeBundleManifest build(Path dataFile, StoreWriteModel model, Map<String, String> scope,
                                        Map<String, Object> parserDetails, Map<String, Object> hnswDetails,
                                        ChunkingConfig chunkingConfig) throws java.io.IOException {
        var metadata = model.metadata(); var hash = new BundleFileHasher().hash(dataFile);
        Map<String, Object> embedding = Map.of("provider", metadata.embeddingProvider, "model", metadata.embeddingModel,
                "dimension", metadata.embeddingDimension, "distanceType", metadata.distanceType, "templateVersion", metadata.embeddingTemplateVersion);
        Map<String, Object> parsers = new LinkedHashMap<>(); parsers.put("configHash", metadata.parserConfigHash);
        parsers.put("supportedSourceFormats", List.of("PDF", "STATIC_HTML", "MARKDOWN")); parsers.putAll(parserDetails);
        Map<String, Object> lexical = Map.of("analyzerVersion", metadata.lexicalAnalyzerVersion, "languages", List.of("zh", "en"),
                "algorithm", "BM25", "tokenization", "CJK_BIGRAM_TRIGRAM_WITH_EXACT_LATIN");
        Map<String, Object> chunking;
        if (chunkingConfig.configVersion() == 1) chunking = Map.of("configVersion", 1, "configHash", metadata.chunkingConfigHash);
        else chunking = Map.ofEntries(Map.entry("configVersion", 2), Map.entry("configHash", metadata.chunkingConfigHash), Map.entry("tokenEstimatorVersion", chunkingConfig.tokenEstimatorVersion()),
                Map.entry("parentIdealMinTokens", chunkingConfig.parentIdealMinTokens()), Map.entry("parentSoftMaxTokens", chunkingConfig.parentSoftMaxTokens()), Map.entry("parentHardMaxTokens", chunkingConfig.parentHardMaxTokens()),
                Map.entry("childIdealMinTokens", chunkingConfig.childIdealMinTokens()), Map.entry("childTargetTokens", chunkingConfig.childTargetTokens()), Map.entry("childSoftMaxTokens", chunkingConfig.childSoftMaxTokens()), Map.entry("childHardMaxTokens", chunkingConfig.childHardMaxTokens()),
                Map.entry("fallbackOverlapMinRatio", chunkingConfig.fallbackOverlapMinRatio()), Map.entry("fallbackOverlapMaxRatio", chunkingConfig.fallbackOverlapMaxRatio()), Map.entry("tableRowsPerChild", chunkingConfig.tableRowsPerChild()), Map.entry("semanticStrategyVersion", chunkingConfig.semanticStrategyVersion()),
                Map.entry("paragraphReconstructionVersion", chunkingConfig.paragraphReconstructionVersion()), Map.entry("pdfColumnLayoutVersion", chunkingConfig.pdfColumnLayoutVersion()), Map.entry("paragraphCosineThreshold", chunkingConfig.paragraphCosineThreshold()), Map.entry("childMergeMaxTokens", chunkingConfig.childMergeMaxTokens()), Map.entry("parentSplitOverlapRatio", chunkingConfig.parentSplitOverlapRatio()), Map.entry("childForceMergeMaxTokens", chunkingConfig.childForceMergeMaxTokens()), Map.entry("childDirectMergeMaxTokens", chunkingConfig.childDirectMergeMaxTokens()), Map.entry("pdfTocReferenceVersion", chunkingConfig.pdfTocReferenceVersion()));
        Map<String, Object> corpus = new LinkedHashMap<>(); corpus.put("corpusHash", metadata.corpusHash); corpus.put("documentCount", metadata.documentCount);
        corpus.put("sourceFormatCounts", new com.fasterxml.jackson.databind.ObjectMapper().readValue(metadata.sourceFormatCounts, Map.class));
        corpus.put("parentChunkCount", metadata.parentChunkCount); corpus.put("childChunkCount", metadata.childChunkCount); corpus.put("lexicalTermCount", metadata.lexicalTermCount);
        KnowledgeBundleManifest manifest = new KnowledgeBundleManifest(KnowledgeStoreContract.FORMAT_VERSION, metadata.bundleId, metadata.bundleVersion,
                metadata.knowledgeScopeId, metadata.builderVersion, metadata.builtAtEpochMs, metadata.objectBoxVersion, metadata.schemaFingerprint,
                KnowledgeStoreContract.SOURCE_LOCATOR_SCHEMA_VERSION, hash, embedding, hnswDetails, Map.copyOf(scope), parsers, lexical, chunking, corpus);
        new ManifestValidator().validate(manifest); return manifest;
    }
}
