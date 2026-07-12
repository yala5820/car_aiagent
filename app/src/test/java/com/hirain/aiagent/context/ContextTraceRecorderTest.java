package com.hirain.aiagent.context;

import com.hirain.aiagent.trace.TestTraceSupport;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ContextTraceRecorderTest {
    @Test
    public void record_writesContextAttributesToRootSpan() {
        TestTraceSupport.TestSession testSession = TestTraceSupport.redactedSession();
        ContextTraceRecorder recorder = new ContextTraceRecorder(
                testSession.traceSession.toTraceContext());

        recorder.record(true,
                9, "RuntimeContextProvider,IntentContextProvider",
                2, "set_ac_status,set_ac_drive_temp",
                8, 123, false, 17L, null);
        testSession.close();

        SpanData root = testSession.exporter.spans.stream()
                .filter(span -> "agent.request".equals(span.getName()))
                .findFirst()
                .orElseThrow();
        assertEquals(2L, root.getAttributes()
                .get(AttributeKey.longKey("agent.context.selected_tool_count")).longValue());
    }
}
