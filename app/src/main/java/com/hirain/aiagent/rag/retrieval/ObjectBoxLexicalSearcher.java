package com.hirain.aiagent.rag.retrieval;

import com.hirain.aiagent.rag.config.RagRetrievalConfig;
import com.hirain.aiagent.rag.model.VehicleProfile;
import com.hirain.aiagent.rag.store.KnowledgeStoreGateway;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;
import com.hirain.aiagent.rag.store.entity.LexicalTermEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 通过离线生成的 TITLE/BODY 独立 posting 计算 BM25；不让 Parent 参与召回。 */
public final class ObjectBoxLexicalSearcher {
    private final MetadataEligibilityPolicy eligibility;
    private final Bm25Searcher bm25;
    private final StoreScopeEligibilityPolicy scopeEligibility;
    public ObjectBoxLexicalSearcher(MetadataEligibilityPolicy eligibility, RagRetrievalConfig config) { this(eligibility, config, new StoreScopeEligibilityPolicy()); }
    public ObjectBoxLexicalSearcher(MetadataEligibilityPolicy eligibility, RagRetrievalConfig config, StoreScopeEligibilityPolicy scopeEligibility) { this.eligibility = eligibility; this.bm25 = new Bm25Searcher(config); this.scopeEligibility = scopeEligibility; }
    public List<RankedCandidate> search(KnowledgeStoreGateway gateway, List<String> queryTerms, VehicleProfile profile, int limit) {
        if (gateway == null || queryTerms == null || queryTerms.isEmpty() || limit < 1 || !scopeEligibility.validate(gateway, profile).eligible()) return List.of();
        Set<String> rawTerms = new HashSet<>(queryTerms);
        Set<String> fieldKeys = new HashSet<>();
        rawTerms.forEach(term -> { fieldKeys.add("TITLE:" + term); fieldKeys.add("BODY:" + term); });
        List<LexicalTermEntity> terms = gateway.lexicalTermsByTerms(List.copyOf(fieldKeys));
        Set<Long> ids = new HashSet<>(); for (LexicalTermEntity term : terms) for (long id : term.chunkEntityIds) ids.add(id);
        Map<Long, KnowledgeChunkEntity> children = new HashMap<>(); for (KnowledgeChunkEntity child : gateway.childChunksByEntityIds(new ArrayList<>(ids))) if (eligibility.evaluate(child, profile).eligible()) children.put(child.id, child);
        Map<String, LexicalTermEntity> indexed = new HashMap<>(); for (LexicalTermEntity term : terms) indexed.put(term.term, term);
        Map<String, Double> scores = new HashMap<>(); long corpusSize = gateway.metadata().childChunkCount;
        for (String queryTerm : rawTerms) {
            scoreField(indexed.get("TITLE:" + queryTerm), "TITLE", children, scores, corpusSize,
                    gateway.metadata().averageLexicalTitleLength, gateway.metadata().bm25TitleWeight);
            scoreField(indexed.get("BODY:" + queryTerm), "BODY", children, scores, corpusSize,
                    gateway.metadata().averageLexicalBodyLength, gateway.metadata().bm25BodyWeight);
        }
        List<RankedCandidate> output = new ArrayList<>(); for (Map.Entry<String, Double> entry : scores.entrySet()) output.add(new RankedCandidate(entry.getKey(), entry.getValue())); java.util.Collections.sort(output);
        return List.copyOf(output.subList(0, Math.min(limit, output.size())));
    }

    private void scoreField(LexicalTermEntity term, String field, Map<Long, KnowledgeChunkEntity> children,
                            Map<String, Double> scores, long corpusSize, double averageLength, double weight) {
        if (term == null) return;
        for (int index = 0; index < term.chunkEntityIds.length; index++) {
            KnowledgeChunkEntity child = children.get(term.chunkEntityIds[index]);
            if (child == null) continue;
            int length = "TITLE".equals(field) ? child.lexicalTitleDocumentLength : child.lexicalBodyDocumentLength;
            if (length == 0 && "BODY".equals(field)) length = child.lexicalDocumentLength;
            scores.merge(child.chunkId, weight * bm25.score(term.termFrequencies[index], term.documentFrequency,
                    (int) corpusSize, length, averageLength), Double::sum);
        }
    }
}
