package com.hirain.aiagent.rag.retrieval;

import com.hirain.aiagent.rag.config.RagRetrievalConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.Locale;

/** Query V1：NFKC、空白折叠、ROOT 小写与 Code Point 限长，禁止静默截断代理对。 */
public final class QueryNormalizer {
    private final RagRetrievalConfig config;
    public QueryNormalizer(RagRetrievalConfig config) { this.config = config; }
    public QueryNormalizationResult normalize(String original) {
        String normalized = Normalizer.normalize(original == null ? "" : original, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return QueryNormalizationResult.failure("QUERY_EMPTY");
        if (normalized.codePointCount(0, normalized.length()) > config.queryMaxCodePoints()) return QueryNormalizationResult.failure("QUERY_TOO_LONG");
        return new QueryNormalizationResult(true, normalized, sha256(normalized), null);
    }
    private static String sha256(String value) { try { byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder output = new StringBuilder(); for (byte item : bytes) output.append(String.format("%02x", item)); return output.toString(); } catch (Exception error) { throw new IllegalStateException("QUERY_HASH_UNAVAILABLE", error); } }
}
