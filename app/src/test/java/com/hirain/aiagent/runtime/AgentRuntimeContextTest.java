package com.hirain.aiagent.runtime;

import com.hirain.aiagent.AgentRequest;
import com.hirain.aiagent.context.ContextBuildInput;
import com.hirain.aiagent.context.ContextFrame;
import com.hirain.aiagent.context.ContextMode;
import com.hirain.aiagent.context.ContextOrchestrator;
import com.hirain.aiagent.core.AgentResult;
import com.hirain.aiagent.toolgroup.ToolGroupId;
import com.hirain.aiagent.toolgroup.ToolGroupRegistry;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AgentRuntimeContextTest {
    @Test
    public void execute_buildsContextFrameBeforeExecutor() {
        AtomicReference<ContextFrame> frameRef = new AtomicReference<>();
        AgentExecutor executor = new AgentExecutor() {
            @Override
            public AgentResult execute(String userInput, java.util.Map<String, Object> context) {
                return AgentResult.error(AgentResult.ErrorType.INVALID_CONFIG, "old path should not be used");
            }

            @Override
            public AgentResult execute(RequestSession session, ContextFrame contextFrame) {
                frameRef.set(contextFrame);
                return AgentResult.success("完成", 1, 1L, List.of());
            }
        };
        ContextOrchestrator contextOrchestrator =
                ContextOrchestrator.defaultForText(ContextBuildInput.builder()
                        .mode(ContextMode.HYBRID_EXTRA_CONTEXT)
                        .toolGroupRegistry(ToolGroupRegistry.defaultRegistry())
                        .build());
        AgentRuntime runtime = new AgentRuntime(executor, contextOrchestrator,
                () -> "req-1", () -> 1000L);
        AgentRequest request = new AgentRequest();
        request.setInputType("TEXT");
        request.setText("打开空调");

        RuntimeResult result = runtime.execute(runtime.startSession(request, null));

        assertTrue(result.success());
        assertEquals("req-1", frameRef.get().requestId());
        assertEquals(ContextMode.HYBRID_EXTRA_CONTEXT, frameRef.get().mode());
    }

    @Test
    public void execute_cancelledAfterContextBuildDoesNotCallExecutor() {
        AtomicBoolean executorCalled = new AtomicBoolean(false);
        AgentExecutor executor = new AgentExecutor() {
            @Override
            public AgentResult execute(String userInput, java.util.Map<String, Object> context) {
                executorCalled.set(true);
                return AgentResult.success("不应执行", 1, 1L, List.of());
            }
        };
        AgentRuntime runtime = new AgentRuntime(
                executor,
                ContextOrchestrator.defaultForText(ContextBuildInput.builder()
                        .mode(ContextMode.HYBRID_EXTRA_CONTEXT)
                        .toolGroupRegistry(ToolGroupRegistry.defaultRegistry())
                        .build()),
                session -> true);
        AgentRequest request = new AgentRequest();
        request.setInputType("TEXT");
        request.setText("打开空调");

        RuntimeResult result = runtime.execute(runtime.startSession(request, null));

        assertEquals("CANCELLED", result.errorType());
        assertFalse(executorCalled.get());
    }

    // ── 验收测试：selectedToolNames 非全量工具 ──

    private AgentRuntime runtimeWithContextCapture(AtomicReference<ContextFrame> frameRef) {
        ContextOrchestrator orchestrator = ContextOrchestrator.defaultForText(
                ContextBuildInput.builder()
                        .mode(ContextMode.HYBRID_EXTRA_CONTEXT)
                        .toolGroupRegistry(ToolGroupRegistry.defaultRegistry())
                        .build());
        AgentExecutor executor = new AgentExecutor() {
            @Override
            public AgentResult execute(String userInput, Map<String, Object> context) {
                return AgentResult.success("ok", 1, 1L, List.of());
            }

            @Override
            public AgentResult execute(RequestSession session, ContextFrame contextFrame) {
                frameRef.set(contextFrame);
                return AgentResult.success("ok", 1, 1L, List.of());
            }
        };
        return new AgentRuntime(executor, orchestrator, () -> "req-1", () -> 1000L);
    }

    @Test
    public void acRequest_selectedToolsAreAcAndBasicOnly() {
        AtomicReference<ContextFrame> frameRef = new AtomicReference<>();
        AgentRuntime runtime = runtimeWithContextCapture(frameRef);
        AgentRequest request = new AgentRequest();
        request.setInputType("TEXT");
        request.setText("把空调打开");

        RequestSession session = runtime.startSession(request, null);
        RuntimeResult result = runtime.execute(session);

        assertTrue(result.success());
        assertTrue(session.toolGroupSelectionResult().selectedGroupIds().contains(ToolGroupId.AC_GROUP));
        assertFalse(session.toolGroupSelectionResult().selectedToolNames().contains("set_fl_window_status"));
        assertEquals(session.toolGroupSelectionResult().selectedToolNames(), frameRef.get().selectedToolNames());
    }

    @Test
    public void windowRequest_selectedToolsAreWindowAndBasicOnly() {
        AtomicReference<ContextFrame> frameRef = new AtomicReference<>();
        AgentRuntime runtime = runtimeWithContextCapture(frameRef);
        AgentRequest request = new AgentRequest();
        request.setInputType("TEXT");
        request.setText("打开车窗");

        RequestSession session = runtime.startSession(request, null);
        RuntimeResult result = runtime.execute(session);

        assertTrue(result.success());
        assertTrue(session.toolGroupSelectionResult().selectedGroupIds().contains(ToolGroupId.WINDOW_GROUP));
        assertFalse(session.toolGroupSelectionResult().selectedToolNames().contains("set_ac_status"));
        assertEquals(session.toolGroupSelectionResult().selectedToolNames(), frameRef.get().selectedToolNames());
    }

    @Test
    public void seatRequest_selectedToolsAreSeatAndBasicOnly() {
        AtomicReference<ContextFrame> frameRef = new AtomicReference<>();
        AgentRuntime runtime = runtimeWithContextCapture(frameRef);
        AgentRequest request = new AgentRequest();
        request.setInputType("TEXT");
        request.setText("打开座椅加热");

        RequestSession session = runtime.startSession(request, null);
        RuntimeResult result = runtime.execute(session);

        assertTrue(result.success());
        assertTrue(session.toolGroupSelectionResult().selectedGroupIds().contains(ToolGroupId.SEAT_GROUP));
        assertFalse(session.toolGroupSelectionResult().selectedToolNames().contains("set_ac_status"));
        assertEquals(session.toolGroupSelectionResult().selectedToolNames(), frameRef.get().selectedToolNames());
    }

    @Test
    public void chatRequest_selectedToolsAreNotAllTools() {
        AtomicReference<ContextFrame> frameRef = new AtomicReference<>();
        AgentRuntime runtime = runtimeWithContextCapture(frameRef);
        AgentRequest request = new AgentRequest();
        request.setInputType("TEXT");
        request.setText("你好，今天心情不错");

        RequestSession session = runtime.startSession(request, null);
        RuntimeResult result = runtime.execute(session);

        assertTrue(result.success());
        assertTrue(session.toolGroupSelectionResult().selectedToolNames().size() < 47);
        assertEquals(session.toolGroupSelectionResult().selectedToolNames(), frameRef.get().selectedToolNames());
    }
}
