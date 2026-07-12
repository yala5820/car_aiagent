package com.hirain.aiagent.runtime;

import com.hirain.aiagent.AgentRequest;
import com.hirain.aiagent.context.ContextBuildInput;
import com.hirain.aiagent.context.ContextFrame;
import com.hirain.aiagent.context.ContextOrchestrator;
import com.hirain.aiagent.core.AgentResult;
import com.hirain.aiagent.prompt.PromptManager;
import com.hirain.aiagent.toolgroup.ToolGroupId;
import com.hirain.aiagent.toolgroup.ToolGroupRegistry;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import dev.langchain4j.agent.tool.ToolSpecification;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AgentRuntimeContextTest {

    // ── JVM 友好 ToolRegistry（避开 android.util.Log） ──

    private static class JvmToolRegistry extends com.hirain.aiagent.ai.langchain4j.tool.ToolRegistry {
        final Map<String, ToolSpecification> specs = new LinkedHashMap<>();
        JvmToolRegistry() {}
        @Override public List<ToolSpecification> toolSpecificationsByNames(List<String> names) {
            if (names == null || names.isEmpty()) return List.of();
            List<ToolSpecification> r = new java.util.ArrayList<>();
            for (String n : names) { ToolSpecification s = specs.get(n); if (s != null) r.add(s); }
            return r;
        }
        @Override public List<ToolSpecification> enabledToolSpecifications() { return new java.util.ArrayList<>(specs.values()); }
        @Override public List<ToolSpecification> getToolSpecifications() { return new java.util.ArrayList<>(specs.values()); }
        @Override public int size() { return specs.size(); }
    }

    // ── PromptManager fake ──

    private static PromptManager testPrompt() {
        return new PromptManager(null) {
            @Override public String render(String templateName) { return "System prompt for " + templateName; }
        };
    }

    // ── 完整 ContextBuildInput ──

    private static ContextBuildInput fullInput() {
        return ContextBuildInput.builder()
                .promptManager(testPrompt())
                .toolRegistry(new JvmToolRegistry())
                .toolGroupRegistry(ToolGroupRegistry.defaultRegistry())
                .build();
    }

    @Test
    public void execute_buildsContextFrameBeforeExecutor() {
        AtomicReference<ContextFrame> frameRef = new AtomicReference<>();
        AgentExecutor executor = (session, prepareResult) -> {
            frameRef.set(prepareResult.frame());
            return AgentResult.success("完成", 1, 1L, List.of());
        };
        ContextOrchestrator contextOrchestrator =
                ContextOrchestrator.defaultForText(fullInput());
        AgentRuntime runtime = new AgentRuntime(executor, contextOrchestrator,
                () -> "req-1", () -> 1000L);
        AgentRequest request = new AgentRequest();
        request.setInputType("TEXT");
        request.setText("打开空调");

        RuntimeResult result = runtime.execute(runtime.startSession(request, null));

        assertTrue(result.success());
        assertEquals("req-1", frameRef.get().requestId());
    }

    @Test
    public void execute_cancelledAfterContextBuildDoesNotCallExecutor() {
        AtomicBoolean executorCalled = new AtomicBoolean(false);
        AgentExecutor executor = (session, prepareResult) -> {
            executorCalled.set(true);
            return AgentResult.success("不应执行", 1, 1L, List.of());
        };
        AgentRuntime runtime = new AgentRuntime(
                executor,
                ContextOrchestrator.defaultForText(fullInput()),
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
        ContextOrchestrator orchestrator = ContextOrchestrator.defaultForText(fullInput());
        AgentExecutor executor = (session, prepareResult) -> {
            frameRef.set(prepareResult.frame());
            return AgentResult.success("ok", 1, 1L, List.of());
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
