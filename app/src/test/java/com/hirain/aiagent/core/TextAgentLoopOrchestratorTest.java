package com.hirain.aiagent.core;

import com.hirain.aiagent.VirtualStateMachine.VehicleStateMachine;
import com.hirain.aiagent.context.ContextAssemblyGateway;
import com.hirain.aiagent.context.ContextAssemblyRequest;
import com.hirain.aiagent.context.ContextAssemblyDebugInfo;
import com.hirain.aiagent.context.ContextAssemblyResult;
import com.hirain.aiagent.context.ContextBuildInput;
import com.hirain.aiagent.context.ContextCancelChecker;
import com.hirain.aiagent.context.ContextErrorCode;
import com.hirain.aiagent.context.ContextFrame;
import com.hirain.aiagent.context.ContextOrchestrator;
import com.hirain.aiagent.context.ContextPrepareResult;
import com.hirain.aiagent.context.TestRequestSessions;
import com.hirain.aiagent.core.collector.DirectTextCollector;
import com.hirain.aiagent.core.component.ModelCaller;
import com.hirain.aiagent.core.component.ToolExecutor;
import com.hirain.aiagent.core.postprocessor.NoOpPostProcessor;
import com.hirain.aiagent.core.terminator.NoToolCallTerminator;
import com.hirain.aiagent.memory.ContextMemoryGateway;
import com.hirain.aiagent.memory.LongTermMemorySnapshot;
import com.hirain.aiagent.memory.MemoryCompactionPlan;
import com.hirain.aiagent.memory.MemoryCompactionResult;
import com.hirain.aiagent.memory.MemoryEntry;
import com.hirain.aiagent.memory.MemorySnapshot;
import com.hirain.aiagent.runtime.RequestSession;
import com.hirain.aiagent.safety.DefaultSafetyRules;
import com.hirain.aiagent.safety.ToolSafetyEngine;
import com.hirain.aiagent.safety.confirmation.ToolConfirmationCoordinator;
import com.hirain.aiagent.trace.AgentTraceRecorder;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * TextAgentLoopOrchestrator 执行测试 — 从 AgentLoopOrchestratorTextPathTest 迁移而来。
 */
public class TextAgentLoopOrchestratorTest {

    // ── Fake Memory Gateway ──
    public static class FakeMemoryGateway implements ContextMemoryGateway {
        public final ChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(50).build();
        @Override public ChatMemory chatMemoryForSession(String sessionId, int maxMessages) { return chatMemory; }
        @Override public MemorySnapshot sessionMemorySnapshot(String sessionId, int maxMessages) {
            List<ChatMessage> msgs = chatMemory.messages();
            return new MemorySnapshot(sessionId, msgs, msgs.size(), "");
        }
        @Override public LongTermMemorySnapshot longTermMemorySnapshot(String userId) {
            return new LongTermMemorySnapshot(userId, new ArrayList<MemoryEntry>(), 0L);
        }
        @Override public void extractTurnMemory(String userId, String sessionId, String userMessage, String aiResponse, AgentTraceRecorder trace) {}
        @Override public MemoryCompactionPlan planSessionCompaction(String sessionId, int targetTokens) {
            return new MemoryCompactionPlan(sessionId, targetTokens, 0, List.of(), 0);
        }
        @Override public MemoryCompactionResult executeCompactionPlan(MemoryCompactionPlan plan, AgentTraceRecorder trace) {
            return MemoryCompactionResult.notExecuted();
        }
    }

    static class CapturingModelCaller implements ModelCaller {
        final List<ChatRequest> captured = new ArrayList<>();
        final List<ChatResponse> responses;
        int index = 0;
        CapturingModelCaller(List<ChatResponse> responses) { this.responses = responses; }
        @Override public ChatResponse call(ChatRequest request) {
            captured.add(request);
            return responses.get(Math.min(index++, responses.size() - 1));
        }
    }

    static class CountingToolExecutor implements ToolExecutor {
        final AtomicInteger callCount = new AtomicInteger(0);
        final List<ToolExecutionRequest> requests = new ArrayList<>();
        @Override public String execute(ToolExecutionRequest request) {
            callCount.incrementAndGet();
            requests.add(request);
            return "{\"result\": \"ok\"}";
        }
    }

    public static class FakeAssemblyGateway implements ContextAssemblyGateway {
        final List<ToolSpecification> tools;
        final String systemPrompt;
        public FakeAssemblyGateway(List<ToolSpecification> tools, String systemPrompt) {
            this.tools = tools;
            this.systemPrompt = systemPrompt;
        }
        @Override public ContextAssemblyResult assemble(ContextAssemblyRequest request) {
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(dev.langchain4j.data.message.SystemMessage.from(systemPrompt));
            return ContextAssemblyResult.success(messages, tools,
                    new com.hirain.aiagent.context.ContextBudgetReport(messages.size(), Integer.MAX_VALUE, true),
                    new com.hirain.aiagent.context.ContextAssemblyDebugInfo(List.of(), messages.size(), tools.size(), null),
                    List.of());
        }
    }

    private static AgentConfig configWith(CapturingModelCaller caller,
                                          ToolExecutor toolExec) {
        return AgentConfig.builder("chat")
                .modelName("qwen-turbo")
                .systemPromptTemplateName("prompts/system/assistant_default")
                .maxIterations(10).maxMemoryMessages(50)
                .memoryPolicy(AgentConfig.MemoryPolicy.PERSISTENT)
                .modelCaller(caller)
                .toolExecutor(toolExec)
                .toolRegistry(new TestToolRegistry(toolExec))
                .toolSubset(null)
                .postProcessors(List.of(new NoOpPostProcessor()))
                .terminator(new NoToolCallTerminator())
                .resultCollector(new DirectTextCollector())
                .timeout(java.time.Duration.ofSeconds(30))
                .build();
    }

    /** 在 ToolRegistry 边界替换反射层，消息仍经过真实 TextAgentLoop。 */
    private static final class TestToolRegistry
            extends com.hirain.aiagent.ai.langchain4j.tool.ToolRegistry {
        private final ToolExecutor executor;

        private TestToolRegistry(ToolExecutor executor) {
            this.executor = executor;
        }

        @Override
        public com.hirain.aiagent.ai.langchain4j.tool.ToolDispatchOutcome dispatchWithOutcome(
                ToolExecutionRequest request) {
            try {
                return com.hirain.aiagent.ai.langchain4j.tool.ToolDispatchOutcome.success(
                        executor.execute(request), "TestToolRegistry", request.name());
            } catch (Exception e) {
                return com.hirain.aiagent.ai.langchain4j.tool.ToolDispatchOutcome.failure(
                        true, true, "error: " + e.getMessage(), "TEST_DISPATCH_FAILED",
                        e.getMessage(), "TestToolRegistry", request.name());
            }
        }
    }

    private static ToolSafetyEngine allowAllSafetyEngine() {
        return new ToolSafetyEngine(new VehicleStateMachine(), Map.of());
    }

    private static TextAgentLoopOrchestrator newLoop(
            AgentConfig config,
            ContextMemoryGateway memoryGateway,
            ContextAssemblyGateway assemblyGateway) {
        return new TextAgentLoopOrchestrator(
                config, memoryGateway, assemblyGateway, allowAllSafetyEngine());
    }

    private static RequestSession session(String input) {
        return TestRequestSessions.textSession("req-1", "conv-1", "user-a", "chat", "client-1", input);
    }

    private static ContextPrepareResult prepare(RequestSession s) {
        ContextOrchestrator orch = ContextOrchestrator.defaultForText(ContextBuildInput.builder().build());
        ContextFrame frame = orch.prepare(s, ContextCancelChecker.neverCancelled()).frame();
        return ContextPrepareResult.success(frame, null, ContextCancelChecker.neverCancelled(), List.of());
    }

    @Test
    public void constructor_nullGateway_throws() {
        AgentConfig cfg = AgentConfig.builder("chat")
                .modelName("qwen-turbo")
                .systemPromptTemplateName("p")
                .maxIterations(1).maxMemoryMessages(2)
                .memoryPolicy(AgentConfig.MemoryPolicy.PERSISTENT)
                .modelCaller(req -> dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(AiMessage.from("ok")).build())
                .toolExecutor(req -> "{}")
                .toolSubset(null)
                .postProcessors(List.of(new NoOpPostProcessor()))
                .terminator(new NoToolCallTerminator())
                .resultCollector(new DirectTextCollector())
                .timeout(java.time.Duration.ofSeconds(30))
                .build();
        boolean thrown = false;
        try {
            newLoop(cfg, null,
                    new FakeAssemblyGateway(List.of(), ""));
        } catch (IllegalArgumentException e) { thrown = true; }
        assertTrue("null memoryGateway should throw", thrown);
    }

    @Test
    public void constructor_nullAssemblyGateway_throws() {
        AgentConfig cfg = AgentConfig.builder("chat")
                .modelName("qwen-turbo")
                .systemPromptTemplateName("p")
                .maxIterations(1).maxMemoryMessages(2)
                .memoryPolicy(AgentConfig.MemoryPolicy.PERSISTENT)
                .modelCaller(req -> dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(AiMessage.from("ok")).build())
                .toolExecutor(req -> "{}")
                .toolSubset(null)
                .postProcessors(List.of(new NoOpPostProcessor()))
                .terminator(new NoToolCallTerminator())
                .resultCollector(new DirectTextCollector())
                .timeout(java.time.Duration.ofSeconds(30))
                .build();
        boolean thrown = false;
        try {
            newLoop(cfg,
                    new FakeMemoryGateway(), null);
        } catch (IllegalArgumentException e) { thrown = true; }
        assertTrue("null contextAssemblyGateway should throw", thrown);
    }

    @Test
    public void consecutiveExecutions_bothSucceed() {
        CapturingModelCaller caller = new CapturingModelCaller(List.of(
                ChatResponse.builder().aiMessage(AiMessage.from("第一次")).build(),
                ChatResponse.builder().aiMessage(AiMessage.from("第二次")).build()));
        AgentConfig config = configWith(caller, req -> "{}");
        FakeMemoryGateway mg = new FakeMemoryGateway();
        TextAgentLoopOrchestrator loop = newLoop(config, mg,
                new FakeAssemblyGateway(List.of(), "你是助手。"));

        AgentResult r1 = loop.execute(session("hi"), prepare(session("hi")));
        assertTrue("First execution should succeed", r1.isSuccess());

        AgentResult r2 = loop.execute(session("hi again"), prepare(session("hi again")));
        assertTrue("Second execution should also succeed, not 'Agent is busy'", r2.isSuccess());
    }

    @Test
    public void safetyDeny_preventsToolExecutionAndReturnsToLlm() {
        CountingToolExecutor toolExec = new CountingToolExecutor();
        CapturingModelCaller caller = new CapturingModelCaller(List.of(
                ChatResponse.builder().aiMessage(AiMessage.from(
                        ToolExecutionRequest.builder().id("r1").name("set_door_lock")
                                .arguments("{\"arg0\":false}").build()))
                        .build(),
                ChatResponse.builder().aiMessage(AiMessage.from("车辆行驶中，暂时不能解锁，请先停车。"))
                        .build()));
        AgentConfig config = configWith(caller, toolExec);
        FakeMemoryGateway memoryGateway = new FakeMemoryGateway();
        VehicleStateMachine vehicleStateMachine = new VehicleStateMachine();
        vehicleStateMachine.setVehicleSpd(70);
        ToolSafetyEngine safetyEngine = new ToolSafetyEngine(
                vehicleStateMachine, DefaultSafetyRules.create());
        TextAgentLoopOrchestrator loop = new TextAgentLoopOrchestrator(
                config, memoryGateway,
                new FakeAssemblyGateway(List.of(ToolSpecification.builder().name("set_door_lock").description("Lock doors").build()),
                        "你是车控助手。"),
                safetyEngine);

        AgentResult result = loop.execute(session("开门"), prepare(session("开门")));
        assertEquals("ToolExecutor should not be called after safety DENY",
                0, toolExec.callCount.get());
        assertEquals("DENY result should return to LLM for one explanation",
                2, caller.captured.size());
        assertTrue("LLM explanation should be returned", result.isSuccess());
        assertEquals("车辆行驶中，暂时不能解锁，请先停车。", result.output());
        boolean hasSafetyResult = memoryGateway.chatMemory.messages().stream().anyMatch(m ->
                m instanceof ToolExecutionResultMessage
                        && ((ToolExecutionResultMessage) m).text()
                        .contains("DOOR_UNLOCK_REQUIRES_STOPPED"));
        assertTrue("ChatMemory should contain stable safety reason code", hasSafetyResult);
    }

    @Test
    public void highRiskTool_firstTurnCreatesPendingWithoutDispatch_thenConfirmExecutesOnce() {
        VehicleStateMachine state = new VehicleStateMachine();
        CountingToolExecutor toolExec = new CountingToolExecutor();
        ToolSafetyEngine safetyEngine = new ToolSafetyEngine(state, DefaultSafetyRules.create());
        ToolConfirmationCoordinator coordinator = new ToolConfirmationCoordinator(
                safetyEngine, toolExec, System::currentTimeMillis);
        CapturingModelCaller caller = new CapturingModelCaller(List.of(
                ChatResponse.builder().aiMessage(AiMessage.from(
                        ToolExecutionRequest.builder().id("r-confirm").name("set_door_lock")
                                .arguments("{\"arg0\":false}").build())).build()));
        AgentConfig config = configWith(caller, toolExec);
        FakeMemoryGateway memory = new FakeMemoryGateway();
        TextAgentLoopOrchestrator loop = new TextAgentLoopOrchestrator(
                config, memory,
                new FakeAssemblyGateway(List.of(ToolSpecification.builder()
                        .name("set_door_lock").description("unlock").build()), "车控"),
                safetyEngine, coordinator);

        AgentResult first = loop.execute(session("解锁车门"), prepare(session("解锁车门")));

        assertTrue(first.isSuccess());
        assertTrue(first.output().contains("确认执行"));
        assertEquals(0, toolExec.callCount.get());
        assertNotNull(coordinator.pendingAction());
        assertTrue(memory.chatMemory.messages().stream()
                .anyMatch(message -> message instanceof ToolExecutionResultMessage
                        && ((ToolExecutionResultMessage) message).text()
                                .contains("CONFIRMATION_REQUIRED")));

        assertTrue(coordinator.handleText("确认执行", "conv-1", () -> false).success());
        assertEquals(1, toolExec.callCount.get());
        assertTrue(!coordinator.handleText("确认执行", "conv-1", () -> false).success());
        assertEquals(1, toolExec.callCount.get());
    }

    @Test
    public void multiToolBatchContainingConfirmation_executesNothingAndCreatesNoPending() {
        CountingToolExecutor toolExec = new CountingToolExecutor();
        ToolSafetyEngine safetyEngine = new ToolSafetyEngine(
                new VehicleStateMachine(), DefaultSafetyRules.create());
        ToolConfirmationCoordinator coordinator = new ToolConfirmationCoordinator(
                safetyEngine, toolExec, System::currentTimeMillis);
        CapturingModelCaller caller = new CapturingModelCaller(List.of(
                ChatResponse.builder().aiMessage(AiMessage.from(
                        ToolExecutionRequest.builder().id("r-low").name("set_ac_status")
                                .arguments("{\"arg0\":true}").build(),
                        ToolExecutionRequest.builder().id("r-high").name("set_door_lock")
                                .arguments("{\"arg0\":false}").build())).build()));
        AgentConfig config = configWith(caller, toolExec);
        FakeMemoryGateway memory = new FakeMemoryGateway();
        TextAgentLoopOrchestrator loop = new TextAgentLoopOrchestrator(
                config, memory, new FakeAssemblyGateway(List.of(), "车控"),
                safetyEngine, coordinator);
        RequestSession requestSession = session("打开空调并解锁");

        AgentResult result = loop.execute(requestSession, prepare(requestSession));

        assertTrue(result.isSuccess());
        assertTrue(result.output().contains("拆分"));
        assertEquals(0, toolExec.callCount.get());
        assertTrue(coordinator.pendingAction() == null);
        assertEquals(2, memory.chatMemory.messages().stream()
                .filter(message -> message instanceof ToolExecutionResultMessage).count());
    }

    @Test
    public void selectedTools_appearInChatRequest() {
        ToolSpecification acSpec = ToolSpecification.builder().name("set_ac_status").description("Set AC").build();
        CapturingModelCaller caller = new CapturingModelCaller(List.of(
                ChatResponse.builder().aiMessage(AiMessage.from("打开空调")).build()));
        AgentConfig config = configWith(caller, req -> "{}");
        TextAgentLoopOrchestrator loop = newLoop(config, new FakeMemoryGateway(),
                new FakeAssemblyGateway(List.of(acSpec), "你是车控助手。"));

        loop.execute(session("打开空调"), prepare(session("打开空调")));

        assertEquals("Assembly should pass 1 tool to ChatRequest", 1, caller.captured.size());
        List<ToolSpecification> tools = caller.captured.get(0).toolSpecifications();
        assertNotNull("ChatRequest should have toolSpecifications", tools);
        assertEquals("Tool count mismatch", 1, tools.size());
        assertEquals("set_ac_status", tools.get(0).name());
    }

    @Test
    public void toolException_writesErrorToolResult() {
        ToolExecutor failingExec = req -> { throw new RuntimeException("模拟工具异常"); };
        CapturingModelCaller caller = new CapturingModelCaller(List.of(
                ChatResponse.builder().aiMessage(AiMessage.from(
                        ToolExecutionRequest.builder().id("r1").name("set_ac_status").arguments("{}").build()))
                        .build(),
                ChatResponse.builder().aiMessage(AiMessage.from("错误已处理")).build()));
        AgentConfig config = configWith(caller, failingExec);
        FakeMemoryGateway mg = new FakeMemoryGateway();
        TextAgentLoopOrchestrator loop = newLoop(config, mg,
                new FakeAssemblyGateway(List.of(ToolSpecification.builder().name("set_ac_status").description("AC").build()),
                        "你是助手。"));

        AgentResult result = loop.execute(session("开空调"), prepare(session("开空调")));
        assertTrue("Should recover from tool exception", result.isSuccess());

        List<ChatMessage> msgs = mg.chatMemory.messages();
        boolean hasErrorToolResult = msgs.stream().anyMatch(m ->
                m instanceof ToolExecutionResultMessage
                        && ((ToolExecutionResultMessage) m).toolName().equals("set_ac_status")
                        && ((ToolExecutionResultMessage) m).text().contains("error"));
        assertTrue("ChatMemory should contain error ToolResult", hasErrorToolResult);
    }

    @Test
    public void cancelBeforeModelCall_modelNotCalled() {
        CapturingModelCaller caller = new CapturingModelCaller(List.of(
                ChatResponse.builder().aiMessage(AiMessage.from("should not be called")).build()));
        AgentConfig config = configWith(caller, req -> "{}");
        FakeMemoryGateway mg = new FakeMemoryGateway();
        AtomicBoolean cancelFlag = new AtomicBoolean(true);
        TextAgentLoopOrchestrator loop = newLoop(config, mg,
                new FakeAssemblyGateway(List.of(), "你是一个助手。") {
                    @Override public ContextAssemblyResult assemble(ContextAssemblyRequest req) {
                        return ContextAssemblyResult.failure(ContextErrorCode.CONTEXT_CANCELLED, "cancelled",
                                new ContextAssemblyDebugInfo(List.of(), 0, 0, "cancelled"));
                    }
                });

        RequestSession s = session("hi");
        ContextOrchestrator orch = ContextOrchestrator.defaultForText(ContextBuildInput.builder().build());
        ContextFrame frame = orch.prepare(s, ContextCancelChecker.neverCancelled()).frame();
        ContextPrepareResult pr = ContextPrepareResult.success(frame, null,
                () -> cancelFlag.get(), List.of());

        AgentResult result = loop.execute(s, pr);
        assertEquals(AgentResult.ErrorType.CANCELLED, result.errorType());
        assertEquals(0, caller.captured.size());
    }

    @Test
    public void cancelAfterModelCall_noAiMessageInChatMemory() {
        CapturingModelCaller caller = new CapturingModelCaller(List.of(
                ChatResponse.builder().aiMessage(AiMessage.from("a response")).build()));
        AgentConfig config = configWith(caller, req -> "{}");
        FakeMemoryGateway mg = new FakeMemoryGateway();
        AtomicBoolean cancelFlag = new AtomicBoolean(false);

        TextAgentLoopOrchestrator loop = newLoop(config, mg,
                new FakeAssemblyGateway(List.of(), "你是一个助手。") {
                    @Override public ContextAssemblyResult assemble(ContextAssemblyRequest req) {
                        cancelFlag.set(true);
                        return super.assemble(req);
                    }
                });

        RequestSession s = session("hi");
        ContextOrchestrator orch = ContextOrchestrator.defaultForText(ContextBuildInput.builder().build());
        ContextFrame frame = orch.prepare(s, ContextCancelChecker.neverCancelled()).frame();
        ContextPrepareResult pr = ContextPrepareResult.success(frame, null,
                () -> cancelFlag.get(), List.of());

        AgentResult result = loop.execute(s, pr);
        assertEquals(AgentResult.ErrorType.CANCELLED, result.errorType());
        boolean hasAiMessage = mg.chatMemory.messages().stream()
                .anyMatch(m -> m instanceof AiMessage && !((AiMessage) m).hasToolExecutionRequests());
        assertEquals("AiMessage should not be in chatMemory after cancel", false, hasAiMessage);
    }

    @Test
    public void multiToolCancel_cancelAfterFirstTool_secondHasCancelledResult() {
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        CapturingModelCaller caller = new CapturingModelCaller(List.of(
                ChatResponse.builder().aiMessage(AiMessage.from(
                        ToolExecutionRequest.builder().id("r1").name("tool_a").arguments("{}").build(),
                        ToolExecutionRequest.builder().id("r2").name("tool_b").arguments("{}").build()))
                        .build()));

        AgentConfig config = configWith(caller, req -> {
                    if ("tool_a".equals(req.name())) cancelFlag.set(true);
                    return "{\"ok\":true}";
                });

        FakeMemoryGateway mg = new FakeMemoryGateway();
        TextAgentLoopOrchestrator loop = newLoop(config, mg,
                new FakeAssemblyGateway(
                        List.of(ToolSpecification.builder().name("tool_a").build(),
                                ToolSpecification.builder().name("tool_b").build()),
                        "你是一个助手。"));

        RequestSession s = session("do two things");
        ContextOrchestrator orch = ContextOrchestrator.defaultForText(ContextBuildInput.builder().build());
        ContextFrame frame = orch.prepare(s, ContextCancelChecker.neverCancelled()).frame();
        ContextPrepareResult pr = ContextPrepareResult.success(frame, null,
                () -> cancelFlag.get(), List.of());

        AgentResult result = loop.execute(s, pr);
        assertEquals(AgentResult.ErrorType.CANCELLED, result.errorType());

        List<ChatMessage> msgs = mg.chatMemory.messages();
        boolean hasToolAResult = msgs.stream().anyMatch(m ->
                m instanceof ToolExecutionResultMessage
                        && "tool_a".equals(((ToolExecutionResultMessage) m).toolName())
                        && ((ToolExecutionResultMessage) m).text().contains("ok"));
        boolean hasToolBCancelled = msgs.stream().anyMatch(m ->
                m instanceof ToolExecutionResultMessage
                        && "tool_b".equals(((ToolExecutionResultMessage) m).toolName())
                        && ((ToolExecutionResultMessage) m).text().contains("CANCELLED"));
        assertTrue("tool_a should have real result", hasToolAResult);
        assertTrue("tool_b should have cancelled result", hasToolBCancelled);
    }

    @Test
    public void assemblyError_mapsCorrectErrorType() {
        assertEquals(AgentResult.ErrorType.CANCELLED,
                TextAgentLoopOrchestrator.mapAssemblyError(
                        ContextAssemblyResult.failure(ContextErrorCode.CONTEXT_CANCELLED, "x",
                                new ContextAssemblyDebugInfo(List.of(), 0, 0, "x"))));
        assertEquals(AgentResult.ErrorType.MESSAGE_SEQUENCE_INVALID,
                TextAgentLoopOrchestrator.mapAssemblyError(
                        ContextAssemblyResult.failure(ContextErrorCode.MESSAGE_SEQUENCE_INVALID, "x",
                                new ContextAssemblyDebugInfo(List.of(), 0, 0, "x"))));
        assertEquals(AgentResult.ErrorType.TOOL_SPEC_RESOLUTION_FAILED,
                TextAgentLoopOrchestrator.mapAssemblyError(
                        ContextAssemblyResult.failure(ContextErrorCode.TOOL_SPEC_RESOLUTION_FAILED, "x",
                                new ContextAssemblyDebugInfo(List.of(), 0, 0, "x"))));
        assertEquals(AgentResult.ErrorType.REQUIRED_PROVIDER_FAILED,
                TextAgentLoopOrchestrator.mapAssemblyError(
                        ContextAssemblyResult.failure(ContextErrorCode.REQUIRED_PROVIDER_FAILED, "x",
                                new ContextAssemblyDebugInfo(List.of(), 0, 0, "x"))));
        assertEquals(AgentResult.ErrorType.CONTEXT_BUDGET_EXCEEDED,
                TextAgentLoopOrchestrator.mapAssemblyError(
                        ContextAssemblyResult.failure(ContextErrorCode.CONTEXT_BUDGET_EXCEEDED, "x",
                                new ContextAssemblyDebugInfo(List.of(), 0, 0, "x"))));
        assertEquals(AgentResult.ErrorType.MEMORY_COMPACTION_FAILED,
                TextAgentLoopOrchestrator.mapAssemblyError(
                        ContextAssemblyResult.failure(ContextErrorCode.MEMORY_COMPACTION_FAILED, "x",
                                new ContextAssemblyDebugInfo(List.of(), 0, 0, "x"))));
        assertEquals(AgentResult.ErrorType.CONTEXT_BUILD_FAILED,
                TextAgentLoopOrchestrator.mapAssemblyError(
                        ContextAssemblyResult.failure(ContextErrorCode.CONTEXT_INTERNAL_ERROR, "x",
                                new ContextAssemblyDebugInfo(List.of(), 0, 0, "x"))));
    }

    @Test
    public void expiredDeadline_stopsBeforeModelCall() {
        CapturingModelCaller caller = new CapturingModelCaller(List.of(
                ChatResponse.builder().aiMessage(AiMessage.from("不应调用")).build()));
        TextAgentLoopOrchestrator loop = newLoop(
                configWith(caller, req -> "{}"),
                new FakeMemoryGateway(),
                new FakeAssemblyGateway(List.of(), "你是一个助手。"));
        RequestSession expired = TestRequestSessions.expiredTextSession(
                "req-expired", "conv-expired", "你好");

        AgentResult result = loop.execute(expired, prepare(expired));

        assertEquals(AgentResult.ErrorType.TIMEOUT, result.errorType());
        assertEquals(0, caller.captured.size());
    }
}
