package com.hirain.aiagent.runtime;

import com.hirain.aiagent.AgentRequest;
import com.hirain.aiagent.VirtualStateMachine.VehicleStateMachine;
import com.hirain.aiagent.ai.langchain4j.tool.ToolRegistry;
import com.hirain.aiagent.context.ContextBuildInput;
import com.hirain.aiagent.context.ContextCancelChecker;
import com.hirain.aiagent.context.ContextErrorCode;
import com.hirain.aiagent.context.ContextOrchestrator;
import com.hirain.aiagent.context.ContextPrepareResult;
import com.hirain.aiagent.core.AgentConfig;
import com.hirain.aiagent.core.AgentResult;
import com.hirain.aiagent.core.CapturingModelCaller;
import com.hirain.aiagent.core.TextAgentLoopOrchestrator;
import com.hirain.aiagent.core.collector.DirectTextCollector;
import com.hirain.aiagent.core.component.ToolExecutor;
import com.hirain.aiagent.core.postprocessor.NoOpPostProcessor;
import com.hirain.aiagent.core.terminator.NoToolCallTerminator;
import com.hirain.aiagent.memory.ContextMemoryGateway;
import com.hirain.aiagent.memory.LongTermMemorySnapshot;
import com.hirain.aiagent.memory.MemoryCompactionPlan;
import com.hirain.aiagent.memory.MemoryCompactionResult;
import com.hirain.aiagent.memory.MemoryEntry;
import com.hirain.aiagent.memory.MemorySnapshot;
import com.hirain.aiagent.prompt.PromptManager;
import com.hirain.aiagent.safety.ToolSafetyEngine;
import com.hirain.aiagent.runtime.RequestSession;
import com.hirain.aiagent.toolgroup.ToolGroupSelectionResult;
import com.hirain.aiagent.trace.AgentTraceRecorder;
import com.hirain.aiagent.toolgroup.ToolGroupRegistry;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * TEXT 集成测试 — 通过完整 AgentRuntime → ContextOrchestrator → TextAgentLoopOrchestrator 链路验证行为。
 * 核心策略：使用 JvmToolRegistry（内存 Map）绕过 android.util.Log 限制。
 */
public class ContextTextEndToEndTest {

    // ═══════════════════════════════════════════
    // JvmToolRegistry
    // ═══════════════════════════════════════════

    /** JVM 友好的 ToolRegistry — 以内存 Map 操作，不调用 ToolDispatcher/Log.d。 */
    public static class JvmToolRegistry extends ToolRegistry {
        final Map<String, ToolSpecification> specs = new LinkedHashMap<>();
        ToolExecutor executor;
        public JvmToolRegistry(ToolSpecification... specs) {
            for (ToolSpecification s : specs) { if (s != null) this.specs.put(s.name(), s); }
        }
        @Override public List<ToolSpecification> toolSpecificationsByNames(List<String> names) {
            if (names == null || names.isEmpty()) return List.of();
            List<ToolSpecification> r = new ArrayList<>();
            for (String n : names) { ToolSpecification s = specs.get(n); if (s != null) r.add(s); }
            return r;
        }
        @Override public List<ToolSpecification> enabledToolSpecifications() { return new ArrayList<>(specs.values()); }
        @Override public List<ToolSpecification> getToolSpecifications() { return new ArrayList<>(specs.values()); }
        @Override public int size() { return specs.size(); }
        @Override public com.hirain.aiagent.ai.langchain4j.tool.ToolDispatchOutcome dispatchWithOutcome(
                ToolExecutionRequest request) {
            if (executor == null) {
                return com.hirain.aiagent.ai.langchain4j.tool.ToolDispatchOutcome.failure(
                        false, false, "error: no test executor", "TEST_EXECUTOR_MISSING",
                        "no test executor", "JvmToolRegistry", request.name());
            }
            try {
                return com.hirain.aiagent.ai.langchain4j.tool.ToolDispatchOutcome.success(
                        executor.execute(request), "JvmToolRegistry", request.name());
            } catch (Exception e) {
                return com.hirain.aiagent.ai.langchain4j.tool.ToolDispatchOutcome.failure(
                        true, true, "error: " + e.getMessage(), "TEST_DISPATCH_FAILED",
                        e.getMessage(), "JvmToolRegistry", request.name());
            }
        }
    }

    // ═══════════════════════════════════════════
    // 测试用 Fake MemoryGateway
    // ═══════════════════════════════════════════

    public static class FakeMemoryGateway implements ContextMemoryGateway {
        public final ChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(50).build();
        public int planCallCount = 0, executeCallCount = 0;
        // 按 sessionId 隔离的 chatMemory（用于 session 隔离测试）
        public final java.util.Map<String, ChatMemory> perSessionChatMemory = new java.util.HashMap<>();

        @Override public ChatMemory chatMemoryForSession(String sessionId, int maxMessages) {
            return perSessionChatMemory.computeIfAbsent(sessionId,
                    k -> MessageWindowChatMemory.builder().maxMessages(50).build());
        }
        @Override public MemorySnapshot sessionMemorySnapshot(String sessionId, int maxMessages) {
            ChatMemory mem = perSessionChatMemory.get(sessionId);
            List<ChatMessage> msgs = mem != null ? mem.messages() : chatMemory.messages();
            return new MemorySnapshot(sessionId, msgs, msgs.size(), "");
        }
        @Override public LongTermMemorySnapshot longTermMemorySnapshot(String userId) {
            List<MemoryEntry> entries = new ArrayList<>();
            if ("user-a".equals(userId)) {
                entries.add(new MemoryEntry(MemoryEntry.Category.FACT,
                        "owner_memory", "A_ONLY_MEMORY", 1.0f, 0L, 1));
            } else if ("user-b".equals(userId)) {
                entries.add(new MemoryEntry(MemoryEntry.Category.FACT,
                        "owner_memory", "B_ONLY_MEMORY", 1.0f, 0L, 1));
            }
            return new LongTermMemorySnapshot(userId, entries, 0L);
        }
        @Override public void extractTurnMemory(String userId, String sessionId, String userMessage, String aiResponse, AgentTraceRecorder trace) {}
        @Override public MemoryCompactionPlan planSessionCompaction(String sessionId, int targetTokens) {
            planCallCount++;
            return new MemoryCompactionPlan(sessionId, targetTokens, 0, List.of(), 0);
        }
        @Override public MemoryCompactionResult executeCompactionPlan(MemoryCompactionPlan plan, AgentTraceRecorder trace) {
            executeCallCount++;
            return MemoryCompactionResult.notExecuted();
        }
    }

    // ═══════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════

    private static PromptManager testPrompt() {
        return new PromptManager(null) {
            @Override public String render(String templateName) { return "System prompt for " + templateName; }
        };
    }

    private static AgentConfig configWith(
            com.hirain.aiagent.core.component.ModelCaller caller,
            ToolExecutor toolExec) {
        JvmToolRegistry executionRegistry = defaultToolRegistry();
        executionRegistry.executor = toolExec;
        return AgentConfig.builder("chat")
                .modelName("qwen-turbo")
                .systemPromptTemplateName("prompts/system/assistant_default")
                .maxIterations(10).maxMemoryMessages(50)
                .memoryPolicy(AgentConfig.MemoryPolicy.PERSISTENT)
                .modelCaller(caller)
                .toolExecutor(toolExec)
                .toolRegistry(executionRegistry)
                .toolSubset(null)
                .postProcessors(List.of(new NoOpPostProcessor()))
                .terminator(new NoToolCallTerminator())
                .resultCollector(new DirectTextCollector())
                .timeout(java.time.Duration.ofSeconds(30))
                .build();
    }

    private static ToolSafetyEngine allowAllSafetyEngine() {
        return new ToolSafetyEngine(new VehicleStateMachine(), Map.of());
    }

    private static AgentRequest request(String text) {
        AgentRequest r = new AgentRequest();
        r.setInputType("TEXT");
        r.setText(text);
        r.setRequestId("req-1");
        r.setSessionId("conv-1");
        r.setUserId("user-a");
        r.setPersonaId("chat");
        r.setClientMessageId("cl-1");
        return r;
    }

    private static AgentRequest request(String text, String persona) {
        AgentRequest r = request(text);
        r.setPersonaId(persona);
        return r;
    }

    /** 创建含 DefaultToolGroupRegistry 全量工具的 JvmToolRegistry。 */
    private static JvmToolRegistry defaultToolRegistry() {
        JvmToolRegistry r = new JvmToolRegistry();
        for (String name : ToolGroupRegistry.defaultRegistry().allToolNames()) {
            r.specs.put(name, ToolSpecification.builder().name(name).description("tool " + name).build());
        }
        return r;
    }

    /** 创建装载了 PromptManager + JvmToolRegistry + memoryGateway + vehicleStatus 的 ContextOrchestrator。 */
    private static ContextOrchestrator createOrchestrator(PromptManager pm, JvmToolRegistry toolReg,
                                                          FakeMemoryGateway mg) {
        return ContextOrchestrator.defaultForText(ContextBuildInput.builder()
                .promptManager(pm)
                .memoryGateway(mg)
                .toolRegistry(toolReg)
                .toolGroupRegistry(ToolGroupRegistry.defaultRegistry())
                .vehicleStatusProvider(() -> "{\"speed\":0}")
                .build());
    }

    /** 创建 AgentRuntime（不含 TextAgentLoop，仅测试 prepare + session 层级）。 */
    private static AgentRuntime runtimeForSessionOnly(ContextOrchestrator orch, CapturingModelCaller caller, FakeMemoryGateway mg, ToolExecutor toolExec) {
        TextAgentLoopOrchestrator loop = new TextAgentLoopOrchestrator(
                configWith(caller, toolExec), mg, orch, allowAllSafetyEngine());
        return new AgentRuntime(
                (session, pr) -> loop.execute(session, pr), orch,
                (userId, reqSessionId, title, personaId, sourceApp) ->
                        reqSessionId != null ? reqSessionId : "gen-session",
                runtimeSession -> false);
    }

    // ═══════════════════════════════════════════
    // 测试
    // ═══════════════════════════════════════════

    @Test
    public void normalChat_correctMessageOrder() {
        FakeMemoryGateway mg = new FakeMemoryGateway();
        CapturingModelCaller caller = new CapturingModelCaller();
        ContextOrchestrator orch = createOrchestrator(testPrompt(), defaultToolRegistry(), mg);
        AgentRuntime runtime = runtimeForSessionOnly(orch, caller, mg, req -> "{}");

        AgentRequest req = request("你好");
        RequestSession session = runtime.startSession(req, null);
        assertNotNull("Session should not be null", session);
        RuntimeResult result = runtime.execute(session);
        assertTrue("Result should succeed, errorType=" + result.errorType() + " detail=" + result.errorDetail()
                + " success=" + result.success() + " output=" + result.output(), result.success());
        List<ChatRequest> captured = caller.capturedRequests();
        assertEquals(1, captured.size());
        ChatRequest chatReq = captured.get(0);
        List<ChatMessage> msgs = chatReq.messages();
        // 消息顺序：System → Context Data → SessionMemory → CurrentUser
        assertEquals("First message must be SystemMessage",
                dev.langchain4j.data.message.SystemMessage.class, msgs.get(0).getClass());
        // 最后一条消息必须包含当前用户输入
        ChatMessage lastMsg = msgs.get(msgs.size() - 1);
        assertTrue("Last message should be current user",
                lastMsg instanceof dev.langchain4j.data.message.UserMessage);
        assertTrue("Last message should contain the user's input",
                ((dev.langchain4j.data.message.UserMessage) lastMsg).singleText().contains("你好"));
    }

    @Test
    public void sessionSwitch_sessionB_noSessionAHistory() {
        // 同一个 FakeMemoryGateway，按 sessionId 隔离
        FakeMemoryGateway mg = new FakeMemoryGateway();
        CapturingModelCaller caller = new CapturingModelCaller();
        ContextOrchestrator orch = createOrchestrator(testPrompt(), defaultToolRegistry(), mg);
        TextAgentLoopOrchestrator loop = new TextAgentLoopOrchestrator(
                configWith(caller, req -> "{}"), mg, orch, allowAllSafetyEngine());
        AgentRuntime runtime = new AgentRuntime(
                (s, pr) -> loop.execute(s, pr), orch,
                (userId, reqSessionId, title, pId, sourceApp) ->
                        reqSessionId != null ? reqSessionId : "gen-session",
                runtimeSession -> false);

        // Session A: conv-A
        AgentRequest reqA = request("第一轮");
        reqA.setSessionId("conv-A");
        runtime.execute(runtime.startSession(reqA, null));
        assertTrue("Session A should have been called", caller.capturedRequests().size() >= 1);

        // Session B: 同一 gateway 不同 sessionId
        AgentRequest reqB = request("第二轮");
        reqB.setSessionId("conv-B");
        runtime.execute(runtime.startSession(reqB, null));

        List<ChatMessage> sessionBMsgs = caller.capturedRequests().get(1).messages();
        boolean hasSessionAUser = sessionBMsgs.stream()
                .anyMatch(m -> m instanceof dev.langchain4j.data.message.UserMessage
                        && ((dev.langchain4j.data.message.UserMessage) m).singleText().contains("第一轮"));
        assertFalse("Session B should not contain Session A's user message", hasSessionAUser);
    }

    @Test
    public void userSwitch_sameSession_shortTermRetained() {
        FakeMemoryGateway mg = new FakeMemoryGateway();
        CapturingModelCaller caller = new CapturingModelCaller();
        ContextOrchestrator orch = createOrchestrator(testPrompt(), defaultToolRegistry(), mg);
        TextAgentLoopOrchestrator loop = new TextAgentLoopOrchestrator(
                configWith(caller, req -> "{}"), mg, orch, allowAllSafetyEngine());
        AgentRuntime runtime = new AgentRuntime(
                (s, pr) -> loop.execute(s, pr), orch,
                (userId, reqSessionId, title, pId, sourceApp) ->
                        reqSessionId != null ? reqSessionId : "gen-session",
                runtimeSession -> false);

        // User A 写入
        AgentRequest reqA = request("用户A的消息");
        reqA.setUserId("user-a");
        runtime.execute(runtime.startSession(reqA, null));
        List<ChatMessage> firstSessionMsgs = new ArrayList<>(caller.capturedRequests().get(0).messages());

        // User B 同一 session
        AgentRequest reqB = request("用户B的消息");
        reqB.setUserId("user-b");
        reqB.setSessionId("conv-1");     // 同一 session
        runtime.execute(runtime.startSession(reqB, null));
        List<ChatMessage> secondSessionMsgs = caller.capturedRequests().get(1).messages();

        // 短期历史保留：User A 的消息应在 User B 的 ChatRequest 中出现
        boolean hasUserAHistory = secondSessionMsgs.stream()
                .anyMatch(m -> m instanceof dev.langchain4j.data.message.UserMessage
                        && ((dev.langchain4j.data.message.UserMessage) m).singleText().contains("用户A的消息"));
        assertTrue("User B's session should retain User A's history", hasUserAHistory);
        String secondRequestText = secondSessionMsgs.stream()
                .filter(m -> m instanceof dev.langchain4j.data.message.UserMessage)
                .map(m -> ((dev.langchain4j.data.message.UserMessage) m).singleText())
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue("User B's long-term memory should be injected", secondRequestText.contains("B_ONLY_MEMORY"));
        assertFalse("User A's long-term memory must not leak after user switch",
                secondRequestText.contains("A_ONLY_MEMORY"));
    }

    @Test
    public void personaSwitch_chatThenFriendlyThenConcise() {
        FakeMemoryGateway mg = new FakeMemoryGateway();
        CapturingModelCaller caller = new CapturingModelCaller();
        ContextOrchestrator orch = createOrchestrator(testPrompt(), defaultToolRegistry(), mg);
        TextAgentLoopOrchestrator loop = new TextAgentLoopOrchestrator(
                configWith(caller, req -> "{}"), mg, orch, allowAllSafetyEngine());
        AgentRuntime runtime = new AgentRuntime(
                (s, pr) -> loop.execute(s, pr), orch,
                (userId, reqSessionId, title, pId, sourceApp) ->
                        reqSessionId != null ? reqSessionId : "gen-session",
                runtimeSession -> false);

        // Chat persona
        runtime.execute(runtime.startSession(request("你好", "chat"), null));
        String chatSystem = extractSystemText(caller.capturedRequests().get(0));
        assertTrue("Chat system text should contain chat prompt",
                chatSystem.contains("system/assistant_default"));

        // Friendly persona
        runtime.execute(runtime.startSession(request("你好", "friendly"), null));
        String friendlySystem = extractSystemText(caller.capturedRequests().get(1));
        assertTrue("Friendly system text should contain friendly prompt",
                friendlySystem.contains("system/assistant_friendly"));

        // Concise persona
        runtime.execute(runtime.startSession(request("你好", "concise"), null));
        String conciseSystem = extractSystemText(caller.capturedRequests().get(2));
        assertTrue("Concise system text should contain concise prompt",
                conciseSystem.contains("system/assistant_concise"));
    }

    private static String extractSystemText(ChatRequest req) {
        for (ChatMessage m : req.messages()) {
            if (m instanceof dev.langchain4j.data.message.SystemMessage) {
                return ((dev.langchain4j.data.message.SystemMessage) m).text();
            }
        }
        return "";
    }

    @Test
    public void chatOnly_bypassesToolRegistry() {
        FakeMemoryGateway mg = new FakeMemoryGateway();
        CapturingModelCaller caller = new CapturingModelCaller();
        ContextOrchestrator orch = createOrchestrator(testPrompt(), defaultToolRegistry(), mg);
        TextAgentLoopOrchestrator loop = new TextAgentLoopOrchestrator(
                configWith(caller, req -> "{}"), mg, orch, allowAllSafetyEngine());

        RequestSession session = com.hirain.aiagent.context.TestRequestSessions.chatOnlySession(
                "req-1", "conv-1", "user-a", "chat", "cl-1", "你好");
        ContextPrepareResult pr = orch.prepare(session, ContextCancelChecker.neverCancelled());
        AgentResult result = loop.execute(session, pr);

        assertTrue(result.isSuccess());
    }

    @Test
    public void explicitAcControl_selectedToolSpecs() {
        ToolSpecification acSpec = ToolSpecification.builder().name("set_ac_status").description("空调控制").build();
        FakeMemoryGateway mg = new FakeMemoryGateway();
        CapturingModelCaller caller = new CapturingModelCaller();
        // 使用默认全量注册表验证 AC 工具解析
        ContextOrchestrator orch = createOrchestrator(testPrompt(), defaultToolRegistry(), mg);
        TextAgentLoopOrchestrator loop = new TextAgentLoopOrchestrator(
                configWith(caller, req -> "{}"), mg, orch, allowAllSafetyEngine());

        RequestSession session = com.hirain.aiagent.context.TestRequestSessions.textSession(
                "req-1", "conv-1", "user-a", "chat", "cl-1", "打开空调");
        ContextPrepareResult pr = orch.prepare(session, ContextCancelChecker.neverCancelled());
        AgentResult result = loop.execute(session, pr);

        assertTrue(result.isSuccess());
        ChatRequest chatReq = caller.capturedRequests().get(0);
        List<ToolSpecification> tools = chatReq.toolSpecifications();
        assertNotNull("ChatRequest must have toolSpecifications", tools);
        List<String> names = tools.stream().map(ToolSpecification::name).toList();
        assertTrue("AC tool should be selected", names.contains("set_ac_status"));
    }

    @Test
    public void allToolsFallback_reachesContextAndModelWithAllTools() {
        ToolGroupRegistry groupRegistry = ToolGroupRegistry.defaultRegistry();
        JvmToolRegistry toolRegistry = defaultToolRegistry();
        FakeMemoryGateway mg = new FakeMemoryGateway();
        CapturingModelCaller caller = new CapturingModelCaller();
        ContextOrchestrator orch = createOrchestrator(testPrompt(), toolRegistry, mg);
        TextAgentLoopOrchestrator loop = new TextAgentLoopOrchestrator(
                configWith(caller, req -> "{}"), mg, orch, allowAllSafetyEngine());
        AgentRuntime runtime = new AgentRuntime(
                (session, pr) -> loop.execute(session, pr),
                orch,
                null,
                groupRegistry,
                (text, sourceInputType) -> com.hirain.aiagent.intentrouter.IntentResult.unknown(
                        text, sourceInputType, "test_unknown"),
                (intent, text) -> ToolGroupSelectionResult.allToolsFallback(
                        groupRegistry, "test_all_tools_fallback"),
                () -> "req-all-tools",
                () -> 1000L,
                runtimeSession -> false);

        RuntimeResult result = runtime.execute(runtime.startSession(request("模糊指令"), null));

        assertTrue("Demo all-tools fallback request must remain executable", result.success());
        assertEquals(1, caller.capturedRequests().size());
        List<String> names = caller.capturedRequests().get(0).toolSpecifications().stream()
                .map(ToolSpecification::name)
                .toList();
        assertEquals("All-tools fallback must expose every registered tool exactly once",
                toolRegistry.size(), names.size());
        assertTrue(names.containsAll(toolRegistry.specs.keySet()));
    }

    @Test
    public void promptOrMemoryFailure_modelNotCalled() {
        // PromptManager=null → PromptContextProvider 返回 FAILED, prepare 失败
        ContextOrchestrator orch = ContextOrchestrator.defaultForText(
                ContextBuildInput.builder()
                        .toolGroupRegistry(ToolGroupRegistry.defaultRegistry())
                        .build());
        FakeMemoryGateway mg = new FakeMemoryGateway();
        CapturingModelCaller caller = new CapturingModelCaller();
        AgentRuntime runtime = new AgentRuntime(
                (session, pr) -> { throw new RuntimeException("should not be called"); },
                orch,
                (userId, reqSessionId, title, pId, sourceApp) -> "gen-session",
                runtimeSession -> false);

        AgentRequest req = request("hi");
        RuntimeResult result = runtime.execute(runtime.startSession(req, null));
        assertEquals("Should fail when PromptManager is null",
                "REQUIRED_PROVIDER_FAILED", result.errorType());
        assertTrue("Model should not be called when prepare fails",
                caller.capturedRequests().isEmpty());
    }

    @Test
    public void budgetExceeded_modelToolMemoryAndCompactionRemainUntouched() {
        ToolSpecification longSpec = ToolSpecification.builder()
                .name("very_long_tool")
                .description("x".repeat(10000))
                .build();
        FakeMemoryGateway mg = new FakeMemoryGateway();
        ContextOrchestrator orch = createOrchestrator(testPrompt(), new JvmToolRegistry(longSpec), mg);
        CapturingModelCaller caller = new CapturingModelCaller();
        java.util.concurrent.atomic.AtomicInteger toolCalls = new java.util.concurrent.atomic.AtomicInteger();
        RequestSession session = com.hirain.aiagent.context.TestRequestSessions.chatOnlySession(
                "req-1", "conv-1", "user-a", "chat", "cl-1", "hi");
        ContextPrepareResult pr = orch.prepare(session, ContextCancelChecker.neverCancelled());
        com.hirain.aiagent.context.ContextBudgetPolicy tinyBudget =
                new com.hirain.aiagent.context.ContextBudgetPolicy(101, 50, 50);
        com.hirain.aiagent.context.ContextAssemblyGateway tinyBudgetGateway = request -> orch.assemble(
                new com.hirain.aiagent.context.ContextAssemblyRequest(
                        request.frame(), request.iteration(), tinyBudget,
                        request.cancelChecker(), request.compressionAlreadyAttempted(), request.session()));
        TextAgentLoopOrchestrator loop = new TextAgentLoopOrchestrator(
                configWith(caller, req -> {
                    toolCalls.incrementAndGet();
                    return "{}";
                }), mg, tinyBudgetGateway, allowAllSafetyEngine());

        AgentResult result = loop.execute(session, pr);

        assertEquals(AgentResult.ErrorType.CONTEXT_BUDGET_EXCEEDED, result.errorType());
        assertTrue("Model must not be called when budget is exceeded", caller.capturedRequests().isEmpty());
        assertEquals("Tool executor must not run when budget is exceeded", 0, toolCalls.get());
        ChatMemory sessionMemory = mg.perSessionChatMemory.get("conv-1");
        assertTrue("SessionMemory must remain unchanged when budget is exceeded",
                sessionMemory == null || sessionMemory.messages().isEmpty());
        assertEquals("Compaction plan should not be called", 0, mg.planCallCount);
        assertEquals("Compaction execute should not be called", 0, mg.executeCallCount);
    }

    @Test
    public void budgetWithinNormal_modelCalledOnce() {
        // 正常预算：通过 TextAgentLoopOrchestrator 验证模型被调用
        FakeMemoryGateway mg = new FakeMemoryGateway();
        CapturingModelCaller caller = new CapturingModelCaller();
        ContextOrchestrator orch = createOrchestrator(testPrompt(), defaultToolRegistry(), mg);
        TextAgentLoopOrchestrator loop = new TextAgentLoopOrchestrator(
                configWith(caller, req -> "{}"), mg, orch, allowAllSafetyEngine());

        RequestSession session = com.hirain.aiagent.context.TestRequestSessions.textSession(
                "req-1", "conv-1", "user-a", "chat", "cl-1", "你好");
        ContextPrepareResult pr = orch.prepare(session, ContextCancelChecker.neverCancelled());
        AgentResult result = loop.execute(session, pr);

        assertTrue(result.isSuccess());
        assertEquals("Model should be called exactly once when within budget",
                1, caller.capturedRequests().size());
        assertEquals("Compaction plan should not be called", 0, mg.planCallCount);
        assertEquals("Compaction execute should not be called", 0, mg.executeCallCount);
    }

    @Test
    public void multiToolCancel_cancelledToolResultWritten() {
        ToolSpecification t1 = ToolSpecification.builder().name("tool_a").description("first").build();
        ToolSpecification t2 = ToolSpecification.builder().name("tool_b").description("second").build();
        FakeMemoryGateway mg = new FakeMemoryGateway();
        java.util.concurrent.atomic.AtomicBoolean cancelAfterFirst = new java.util.concurrent.atomic.AtomicBoolean(false);

        // 自定 ModelCaller：返回含 2 个 ToolExecutionRequest 的 AiMessage
        com.hirain.aiagent.core.component.ModelCaller toolCaller = req -> dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(AiMessage.from(
                        ToolExecutionRequest.builder().id("r1").name("tool_a").arguments("{}").build(),
                        ToolExecutionRequest.builder().id("r2").name("tool_b").arguments("{}").build()))
                .build();

        AgentConfig config = configWith(toolCaller, req -> {
                    if ("tool_a".equals(req.name())) cancelAfterFirst.set(true);
                    return "{\"ok\":true}";
                });

        ContextOrchestrator orch = createOrchestrator(testPrompt(), defaultToolRegistry(), mg);
        TextAgentLoopOrchestrator loop = new TextAgentLoopOrchestrator(
                config, mg, orch, allowAllSafetyEngine());

        RequestSession session = com.hirain.aiagent.context.TestRequestSessions.textSession(
                "req-1", "conv-1", "user-a", "chat", "cl-1", "do two things");
        ContextPrepareResult pr = orch.prepare(session, () -> cancelAfterFirst.get());
        AgentResult result = loop.execute(session, pr);
        assertEquals("Should cancel during multi-tool", AgentResult.ErrorType.CANCELLED, result.errorType());

        ChatMemory sessionMem = mg.perSessionChatMemory.get("conv-1");
        List<ChatMessage> msgs = sessionMem != null ? sessionMem.messages() : mg.chatMemory.messages();
        long toolResultCount = msgs.stream()
                .filter(m -> m instanceof ToolExecutionResultMessage).count();
        assertEquals("Two ToolResults should be in chatMemory (1 real, 1 cancelled)", 2, toolResultCount);
        boolean hasCancelled = msgs.stream().anyMatch(m ->
                m instanceof ToolExecutionResultMessage
                        && ((ToolExecutionResultMessage) m).text().contains("CANCELLED"));
        assertTrue("Cancelled tool should have CANCELLED text", hasCancelled);
    }

    @Test
    public void chatOnly_noVehicleState() {
        FakeMemoryGateway mg = new FakeMemoryGateway();
        CapturingModelCaller caller = new CapturingModelCaller();
        ContextOrchestrator orch = createOrchestrator(testPrompt(), defaultToolRegistry(), mg);
        TextAgentLoopOrchestrator loop = new TextAgentLoopOrchestrator(
                configWith(caller, req -> "{}"), mg, orch, allowAllSafetyEngine());

        RequestSession session = com.hirain.aiagent.context.TestRequestSessions.chatOnlySession(
                "req-1", "conv-1", "user-a", "chat", "cl-1", "你好");
        ContextPrepareResult pr = orch.prepare(session, ContextCancelChecker.neverCancelled());
        AgentResult result = loop.execute(session, pr);

        assertTrue(result.isSuccess());
        List<ChatMessage> msgs = caller.capturedRequests().get(0).messages();
        // CHAT_ONLY 不应包含车辆 JSON
        boolean hasSpeed = msgs.stream().anyMatch(m ->
                m.toString().contains("\"speed\""));
        assertFalse("CHAT_ONLY should not contain vehicle JSON", hasSpeed);
    }

    @Test
    public void acControl_containsVehicleState() {
        FakeMemoryGateway mg = new FakeMemoryGateway();
        CapturingModelCaller caller = new CapturingModelCaller();
        ContextOrchestrator orch = createOrchestrator(testPrompt(), defaultToolRegistry(), mg);
        TextAgentLoopOrchestrator loop = new TextAgentLoopOrchestrator(
                configWith(caller, req -> "{}"), mg, orch, allowAllSafetyEngine());

        RequestSession session = com.hirain.aiagent.context.TestRequestSessions.textSession(
                "req-1", "conv-1", "user-a", "chat", "cl-1", "打开空调");
        ContextPrepareResult pr = orch.prepare(session, ContextCancelChecker.neverCancelled());
        AgentResult result = loop.execute(session, pr);

        assertTrue(result.isSuccess());
        List<ChatMessage> msgs = caller.capturedRequests().get(0).messages();
        // VEHICLE_AC 应包含车辆状态（speed）
        boolean hasSpeed = msgs.stream().anyMatch(m ->
                m.toString().contains("\"speed\""));
        assertTrue("VEHICLE_AC should contain vehicle state", hasSpeed);
    }
}
