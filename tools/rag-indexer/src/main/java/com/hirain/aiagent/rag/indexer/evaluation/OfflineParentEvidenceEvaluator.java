package com.hirain.aiagent.rag.indexer.evaluation;

import com.hirain.aiagent.rag.indexer.embedding.DocumentEmbeddingClient;
import com.hirain.aiagent.rag.indexer.embedding.EmbeddingException;
import com.hirain.aiagent.rag.indexer.store.ObjectBoxStoreFactory;
import com.hirain.aiagent.rag.indexer.util.Sha256;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;
import io.objectbox.BoxStore;
import java.nio.file.Path;
import java.util.*;

/**
 * Eval V2 离线适配器。底层沿用已经与 Android 对齐的 Child Dense/BM25/RRF/Rerank，
 * 评测输出再按 Child 的 parentChunkId 恢复 Parent，并以 acceptableEvidenceSets 判断最终覆盖。
 */
public final class OfflineParentEvidenceEvaluator {
    private final ObjectBoxStoreFactory storeFactory;
    private final DocumentEmbeddingClient embeddingClient;
    private final boolean rerankEnabled;
    private final OfflineHybridEvaluator.AblationMode ablationMode;
    private final OfflineNoEvidencePolicy noEvidencePolicy = new OfflineNoEvidencePolicy();

    public OfflineParentEvidenceEvaluator(DocumentEmbeddingClient embeddingClient, boolean rerankEnabled) {
        this(new ObjectBoxStoreFactory(), embeddingClient,
                rerankEnabled ? OfflineHybridEvaluator.AblationMode.RRF_RERANK : OfflineHybridEvaluator.AblationMode.RRF_NEAR_DEDUP);
    }

    public OfflineParentEvidenceEvaluator(DocumentEmbeddingClient embeddingClient, OfflineHybridEvaluator.AblationMode mode) {
        this(new ObjectBoxStoreFactory(), embeddingClient, mode);
    }

    OfflineParentEvidenceEvaluator(ObjectBoxStoreFactory storeFactory, DocumentEmbeddingClient embeddingClient,
                                   boolean rerankEnabled) {
        this(storeFactory, embeddingClient,
                rerankEnabled ? OfflineHybridEvaluator.AblationMode.RRF_RERANK : OfflineHybridEvaluator.AblationMode.RRF_NEAR_DEDUP);
    }

    OfflineParentEvidenceEvaluator(ObjectBoxStoreFactory storeFactory, DocumentEmbeddingClient embeddingClient,
                                   OfflineHybridEvaluator.AblationMode mode) {
        this.storeFactory = storeFactory;
        this.embeddingClient = embeddingClient;
        this.rerankEnabled = mode == OfflineHybridEvaluator.AblationMode.RRF_RERANK;
        this.ablationMode = mode == null ? OfflineHybridEvaluator.AblationMode.RRF_NEAR_DEDUP : mode;
    }

    public ParentEvidenceMetrics evaluate(Path bundle, RetrievalEvaluationDatasetV2 dataset) throws EmbeddingException {
        Map<String, KnowledgeChunkEntity> children;
        Map<String, KnowledgeChunkEntity> parents;
        Map<String, List<String>> childrenByParent = new HashMap<>();
        List<String> anyChild;
        try (BoxStore store = storeFactory.openReadOnlyExisting(bundle)) {
            children = new HashMap<>();
            parents = new HashMap<>();
            for (KnowledgeChunkEntity entity : store.boxFor(KnowledgeChunkEntity.class).getAll()) {
                if ("PARENT".equals(entity.chunkLevel)) {
                    parents.put(entity.chunkId, entity);
                } else if ("CHILD".equals(entity.chunkLevel)) {
                    children.put(entity.chunkId, entity);
                    childrenByParent.computeIfAbsent(entity.parentChunkId, ignored -> new ArrayList<>()).add(entity.chunkId);
                }
            }
            anyChild = children.keySet().stream().sorted().limit(1).toList();
        }

        List<RetrievalEvaluationDataset.Case> compatibilityCases = new ArrayList<>();
        for (RetrievalEvaluationDatasetV2.Case value : dataset.cases()) {
            Set<String> expectedChildren = new LinkedHashSet<>();
            for (EvidenceSetExpectation set : value.acceptableEvidenceSets()) {
                for (String parentId : set.requiredParentIds()) {
                    List<String> members = childrenByParent.get(parentId);
                    if (members == null || members.isEmpty()) throw new IllegalArgumentException("EVALUATION_V2_PARENT_UNKNOWN");
                    expectedChildren.addAll(members);
                }
            }
            // V1 评测器需要非空 expectedChunkIds 才能执行检索；NO_EVIDENCE 使用一个存在的哨兵，
            // 主指标仍完全依据 V2 的空 Evidence 集合判定，不把哨兵当作正确答案。
            if (expectedChildren.isEmpty()) expectedChildren.addAll(anyChild);
            if (expectedChildren.isEmpty()) throw new IllegalArgumentException("EVALUATION_V2_STORE_EMPTY");
            compatibilityCases.add(new RetrievalEvaluationDataset.Case(value.caseId(), value.query(), List.copyOf(expectedChildren)));
        }
        OfflineHybridEvaluator childEvaluator = new OfflineHybridEvaluator(storeFactory, embeddingClient, ablationMode);
        RetrievalMetrics childMetrics = childEvaluator.evaluate(bundle,
                new RetrievalEvaluationDataset(dataset.datasetVersion() + "-child-diagnostic", dataset.knowledgeScopeId(), compatibilityCases));
        Map<String, OfflineHybridEvaluator.HybridDiagnostic> diagnostics = new HashMap<>();
        for (OfflineHybridEvaluator.HybridDiagnostic value : childEvaluator.hybridDiagnostics()) {
            diagnostics.put(value.caseId(), value);
        }
        Map<String, OfflineHybridEvaluator.RerankDiagnostic> rerankDiagnostics = new HashMap<>();
        for (OfflineHybridEvaluator.RerankDiagnostic value : childEvaluator.rerankDiagnostics()) {
            rerankDiagnostics.put(value.caseId(), value);
        }

        List<ParentEvidenceMetrics.CaseResult> results = new ArrayList<>();
        double noEvidenceCorrect = 0D;
        double coverage1 = 0D, coverage2 = 0D, coverage3 = 0D, coverage4 = 0D, mrr = 0D;
        for (int index = 0; index < dataset.cases().size(); index++) {
            RetrievalEvaluationDatasetV2.Case value = dataset.cases().get(index);
            RetrievalMetrics.CaseResult child = childMetrics.cases().get(index);
            List<String> rankedParents = child.rankedChunkIds().stream()
                    .map(chunkId -> children.get(chunkId))
                    .filter(Objects::nonNull)
                    .map(entity -> entity.parentChunkId)
                    .filter(Objects::nonNull)
                    .distinct().toList();
            List<String> evidenceParents = selectParents(rankedParents, parents);
            int evidenceTokens = evidenceParents.stream().mapToInt(id -> parentTokens(parents.get(id))).sum();
            int firstCovered = firstCoveredRank(evidenceParents, value.acceptableEvidenceSets());
            boolean covered = value.answerability() == EvaluationAnswerability.ANSWERABLE && firstCovered > 0;
            if (value.answerability() == EvaluationAnswerability.NO_EVIDENCE) {
                List<KnowledgeChunkEntity> evidence = evidenceParents.stream().map(parents::get).filter(Objects::nonNull).toList();
                if (!noEvidencePolicy.hasSufficientEvidence(value.query(), evidence)) noEvidenceCorrect++;
            } else if (firstCovered > 0) {
                mrr += 1D / firstCovered;
                if (firstCovered <= 1) coverage1++;
                if (firstCovered <= 2) coverage2++;
                if (firstCovered <= 3) coverage3++;
                if (firstCovered <= 4) coverage4++;
            }
            List<String> matched = firstCovered > 0 ? matchedSet(evidenceParents, value.acceptableEvidenceSets(), firstCovered) : List.of();
            OfflineHybridEvaluator.HybridDiagnostic diagnostic = diagnostics.get(value.caseId());
            ScoreSummary scoreSummary = scoreSummary(value.caseId(), rankedParents, children, rerankDiagnostics);
            results.add(new ParentEvidenceMetrics.CaseResult(value.caseId(), child.querySha256(), value.answerability(), covered,
                    firstCovered, evidenceParents, rankedParents, child.rankedChunkIds().size(), evidenceParents.size(),
                    evidenceTokens, diagnostic == null ? 0 : diagnostic.exactDuplicateDropCount(),
                    diagnostic == null ? 0 : diagnostic.nearDuplicateDropCount(),
                    diagnostic == null ? 0 : diagnostic.parentOccupancyDropCount(), matched,
                    scoreSummary.bestScore(), scoreSummary.margin(), scoreSummary.rankingSource()));
        }
        int count = dataset.cases().size();
        long noEvidenceCount = dataset.cases().stream().filter(value -> value.answerability() == EvaluationAnswerability.NO_EVIDENCE).count();
        long answerableCount = count - noEvidenceCount;
        return new ParentEvidenceMetrics(count,
                answerableCount == 0 ? 0D : coverage1 / answerableCount,
                answerableCount == 0 ? 0D : coverage2 / answerableCount,
                answerableCount == 0 ? 0D : coverage3 / answerableCount,
                answerableCount == 0 ? 0D : coverage4 / answerableCount,
                answerableCount == 0 ? 0D : mrr / answerableCount,
                noEvidenceCount == 0 ? 0D : noEvidenceCorrect / noEvidenceCount,
                results);
    }

    private static int firstCoveredRank(List<String> rankedParents, List<EvidenceSetExpectation> sets) {
        for (int rank = 1; rank <= rankedParents.size(); rank++) {
            Set<String> top = new HashSet<>(rankedParents.subList(0, rank));
            for (EvidenceSetExpectation set : sets) if (top.containsAll(set.requiredParentIds())) return rank;
        }
        return 0;
    }

    /** 与 Android ParentEvidenceBudgetPolicy 对齐：完整 Parent 超预算时停止，不截断或跳过首个超限项。 */
    private static List<String> selectParents(List<String> rankedParents, Map<String, KnowledgeChunkEntity> parents) {
        List<String> output = new ArrayList<>();
        int used = 0;
        for (String id : rankedParents) {
            if (output.size() >= 4) break;
            KnowledgeChunkEntity parent = parents.get(id);
            if (parent == null) continue;
            int tokens = parentTokens(parent);
            if (tokens > 5000 || used + tokens > 5000) break;
            output.add(id);
            used += tokens;
        }
        return List.copyOf(output);
    }

    private static int parentTokens(KnowledgeChunkEntity parent) {
        if (parent == null) return 0;
        if (parent.tokenEstimate > 0) return parent.tokenEstimate;
        String content = parent.content == null ? "" : parent.content;
        return Math.max(1, (content.codePointCount(0, content.length()) + 3) / 4);
    }

    private static List<String> matchedSet(List<String> rankedParents, List<EvidenceSetExpectation> sets, int rank) {
        Set<String> top = new HashSet<>(rankedParents.subList(0, Math.min(rank, rankedParents.size())));
        for (EvidenceSetExpectation set : sets) if (top.containsAll(set.requiredParentIds())) return List.copyOf(set.requiredParentIds());
        return List.of();
    }

    private static ScoreSummary scoreSummary(String caseId, List<String> rankedParents,
                                             Map<String, KnowledgeChunkEntity> children,
                                             Map<String, OfflineHybridEvaluator.RerankDiagnostic> diagnostics) {
        OfflineHybridEvaluator.RerankDiagnostic diagnostic = diagnostics.get(caseId);
        if (diagnostic == null || rankedParents.isEmpty()) return new ScoreSummary(null, null, "RRF");
        Map<String, Double> childScores = new HashMap<>();
        for (OfflineHybridEvaluator.RerankResponse response : diagnostic.response()) {
            KnowledgeChunkEntity child = children.get(response.chunkId());
            if (child != null && child.parentChunkId != null) {
                childScores.merge(child.parentChunkId, response.relevanceScore(), Math::max);
            }
        }
        Double best = childScores.get(rankedParents.get(0));
        Double next = null;
        for (int index = 1; index < rankedParents.size(); index++) {
            Double value = childScores.get(rankedParents.get(index));
            if (value != null && (next == null || value > next)) next = value;
        }
        return new ScoreSummary(best, best == null || next == null ? null : best - next, "RERANK");
    }

    private record ScoreSummary(Double bestScore, Double margin, String rankingSource) { }
}
