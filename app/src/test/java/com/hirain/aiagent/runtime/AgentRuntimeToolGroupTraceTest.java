package com.hirain.aiagent.runtime;

import static org.junit.Assert.assertEquals;

import com.hirain.aiagent.AgentRequest;
import com.hirain.aiagent.context.ContextPrepareResult;
import com.hirain.aiagent.core.AgentResult;
import com.hirain.aiagent.intentrouter.IntentConfidence;
import com.hirain.aiagent.intentrouter.IntentResult;
import com.hirain.aiagent.intentrouter.IntentTag;
import com.hirain.aiagent.toolgroup.ToolGroupId;
import com.hirain.aiagent.toolgroup.ToolGroupSelectionResult;
import com.hirain.aiagent.trace.TraceContext;
import com.hirain.aiagent.trace.TestTraceSupport;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;

import org.junit.Test;

import java.util.List;

public class AgentRuntimeToolGroupTraceTest {

    @Test
    public void startSession_writesToolGroupSelectionToTrace() {
        TestTraceSupport.TestSession testSession = TestTraceSupport.redactedSession();

        AgentRuntime runtime = new AgentRuntime(
                (session, prepareResult) -> AgentResult.success("完成", 1, 1L, List.of()),
                (text, sourceInputType) -> IntentResult.of(IntentTag.VEHICLE_AC, IntentConfidence.HIGH,
                        List.of("空调"), text, sourceInputType, "matched:VEHICLE_AC"),
                (intentResult, userInput) -> ToolGroupSelectionResult.of(
                        List.of(ToolGroupId.AC_GROUP, ToolGroupId.BASIC_STATUS_GROUP),
                        List.of("set_ac_status"),
                        "intent:VEHICLE_AC",
                        IntentConfidence.HIGH,
                        false),
                () -> "req-fixed",
                () -> 3000L);
        AgentRequest request = new AgentRequest();
        request.setInputType("TEXT");
        request.setText("打开空调");

        runtime.startSession(request, new TraceContext("trace-1", "span-1", testSession.traceSession));
        testSession.close();

        SpanData root = testSession.exporter.spans.get(0);
        assertEquals("AC_GROUP,BASIC_STATUS_GROUP", root.getAttributes()
                .get(AttributeKey.stringKey("agent.tool_group.selected_group_ids")));
        assertEquals("set_ac_status", root.getAttributes()
                .get(AttributeKey.stringKey("agent.tool_group.selected_tool_names")));
        assertEquals("intent:VEHICLE_AC", root.getAttributes()
                .get(AttributeKey.stringKey("agent.tool_group.selection_reason")));
        assertEquals("HIGH", root.getAttributes()
                .get(AttributeKey.stringKey("agent.tool_group.confidence")));
        assertEquals(false, root.getAttributes()
                .get(AttributeKey.booleanKey("agent.tool_group.fallback_used")));
    }
}
