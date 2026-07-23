package com.hirain.aiagent.rag.trace;

import com.hirain.aiagent.trace.TraceAttributeKeys;
import com.hirain.aiagent.trace.TraceSession;
import com.hirain.aiagent.trace.TraceSpanNames;

import io.opentelemetry.api.trace.Span;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * RAG 可观测性唯一入口。它只接受已脱敏的 Hash、ID、计数和稳定码；不提供正文参数，
 * 从类型边界避免 Query、Evidence、车辆动态状态或凭证意外写入 Trace。
 */
public final class RagTraceRecorder {
    private final TraceSession session;

    public RagTraceRecorder(TraceSession session) { this.session = session; }

    /** 只返回不可逆摘要；调用方不得把原始 Query 传入快照。 */
    public static String queryHash(String query) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((query == null ? "" : query).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte value : digest) out.append(String.format("%02x", value));
            return out.toString();
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }

    public Span startRetrieve(RagTraceSnapshot snapshot) {
        if (session == null) return null;
        Span span = session.startChildSpan(TraceSpanNames.RAG_RETRIEVE);
        write(span, snapshot, false);
        return span;
    }

    public void finishRetrieve(Span span, RagTraceSnapshot snapshot) {
        if (span == null) return;
        write(span, snapshot, true);
        if (!snapshot.failureCode().isEmpty()) span.setAttribute(TraceAttributeKeys.RAG_FAILURE_CODE, snapshot.failureCode());
        span.end();
    }

    private static void write(Span span, RagTraceSnapshot value, boolean includeOutcome) {
        if (span == null || value == null) return;
        span.setAttribute(TraceAttributeKeys.RAG_QUERY_HASH, value.queryHash());
        span.setAttribute(TraceAttributeKeys.RAG_BUNDLE_VERSION, value.bundleVersion());
        span.setAttribute(TraceAttributeKeys.RAG_SCOPE_MATCHED, value.scopeMatched());
        span.setAttribute(TraceAttributeKeys.RAG_CANDIDATE_COUNT, value.candidateCount());
        span.setAttribute(TraceAttributeKeys.RAG_DEADLINE_REMAINING_MS, value.deadlineRemainingMs());
        if (includeOutcome) {
            span.setAttribute(TraceAttributeKeys.RAG_DOCUMENT_IDS, String.join(",", value.documentIds()));
            span.setAttribute(TraceAttributeKeys.RAG_EVIDENCE_IDS, String.join(",", value.evidenceIds()));
            span.setAttribute(TraceAttributeKeys.RAG_RETRIEVAL_MODE, value.retrievalMode());
            span.setAttribute(TraceAttributeKeys.RAG_DURATION_MS, value.elapsedMs());
        }
    }
}
