package com.hirain.aiagent.rag.retrieval;

import com.hirain.aiagent.rag.cloud.QueryEmbeddingClient;
import com.hirain.aiagent.rag.cloud.RagCloudException;
import com.hirain.aiagent.rag.cloud.RerankCandidate;
import com.hirain.aiagent.rag.cloud.RerankClient;
import com.hirain.aiagent.rag.cloud.RerankItem;
import com.hirain.aiagent.rag.document.SourceLocatorEntityMapper;
import com.hirain.aiagent.rag.model.*;
import com.hirain.aiagent.rag.ranking.*;
import com.hirain.aiagent.rag.store.KnowledgeStoreGateway;
import com.hirain.aiagent.runtime.RequestDeadline;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 纯检索协调器：Child 参与 Dense/BM25、RRF 与 Rerank，最终只输出完整 Parent Evidence。
 * Embedding/Rerank 云端失败时沿用已有降级语义，不伪造 Rerank 置信度。
 */
public final class HybridRetrievalCoordinator {
    private final LexicalAnalyzer analyzer;
    private final ObjectBoxLexicalSearcher lexical;
    private final ObjectBoxDenseSearcher dense;
    private final QueryEmbeddingClient embedding;
    private final ReciprocalRankFusion rrf;
    private final RerankClient rerank;
    private final RerankCoordinator rerankCoordinator;
    private final SourceLocatorEntityMapper locator = new SourceLocatorEntityMapper();
    private final ParentCandidateAggregator parentAggregator = new ParentCandidateAggregator();
    private final ParentEvidenceAssembler parentAssembler = new ParentEvidenceAssembler();
    private final FusionCandidateDeduplicator candidateDeduplicator = new FusionCandidateDeduplicator();
    private final com.hirain.aiagent.rag.policy.ParentEvidenceBudgetPolicy parentBudget =
            new com.hirain.aiagent.rag.policy.ParentEvidenceBudgetPolicy(4, 5000);

    public HybridRetrievalCoordinator(LexicalAnalyzer analyzer, ObjectBoxLexicalSearcher lexical,
                                      ObjectBoxDenseSearcher dense, QueryEmbeddingClient embedding,
                                      ReciprocalRankFusion rrf) {
        this(analyzer, lexical, dense, embedding, rrf, null, null);
    }

    public HybridRetrievalCoordinator(LexicalAnalyzer analyzer, ObjectBoxLexicalSearcher lexical,
                                      ObjectBoxDenseSearcher dense, QueryEmbeddingClient embedding,
                                      ReciprocalRankFusion rrf, RerankClient rerank,
                                      RerankCoordinator rerankCoordinator) {
        this.analyzer = analyzer;
        this.lexical = lexical;
        this.dense = dense;
        this.embedding = embedding;
        this.rrf = rrf;
        this.rerank = rerank;
        this.rerankCoordinator = rerankCoordinator;
    }

    public Outcome retrieve(String requestId, String normalized, KnowledgeStoreGateway store,
                            VehicleProfile profile, RequestDeadline deadline) {
        List<RankedCandidate> lexicalHits = lexical.search(store, analyzer.analyze(normalized), profile, 20);
        List<DenseCandidate> denseHits = List.of();
        Map<String, Double> rerankScores = new HashMap<>();
        List<String> degraded = new ArrayList<>();
        RetrievalMode mode = RetrievalMode.HYBRID_FUSION_ONLY;
        try {
            denseHits = dense.search(store, embedding.embed(requestId, normalized, deadline), profile, 20);
        } catch (RagCloudException error) {
            if (lexicalHits.isEmpty()) return new Outcome(List.of(), null, List.of(error.reasonCode()),
                    new CandidateDeduplicationResult(List.of(), List.of()));
            mode = RetrievalMode.LEXICAL_ONLY;
            degraded.add(error.reasonCode());
        }

        List<String> lexicalIds = lexicalHits.stream().map(RankedCandidate::chunkId).collect(Collectors.toList());
        List<String> denseIds = denseHits.stream().map(value -> value.chunk().chunkId).collect(Collectors.toList());
        List<FusionCandidate> fused = rrf.fuse(denseIds, lexicalIds);
        Map<String, RankedCandidate> lexicalById = new HashMap<>();
        for (RankedCandidate item : lexicalHits) lexicalById.put(item.chunkId(), item);
        Map<String, DenseCandidate> denseById = new HashMap<>();
        for (DenseCandidate item : denseHits) denseById.put(item.chunk().chunkId, item);
        Map<String, com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity> chunks = new HashMap<>();
        for (var item : store.childChunksByIds(fused.stream().map(FusionCandidate::chunkId).collect(Collectors.toList()))) {
            chunks.put(item.chunkId, item);
        }
        // RRF 已按 Chunk ID 合并同一候选；此处再按正文和 Parent 占位做统一去重，避免同一 Parent 独占 Rerank 输入。
        CandidateDeduplicationResult deduplication = candidateDeduplicator.deduplicate(fused, chunks);
        fused = deduplication.candidates();

        // Rerank 输入只来自已通过 Scope/Metadata 过滤的 Child 候选。
        if (mode == RetrievalMode.HYBRID_FUSION_ONLY && rerank != null && rerankCoordinator != null && !fused.isEmpty()) {
            List<RerankCandidate> candidates = new ArrayList<>();
            for (FusionCandidate item : fused) {
                var chunk = chunks.get(item.chunkId());
                if (chunk != null) candidates.add(new RerankCandidate(chunk.headingPath, chunk.content));
            }
            if (candidates.size() == fused.size()) {
                try {
                    List<RerankItem> response = rerank.rerank(requestId, normalized, List.copyOf(candidates), deadline);
                    for (RerankItem item : response) {
                        if (item.index() >= 0 && item.index() < fused.size()) {
                            rerankScores.put(fused.get(item.index()).chunkId(), item.score());
                        }
                    }
                    fused = rerankCoordinator.apply(fused, response);
                    mode = RetrievalMode.HYBRID_RERANKED;
                } catch (RagCloudException error) {
                    degraded.add(error.reasonCode());
                }
            }
        }

        List<RetrievalEvidence> childEvidence = new ArrayList<>();
        for (int rank = 0; rank < fused.size(); rank++) {
            FusionCandidate fusion = fused.get(rank);
            var chunk = chunks.get(fusion.chunkId());
            if (chunk == null) continue;
            DenseCandidate denseCandidate = denseById.get(fusion.chunkId());
            RankedCandidate lexicalCandidate = lexicalById.get(fusion.chunkId());
            Double rerankScore = rerankScores.get(fusion.chunkId());
            childEvidence.add(new RetrievalEvidence(
                    "internal-" + chunk.chunkId, chunk.chunkId, chunk.parentChunkId, chunk.content,
                    chunk.documentId, chunk.documentTitle, chunk.documentVersion, chunk.chapter, chunk.section,
                    chunk.headingPath, locator.fromChunk(chunk), denseCandidate == null ? null : denseCandidate.distance(),
                    fusion.denseRank(), lexicalCandidate == null ? null : lexicalCandidate.score(), fusion.lexicalRank(),
                    fusion.fusionScore(), rank + 1, rerankScore, rerankScore == null ? null : rank + 1,
                    fusion.sources(), Applicability.EXACT, RetrievalConfidence.UNASSESSED, null));
        }

        List<RetrievalEvidence> parents = parentAssembler.assemble(store, parentAggregator.aggregate(childEvidence));
        return new Outcome(parentBudget.select(parents), mode, List.copyOf(degraded), deduplication);
    }

    public record Outcome(List<RetrievalEvidence> evidence, RetrievalMode mode, List<String> degradedReasons,
                          CandidateDeduplicationResult deduplication) {
        public Outcome(List<RetrievalEvidence> evidence, RetrievalMode mode, List<String> degradedReasons) {
            this(evidence, mode, degradedReasons, new CandidateDeduplicationResult(List.of(), List.of()));
        }
        public Outcome {
            evidence = List.copyOf(evidence == null ? List.of() : evidence);
            degradedReasons = List.copyOf(degradedReasons == null ? List.of() : degradedReasons);
            deduplication = deduplication == null ? new CandidateDeduplicationResult(List.of(), List.of()) : deduplication;
        }
    }
}
