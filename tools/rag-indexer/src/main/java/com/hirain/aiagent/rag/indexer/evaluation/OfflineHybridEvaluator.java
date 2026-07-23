package com.hirain.aiagent.rag.indexer.evaluation;

import com.hirain.aiagent.rag.indexer.embedding.DocumentEmbeddingClient;
import com.hirain.aiagent.rag.indexer.embedding.EmbeddingException;
import com.hirain.aiagent.rag.indexer.embedding.EmbeddingRequest;
import com.hirain.aiagent.rag.indexer.embedding.DashScopeApiKeyProvider;
import com.hirain.aiagent.rag.indexer.lexical.CjkLatinLexicalAnalyzer;
import com.hirain.aiagent.rag.indexer.lexical.LexicalAnalyzerConfig;
import com.hirain.aiagent.rag.indexer.store.ObjectBoxStoreFactory;
import com.hirain.aiagent.rag.indexer.util.Sha256;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity_;
import com.hirain.aiagent.rag.store.entity.KnowledgeStoreMetadataEntity;
import com.hirain.aiagent.rag.store.entity.LexicalTermEntity;
import io.objectbox.BoxStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 与 Android V1 运行时对齐的只读 Hybrid 评测器。
 *
 * <p>固定执行 Dense Top20、BM25 Top20、RRF(k=60) 与 Top5 指标计算；不调用 Rerank，
 * 因为 Rerank 结果依赖运行时云服务策略，不能被伪装为本地确定性基线。</p>
 */
public final class OfflineHybridEvaluator {
    private static final int QUERY_MAX_CODE_POINTS = 512;
    private static final int DENSE_TOP_K = 20;
    private static final int LEXICAL_TOP_K = 20;
    private static final int REPORT_TOP_K = 5;
    private static final int RRF_K = 60;
    private static final int RERANK_TOP_K = 20;
    private static final int RERANK_MAX_CHARACTERS = 12_000;
    private static final double BM25_K1 = 1.2D;
    private static final double BM25_B = 0.75D;

    private final ObjectBoxStoreFactory storeFactory;
    private final DocumentEmbeddingClient embeddingClient;
    private final CjkLatinLexicalAnalyzer analyzer = new CjkLatinLexicalAnalyzer(LexicalAnalyzerConfig.v1());
    private final boolean rerankEnabled;
    private final List<RerankDiagnostic> rerankDiagnostics = new ArrayList<>();

    public OfflineHybridEvaluator(DocumentEmbeddingClient embeddingClient) {
        this(new ObjectBoxStoreFactory(), embeddingClient, false);
    }

    public OfflineHybridEvaluator(DocumentEmbeddingClient embeddingClient, boolean rerankEnabled) { this(new ObjectBoxStoreFactory(), embeddingClient, rerankEnabled); }

    OfflineHybridEvaluator(ObjectBoxStoreFactory storeFactory, DocumentEmbeddingClient embeddingClient) { this(storeFactory, embeddingClient, false); }
    OfflineHybridEvaluator(ObjectBoxStoreFactory storeFactory, DocumentEmbeddingClient embeddingClient, boolean rerankEnabled) {
        this.storeFactory = storeFactory;
        this.embeddingClient = embeddingClient;
        this.rerankEnabled = rerankEnabled;
    }

    public RetrievalMetrics evaluate(Path bundle, RetrievalEvaluationDataset dataset) throws EmbeddingException {
        rerankDiagnostics.clear();
        try (BoxStore store = storeFactory.openReadOnlyExisting(bundle)) {
            KnowledgeStoreMetadataEntity metadata = requireMetadata(store);
            if (!dataset.knowledgeScopeId().equals(metadata.knowledgeScopeId)) {
                throw new IllegalArgumentException("EVALUATION_SCOPE_MISMATCH");
            }
            Map<String, KnowledgeChunkEntity> children = children(store);
            Map<Long, KnowledgeChunkEntity> childrenByEntityId = new HashMap<>();
            for (KnowledgeChunkEntity child : children.values()) childrenByEntityId.put(child.id, child);
            for (RetrievalEvaluationDataset.Case value : dataset.cases()) {
                if (!children.keySet().containsAll(value.expectedChunkIds())) {
                    throw new IllegalArgumentException("EVALUATION_EXPECTED_CHUNK_UNKNOWN");
                }
            }
            Map<String, LexicalTermEntity> terms = terms(store);
            List<RetrievalMetrics.CaseResult> cases = new ArrayList<>();
            for (RetrievalEvaluationDataset.Case value : dataset.cases()) {
                cases.add(evaluateCase(store, metadata, children, childrenByEntityId, terms, value));
            }
            return metrics(cases);
        }
    }

    /** 仅包含 ID、长度和分数的评测诊断；禁止写入 Query 与正文。 */
    public List<RerankDiagnostic> rerankDiagnostics() { return List.copyOf(rerankDiagnostics); }

    private RetrievalMetrics.CaseResult evaluateCase(BoxStore store, KnowledgeStoreMetadataEntity metadata,
                                                     Map<String, KnowledgeChunkEntity> children,
                                                     Map<Long, KnowledgeChunkEntity> childrenByEntityId,
                                                     Map<String, LexicalTermEntity> terms,
                                                     RetrievalEvaluationDataset.Case value) throws EmbeddingException {
        String query = normalize(value.query());
        float[] vector = embeddingClient.embed(List.of(new EmbeddingRequest(0, value.caseId(), query))).vectors().get(0);
        if (vector == null || vector.length != 1024) {
            throw new IllegalArgumentException("EVALUATION_QUERY_VECTOR_INVALID");
        }
        List<String> dense = dense(store, vector);
        List<String> lexical = lexical(query, metadata, childrenByEntityId, terms);
        List<String> ranked = fuse(dense, lexical);
        if (rerankEnabled) ranked = rerank(value, query, ranked, children);
        ranked = ranked.stream().limit(REPORT_TOP_K).toList();
        int rank = 0;
        for (int index = 0; index < ranked.size(); index++) {
            if (value.expectedChunkIds().contains(ranked.get(index))) {
                rank = index + 1;
                break;
            }
        }
        return new RetrievalMetrics.CaseResult(value.caseId(), Sha256.ofUtf8(query), rank, ranked);
    }

    private static List<String> dense(BoxStore store, float[] vector) {
        List<KnowledgeChunkEntity> values = store.boxFor(KnowledgeChunkEntity.class)
                .query(KnowledgeChunkEntity_.embedding.nearestNeighbors(vector, DENSE_TOP_K * 3)).build().find();
        return values.stream().filter(value -> "CHILD".equals(value.chunkLevel))
                .sorted(Comparator.comparingDouble((KnowledgeChunkEntity value) -> cosineDistance(vector, value.embedding))
                        .thenComparing(value -> value.chunkId))
                .limit(DENSE_TOP_K).map(value -> value.chunkId).toList();
    }

    private List<String> lexical(String query, KnowledgeStoreMetadataEntity metadata,
                                 Map<Long, KnowledgeChunkEntity> childrenByEntityId, Map<String, LexicalTermEntity> terms) {
        Map<String, Double> scores = new HashMap<>();
        for (String token : new HashSet<>(analyzer.analyze(query))) {
            LexicalTermEntity term = terms.get(token);
            if (term == null) continue;
            for (int index = 0; index < term.chunkEntityIds.length; index++) {
                KnowledgeChunkEntity child = childrenByEntityId.get(term.chunkEntityIds[index]);
                if (child == null) continue;
                scores.merge(child.chunkId, bm25(term.termFrequencies[index], term.documentFrequency,
                        (int) metadata.childChunkCount, child.lexicalDocumentLength,
                        metadata.averageLexicalDocumentLength), Double::sum);
            }
        }
        return scores.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(LEXICAL_TOP_K).map(Map.Entry::getKey).toList();
    }

    private static Map<String, KnowledgeChunkEntity> children(BoxStore store) {
        Map<String, KnowledgeChunkEntity> result = new HashMap<>();
        for (KnowledgeChunkEntity value : store.boxFor(KnowledgeChunkEntity.class).getAll()) {
            if ("CHILD".equals(value.chunkLevel)) result.put(value.chunkId, value);
        }
        return result;
    }

    private static Map<String, LexicalTermEntity> terms(BoxStore store) {
        Map<String, LexicalTermEntity> result = new HashMap<>();
        for (LexicalTermEntity value : store.boxFor(LexicalTermEntity.class).getAll()) result.put(value.term, value);
        return result;
    }

    private static List<String> fuse(List<String> dense, List<String> lexical) {
        Map<String, Double> scores = new HashMap<>();
        addRrf(scores, dense);
        addRrf(scores, lexical);
        return scores.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey)).map(Map.Entry::getKey).toList();
    }

    /** 与 Android qwen3-rerank 请求协议对齐；只发送已通过本地候选链路的标题路径与 Chunk 文本。 */
    private List<String> rerank(RetrievalEvaluationDataset.Case evaluationCase, String query, List<String> candidates, Map<String, KnowledgeChunkEntity> children) throws EmbeddingException {
        if (candidates.isEmpty()) return candidates;
        String key = DashScopeApiKeyProvider.load();
        if (key == null || key.isBlank()) throw new EmbeddingException("DASHSCOPE_API_KEY_MISSING", false);
        try {
            List<RerankInput> sent = budget(candidates, children);
            if (sent.isEmpty()) throw new EmbeddingException("RERANK_CANDIDATE_EMPTY", false);
            int topN = Math.min(RERANK_TOP_K, sent.size());
            ObjectMapper mapper = new ObjectMapper(); var root = mapper.createObjectNode(); root.put("model", "qwen3-rerank"); root.put("query", query); root.put("top_n", topN); var docs = root.putArray("documents");
            // qwen3-rerank 兼容接口的文本 documents 必须是字符串数组，不使用 qwen3-vl 的对象形式。
            for (RerankInput input : sent) docs.add(input.text());
            Request request = new Request.Builder().url("https://dashscope.aliyuncs.com/compatible-api/v1/reranks").header("Authorization", "Bearer " + key).post(RequestBody.create(mapper.writeValueAsBytes(root), MediaType.get("application/json; charset=utf-8"))).build();
            try (Response response = new OkHttpClient().newCall(request).execute()) {
                if (!response.isSuccessful()) throw new EmbeddingException("RERANK_HTTP_" + response.code(), response.code() == 429 || response.code() >= 500);
                JsonNode results = mapper.readTree(response.body().bytes()).path("results"); if (!results.isArray() || results.size() != topN) throw new EmbeddingException("RERANK_RESPONSE_INVALID", false);
                Map<String, Double> scores = new HashMap<>(); List<RerankResponse> responseItems = new ArrayList<>(); Set<Integer> indexes = new HashSet<>();
                for (JsonNode item : results) { int index=item.path("index").asInt(-1); double score=item.path("relevance_score").asDouble(Double.NaN); if(index<0||index>=sent.size()||!indexes.add(index)||!Double.isFinite(score)) throw new EmbeddingException("RERANK_RESPONSE_INVALID",false); String chunkId=sent.get(index).chunkId(); scores.put(chunkId,score); responseItems.add(new RerankResponse(index,chunkId,score)); }
                List<String> output = new ArrayList<>(candidates); output.sort(Comparator.<String>comparingDouble(id -> scores.getOrDefault(id, Double.NEGATIVE_INFINITY)).reversed().thenComparing(id -> id));
                rerankDiagnostics.add(new RerankDiagnostic(evaluationCase.caseId(), Sha256.ofUtf8(query), List.copyOf(candidates), sent.stream().map(input -> new RerankSentCandidate(input.chunkId(), input.characterCount())).toList(), List.copyOf(responseItems), List.copyOf(output), Set.copyOf(evaluationCase.expectedChunkIds())));
                return output;
            }
        } catch (EmbeddingException error) { throw error; } catch (Exception error) { throw new EmbeddingException("RERANK_TRANSPORT_FAILURE", true); }
    }

    private static List<RerankInput> budget(List<String> candidates, Map<String, KnowledgeChunkEntity> children) throws EmbeddingException {
        List<RerankInput> output=new ArrayList<>(); int used=0;
        for(String chunkId:candidates){KnowledgeChunkEntity chunk=children.get(chunkId);if(chunk==null)throw new EmbeddingException("RERANK_CANDIDATE_MAPPING_INVALID",false);String heading=chunk.headingPath==null?"":chunk.headingPath;String content=chunk.content==null?"":chunk.content;int remaining=RERANK_MAX_CHARACTERS-used-heading.length();if(remaining<=0)break;String text=heading+"\n"+content.substring(0,Math.min(content.length(),remaining));output.add(new RerankInput(chunkId,text,heading.length()+Math.min(content.length(),remaining)));used+=heading.length()+Math.min(content.length(),remaining);}return List.copyOf(output);
    }

    public record RerankDiagnostic(String caseId,String querySha256,List<String> rrfCandidateChunkIds,List<RerankSentCandidate> sentCandidates,List<RerankResponse> response,List<String> finalRankedChunkIds,Set<String> expectedChunkIds) { }
    public record RerankSentCandidate(String chunkId,int characterCount) { }
    public record RerankResponse(int index,String chunkId,double relevanceScore) { }
    private record RerankInput(String chunkId,String text,int characterCount) { }

    private static void addRrf(Map<String, Double> scores, List<String> values) {
        for (int index = 0; index < values.size(); index++) {
            scores.merge(values.get(index), 1D / (RRF_K + index + 1), Double::sum);
        }
    }

    private static double bm25(int termFrequency, int documentFrequency, int corpusSize, int documentLength,
                               double averageLength) {
        if (termFrequency <= 0 || documentFrequency <= 0 || corpusSize <= 0 || averageLength <= 0D) return 0D;
        double idf = Math.log(1D + (corpusSize - documentFrequency + 0.5D) / (documentFrequency + 0.5D));
        double denominator = termFrequency + BM25_K1 * (1D - BM25_B + BM25_B * documentLength / averageLength);
        return idf * termFrequency * (BM25_K1 + 1D) / denominator;
    }

    private static KnowledgeStoreMetadataEntity requireMetadata(BoxStore store) {
        List<KnowledgeStoreMetadataEntity> values = store.boxFor(KnowledgeStoreMetadataEntity.class).getAll();
        if (values.size() != 1) throw new IllegalArgumentException("EVALUATION_STORE_METADATA_INVALID");
        return values.get(0);
    }

    private static String normalize(String value) {
        String result = Normalizer.normalize(value, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (result.isEmpty() || result.codePointCount(0, result.length()) > QUERY_MAX_CODE_POINTS) {
            throw new IllegalArgumentException("EVALUATION_QUERY_INVALID");
        }
        return result;
    }

    private static double cosineDistance(float[] left, float[] right) {
        if (right == null || right.length != left.length) return Double.POSITIVE_INFINITY;
        double dot = 0D, leftNorm = 0D, rightNorm = 0D;
        for (int index = 0; index < left.length; index++) {
            dot += (double) left[index] * right[index];
            leftNorm += (double) left[index] * left[index];
            rightNorm += (double) right[index] * right[index];
        }
        return leftNorm == 0D || rightNorm == 0D ? Double.POSITIVE_INFINITY : 1D - dot / Math.sqrt(leftNorm * rightNorm);
    }

    private static RetrievalMetrics metrics(List<RetrievalMetrics.CaseResult> cases) {
        double at1 = 0D, at3 = 0D, at5 = 0D, mrr = 0D;
        for (RetrievalMetrics.CaseResult value : cases) {
            if (value.firstRelevantRank() > 0) {
                mrr += 1D / value.firstRelevantRank();
                if (value.firstRelevantRank() <= 1) at1++;
                if (value.firstRelevantRank() <= 3) at3++;
                if (value.firstRelevantRank() <= 5) at5++;
            }
        }
        int size = cases.size();
        return new RetrievalMetrics(size, at1 / size, at3 / size, at5 / size, mrr / size, cases);
    }
}
