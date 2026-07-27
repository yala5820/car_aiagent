package com.hirain.aiagent.rag.ranking;

import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 在 RRF 后统一删除重复候选，并限制单一 Parent 占用 Rerank 候选位。
 * 该策略只使用本地确定性文本相似度，避免把去重结果依赖到云端模型。
 */
public final class FusionCandidateDeduplicator {
    private final int maxCandidates;
    private final int maxPerParent;
    private final double nearDuplicateThreshold;

    public FusionCandidateDeduplicator() {
        this(30, 2, 0.92D);
    }

    public FusionCandidateDeduplicator(int maxCandidates, int maxPerParent, double nearDuplicateThreshold) {
        if (maxCandidates <= 0 || maxPerParent <= 0 || nearDuplicateThreshold <= 0D || nearDuplicateThreshold > 1D) {
            throw new IllegalArgumentException("CANDIDATE_DEDUP_CONFIG_INVALID");
        }
        this.maxCandidates = maxCandidates;
        this.maxPerParent = maxPerParent;
        this.nearDuplicateThreshold = nearDuplicateThreshold;
    }

    public CandidateDeduplicationResult deduplicate(List<FusionCandidate> input,
                                                     Map<String, KnowledgeChunkEntity> chunks) {
        List<FusionCandidate> kept = new ArrayList<>();
        List<CandidateDeduplicationRecord> records = new ArrayList<>();
        Map<String, String> exact = new HashMap<>();
        Map<String, Integer> parentCounts = new HashMap<>();
        for (FusionCandidate candidate : input == null ? List.<FusionCandidate>of() : input) {
            if (kept.size() >= maxCandidates) break;
            KnowledgeChunkEntity chunk = chunks == null ? null : chunks.get(candidate.chunkId());
            String parentId = chunk == null ? null : chunk.parentChunkId;
            int count = parentCounts.getOrDefault(parentId, 0);
            if (parentId != null && count >= maxPerParent) {
                records.add(new CandidateDeduplicationRecord(candidate.chunkId(), keptParentRepresentative(kept, chunks, parentId),
                        parentId, "PARENT_OCCUPANCY", 1D));
                continue;
            }
            String normalized = normalize(chunk == null ? "" : chunk.content);
            String hash = sha256(normalized);
            String exactKept = exact.get(hash);
            if (exactKept != null) {
                records.add(new CandidateDeduplicationRecord(candidate.chunkId(), exactKept, parentId,
                        "EXACT_CONTENT", 1D));
                continue;
            }
            String nearKept = null;
            double nearScore = 0D;
            for (FusionCandidate prior : kept) {
                KnowledgeChunkEntity priorChunk = chunks == null ? null : chunks.get(prior.chunkId());
                double similarity = jaccard(normalized, normalize(priorChunk == null ? "" : priorChunk.content));
                if (similarity >= nearDuplicateThreshold && similarity > nearScore) {
                    nearKept = prior.chunkId();
                    nearScore = similarity;
                }
            }
            if (nearKept != null) {
                records.add(new CandidateDeduplicationRecord(candidate.chunkId(), nearKept, parentId,
                        "NEAR_DUPLICATE", nearScore));
                continue;
            }
            kept.add(candidate);
            if (parentId != null) parentCounts.merge(parentId, 1, Integer::sum);
            exact.put(hash, candidate.chunkId());
        }
        return new CandidateDeduplicationResult(kept, records);
    }

    private static String keptParentRepresentative(List<FusionCandidate> kept,
                                                    Map<String, KnowledgeChunkEntity> chunks, String parentId) {
        for (FusionCandidate candidate : kept) {
            KnowledgeChunkEntity chunk = chunks == null ? null : chunks.get(candidate.chunkId());
            if (chunk != null && parentId.equals(chunk.parentChunkId)) return candidate.chunkId();
        }
        return null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
    }

    private static Set<String> shingles(String value) {
        Set<String> output = new HashSet<>();
        if (value == null || value.isEmpty()) return output;
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= 2) {
            output.add(value);
            return output;
        }
        int[] points = value.codePoints().toArray();
        for (int index = 0; index < points.length - 1; index++) {
            output.add(new String(points, index, 2));
        }
        return output;
    }

    private static double jaccard(String left, String right) {
        Set<String> a = shingles(left);
        Set<String> b = shingles(right);
        if (a.isEmpty() && b.isEmpty()) return 1D;
        if (a.isEmpty() || b.isEmpty()) return 0D;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0D : (double) intersection.size() / union.size();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(digest.length * 2);
            for (byte item : digest) output.append(String.format(java.util.Locale.ROOT, "%02x", item));
            return output.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("CANDIDATE_DEDUP_HASH_FAILED", exception);
        }
    }
}
