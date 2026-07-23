package com.hirain.aiagent.rag.indexer.evaluation;

import com.hirain.aiagent.rag.indexer.embedding.DocumentEmbeddingClient;
import com.hirain.aiagent.rag.indexer.embedding.EmbeddingException;
import com.hirain.aiagent.rag.indexer.embedding.EmbeddingRequest;
import com.hirain.aiagent.rag.indexer.store.ObjectBoxStoreFactory;
import com.hirain.aiagent.rag.indexer.util.Sha256;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity_;
import com.hirain.aiagent.rag.store.entity.KnowledgeStoreMetadataEntity;
import io.objectbox.BoxStore;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 只读 Dense 评测器：评测 Query 使用与 Android 一致的 NFKC/空白折叠/ROOT 小写规则，
 * Store 只执行向量查询，任何写入、重建或发布状态变更均不属于该类职责。
 */
public final class OfflineDenseEvaluator {
    private static final int QUERY_MAX_CODE_POINTS = 512;
    private static final int MAX_K = 5;
    private final ObjectBoxStoreFactory storeFactory;
    private final DocumentEmbeddingClient embeddingClient;

    public OfflineDenseEvaluator(DocumentEmbeddingClient embeddingClient) { this(new ObjectBoxStoreFactory(), embeddingClient); }
    OfflineDenseEvaluator(ObjectBoxStoreFactory storeFactory, DocumentEmbeddingClient embeddingClient) { this.storeFactory = storeFactory; this.embeddingClient = embeddingClient; }

    public RetrievalMetrics evaluate(Path bundle, RetrievalEvaluationDataset dataset) throws EmbeddingException {
        try (BoxStore store = storeFactory.openReadOnlyExisting(bundle)) {
            KnowledgeStoreMetadataEntity metadata = requireMetadata(store);
            if (!dataset.knowledgeScopeId().equals(metadata.knowledgeScopeId)) throw new IllegalArgumentException("EVALUATION_SCOPE_MISMATCH");
            Set<String> childIds = new HashSet<>();
            for (KnowledgeChunkEntity child : store.boxFor(KnowledgeChunkEntity.class).getAll()) if ("CHILD".equals(child.chunkLevel)) childIds.add(child.chunkId);
            for (RetrievalEvaluationDataset.Case value : dataset.cases()) if (!childIds.containsAll(value.expectedChunkIds())) throw new IllegalArgumentException("EVALUATION_EXPECTED_CHUNK_UNKNOWN");
            List<RetrievalMetrics.CaseResult> cases = new ArrayList<>();
            for (RetrievalEvaluationDataset.Case value : dataset.cases()) cases.add(evaluateCase(store, value));
            return metrics(cases);
        }
    }

    private RetrievalMetrics.CaseResult evaluateCase(BoxStore store, RetrievalEvaluationDataset.Case value) throws EmbeddingException {
        String query = normalize(value.query());
        float[] vector = embeddingClient.embed(List.of(new EmbeddingRequest(0, value.caseId(), query))).vectors().get(0);
        if (vector == null || vector.length != 1024) throw new IllegalArgumentException("EVALUATION_QUERY_VECTOR_INVALID");
        List<KnowledgeChunkEntity> candidates = store.boxFor(KnowledgeChunkEntity.class).query(KnowledgeChunkEntity_.embedding.nearestNeighbors(vector, MAX_K)).build().find();
        candidates = candidates.stream().filter(candidate -> "CHILD".equals(candidate.chunkLevel))
                .sorted(Comparator.comparingDouble((KnowledgeChunkEntity candidate) -> cosine(vector, candidate.embedding)).reversed().thenComparing(candidate -> candidate.chunkId)).toList();
        List<String> ranked = candidates.stream().map(candidate -> candidate.chunkId).toList();
        int rank = 0; for (int index = 0; index < ranked.size(); index++) if (value.expectedChunkIds().contains(ranked.get(index))) { rank = index + 1; break; }
        return new RetrievalMetrics.CaseResult(value.caseId(), Sha256.ofUtf8(query), rank, ranked);
    }

    private static KnowledgeStoreMetadataEntity requireMetadata(BoxStore store) {
        List<KnowledgeStoreMetadataEntity> values = store.boxFor(KnowledgeStoreMetadataEntity.class).getAll();
        if (values.size() != 1) throw new IllegalArgumentException("EVALUATION_STORE_METADATA_INVALID"); return values.get(0);
    }
    private static String normalize(String value) {
        String result = Normalizer.normalize(value, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (result.isEmpty() || result.codePointCount(0, result.length()) > QUERY_MAX_CODE_POINTS) throw new IllegalArgumentException("EVALUATION_QUERY_INVALID"); return result;
    }
    private static double cosine(float[] left, float[] right) {
        if (right == null || right.length != left.length) return -1d;
        double dot = 0d, leftNorm = 0d, rightNorm = 0d; for (int index = 0; index < left.length; index++) { dot += (double) left[index] * right[index]; leftNorm += (double) left[index] * left[index]; rightNorm += (double) right[index] * right[index]; }
        return leftNorm == 0d || rightNorm == 0d ? -1d : dot / Math.sqrt(leftNorm * rightNorm);
    }
    private static RetrievalMetrics metrics(List<RetrievalMetrics.CaseResult> cases) {
        double at1 = 0d, at3 = 0d, at5 = 0d, mrr = 0d;
        for (RetrievalMetrics.CaseResult value : cases) { if (value.firstRelevantRank() > 0) { mrr += 1d / value.firstRelevantRank(); if (value.firstRelevantRank() <= 1) at1++; if (value.firstRelevantRank() <= 3) at3++; if (value.firstRelevantRank() <= 5) at5++; } }
        int size = cases.size(); return new RetrievalMetrics(size, at1 / size, at3 / size, at5 / size, mrr / size, cases);
    }
}
