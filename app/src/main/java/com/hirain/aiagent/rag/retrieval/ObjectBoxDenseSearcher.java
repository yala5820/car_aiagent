package com.hirain.aiagent.rag.retrieval;

import com.hirain.aiagent.rag.model.VehicleProfile;
import com.hirain.aiagent.rag.store.KnowledgeStoreGateway;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 先扩大 ObjectBox 近邻候选再严格过滤 Metadata，保证不合格 Chunk 不会进入 RRF。 */
public final class ObjectBoxDenseSearcher {
    private final MetadataEligibilityPolicy eligibility;
    private final StoreScopeEligibilityPolicy scopeEligibility;
    public ObjectBoxDenseSearcher(MetadataEligibilityPolicy eligibility) { this(eligibility, new StoreScopeEligibilityPolicy()); }
    public ObjectBoxDenseSearcher(MetadataEligibilityPolicy eligibility, StoreScopeEligibilityPolicy scopeEligibility) { this.eligibility = eligibility; this.scopeEligibility = scopeEligibility; }
    public List<DenseCandidate> search(KnowledgeStoreGateway gateway, float[] queryEmbedding, VehicleProfile profile, int limit) {
        if (gateway == null || queryEmbedding == null || queryEmbedding.length != 1024 || limit < 1 || !scopeEligibility.validate(gateway, profile).eligible()) return List.of();
        List<DenseCandidate> output = new ArrayList<>();
        for (KnowledgeChunkEntity chunk : gateway.nearestChildren(queryEmbedding, Math.max(limit * 3, limit))) if (eligibility.evaluate(chunk, profile).eligible()) output.add(new DenseCandidate(chunk, cosineDistance(queryEmbedding, chunk.embedding)));
        output.sort(Comparator.comparingDouble(DenseCandidate::distance).thenComparing(value -> value.chunk().chunkId));
        return List.copyOf(output.subList(0, Math.min(limit, output.size())));
    }
    private static double cosineDistance(float[] left, float[] right) { if (right == null || right.length != left.length) return Double.POSITIVE_INFINITY; double dot=0d, leftNorm=0d, rightNorm=0d; for(int index=0;index<left.length;index++){dot+=(double)left[index]*right[index];leftNorm+=(double)left[index]*left[index];rightNorm+=(double)right[index]*right[index];} return leftNorm==0d||rightNorm==0d?Double.POSITIVE_INFINITY:1d-dot/Math.sqrt(leftNorm*rightNorm); }
}
