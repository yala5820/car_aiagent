package com.hirain.aiagent.rag.trace;

import com.hirain.aiagent.trace.TestTraceSupport;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** RAG Trace 契约只允许受控诊断字段。 */
public class RagTraceRecorderTest {
    @Test public void recordsOnlyApprovedSnapshotFields() {
        TestTraceSupport.TestSession test = TestTraceSupport.redactedSession();
        try {
            RagTraceRecorder recorder = new RagTraceRecorder(test.traceSession);
            RagTraceSnapshot snapshot = new RagTraceSnapshot("sha256:abc", "v1", true, 2,
                    List.of("doc-1"), List.of("E1"), "LEXICAL_ONLY", 33L, 9_000L, "");
            Span span = recorder.startRetrieve(snapshot);
            recorder.finishRetrieve(span, snapshot);
            SpanData data = test.exporter.spans.stream()
                    .filter(item -> "rag.retrieve".equals(item.getName())).findFirst().orElseThrow();
            assertEquals("sha256:abc", data.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("rag.query.hash")));
            assertEquals("doc-1", data.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("rag.document.ids")));
            assertFalse(data.getAttributes().asMap().toString().contains("车辆知识原文"));
        } finally { test.close(); }
    }

    @Test public void snapshotNeverCarriesFreeTextFields() {
        assertEquals(10, RagTraceSnapshot.class.getRecordComponents().length);
        assertTrue(java.util.Arrays.stream(RagTraceSnapshot.class.getRecordComponents())
                .noneMatch(component -> component.getName().toLowerCase().contains("querytext")
                        || component.getName().toLowerCase().contains("content")));
    }
}
