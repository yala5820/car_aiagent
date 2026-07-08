package com.hirain.aiagent.context;

import com.hirain.aiagent.context.provider.MemoryContextProvider;
import com.hirain.aiagent.context.provider.PersonaContextProvider;
import com.hirain.aiagent.context.provider.PromptContextProvider;
import com.hirain.aiagent.context.provider.RuntimeContextProvider;
import com.hirain.aiagent.context.provider.TimeContextProvider;
import com.hirain.aiagent.context.provider.UserInputContextProvider;
import com.hirain.aiagent.context.provider.VehicleStateContextProvider;
import com.hirain.aiagent.intentrouter.IntentTag;
import com.hirain.aiagent.runtime.RequestSession;
import com.hirain.aiagent.toolgroup.ToolGroupId;
import com.hirain.aiagent.toolgroup.ToolGroupRegistry;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ContextOrchestratorTest {

    @Test
    public void basicProviders_createRuntimePersonaAndInputSections() {
        RequestSession session = TestRequestSessions.textSession(
                "req-1", "conv-1", "user-a", "friendly", "client-9", "打开空调");
        ContextBuildInput input = ContextBuildInput.builder()
                .mode(ContextMode.HYBRID_EXTRA_CONTEXT)
                .build();

        ContextProviderResult runtime =
                new RuntimeContextProvider().provide(session, input);
        ContextProviderResult persona =
                new PersonaContextProvider().provide(session, input);
        ContextProviderResult userInput =
                new UserInputContextProvider().provide(session, input);

        assertTrue(runtime.success());
        assertTrue(persona.success());
        assertTrue(userInput.success());
        assertEquals(ContextSectionType.RUNTIME, runtime.section().type());
        assertEquals("friendly", persona.section().metadata().get("persona_id"));
        assertEquals("打开空调", userInput.section().metadata().get("raw_user_input"));
    }

    @Test
    public void hybridMode_keepsExistingPreprocessorOwnedSectionsNonRenderable() {
        RequestSession session = TestRequestSessions.textSession(
                "req-1", "conv-1", "user-a", "chat", "client-1", "你好");
        ContextBuildInput input = ContextBuildInput.builder()
                .mode(ContextMode.HYBRID_EXTRA_CONTEXT)
                .vehicleStatusProvider(() -> "{\"speed\":0}")
                .timeProvider(() -> 1000L)
                .build();

        assertFalse(new MemoryContextProvider().provide(session, input).section().renderable());
        assertFalse(new VehicleStateContextProvider().provide(session, input).section().renderable());
        assertFalse(new TimeContextProvider().provide(session, input).section().renderable());
        assertFalse(new PromptContextProvider().provide(session, input).section().renderable());
    }

    @Test
    public void build_hybridModeCreatesFrameBeforeAgentLoop() {
        RequestSession session = TestRequestSessions.textSession(
                "req-1", "conv-1", "user-a", "chat", "client-1", "打开空调");
        ContextOrchestrator orchestrator =
                ContextOrchestrator.defaultForText(ContextBuildInput.builder()
                        .mode(ContextMode.HYBRID_EXTRA_CONTEXT)
                        .toolGroupRegistry(ToolGroupRegistry.defaultRegistry())
                        .vehicleStatusProvider(() -> "{\"speed\":0}")
                        .timeProvider(() -> 1000L)
                        .build());

        ContextBuildResult result = orchestrator.build(session);

        assertTrue(result.success());
        assertEquals("req-1", result.frame().requestId());
        assertEquals(ContextMode.HYBRID_EXTRA_CONTEXT, result.frame().mode());
        assertTrue(result.frame().renderedExtraContext().contains("【运行时上下文】"));
        assertTrue(result.frame().renderedExtraContext().contains("【意图上下文】"));
        assertTrue(result.frame().renderedExtraContext().contains("【工具组上下文】"));
        assertFalse(result.frame().renderedExtraContext().contains("当前时间："));
        assertFalse(result.frame().renderedExtraContext().contains("【长期记忆】"));
    }

    @Test
    public void fullContextMode_delegatesToHybridAndRecordsDeferred() {
        ContextBuildResult result = ContextOrchestrator.defaultForText(
                ContextBuildInput.builder().mode(ContextMode.FULL_CONTEXT).build())
                .build(TestRequestSessions.textSession(
                        "req-1", "conv-1", "user-a", "chat", "client-1", "你好"));

        assertTrue(result.fallbackUsed());
        assertTrue(result.frame().debugInfo().fallbackProviders()
                .contains("full_context_deferred"));
    }

    @Test
    public void build_nullSessionReturnsFallback() {
        ContextBuildResult result = ContextOrchestrator.defaultForText(
                ContextBuildInput.builder().mode(ContextMode.HYBRID_EXTRA_CONTEXT).build())
                .build(null);

        assertTrue(result.fallbackUsed());
        assertEquals("session_is_null", result.errorReason());
    }

    @Test
    public void build_preservesUserSessionPersonaAndClientMessageId() {
        RequestSession session = TestRequestSessions.textSession(
                "req-1", "conv-42", "driver-a", "concise", "client-42", "你好");
        ContextBuildResult result = ContextOrchestrator.defaultForText(
                ContextBuildInput.builder().mode(ContextMode.HYBRID_EXTRA_CONTEXT).build())
                .build(session);

        ContextFrame frame = result.frame();
        assertEquals("driver-a", frame.userId());
        assertEquals("conv-42", frame.sessionId());
        assertEquals("concise", frame.personaId());
        assertEquals("client-42", frame.clientMessageId());
    }

    @Test
    public void build_containsIntentToolGroupAndSelectedTools() {
        ContextFrame frame = ContextOrchestrator.defaultForText(
                ContextBuildInput.builder()
                        .mode(ContextMode.HYBRID_EXTRA_CONTEXT)
                        .toolGroupRegistry(ToolGroupRegistry.defaultRegistry())
                        .build())
                .build(TestRequestSessions.textSession(
                        "req-1", "conv-1", "user-a", "chat", "client-1", "打开空调"))
                .frame();

        assertEquals(IntentTag.VEHICLE_AC, frame.intentResult().intentTag());
        assertTrue(frame.selectedGroupIds().contains(ToolGroupId.AC_GROUP));
        assertEquals(List.of("set_ac_status"), frame.selectedToolNames());
    }

    @Test
    public void renderedExtraContext_doesNotDuplicateExistingAgentLoopContexts() {
        ContextFrame frame = ContextOrchestrator.defaultForText(
                ContextBuildInput.builder()
                        .mode(ContextMode.HYBRID_EXTRA_CONTEXT)
                        .toolGroupRegistry(ToolGroupRegistry.defaultRegistry())
                        .vehicleStatusProvider(() -> "{\"speed\":0}")
                        .timeProvider(() -> 1000L)
                        .build())
                .build(TestRequestSessions.textSession(
                        "req-1", "conv-1", "user-a", "chat", "client-1", "你好"))
                .frame();

        String rendered = frame.renderedExtraContext();
        assertFalse(rendered.contains("当前时间："));
        assertFalse(rendered.contains("【长期记忆】"));
        assertFalse(rendered.contains("车辆状态："));
        assertFalse(rendered.contains("你是"));
    }

    @Test
    public void build_tokenEstimateIsWrittenToFrame() {
        ContextFrame frame = ContextOrchestrator.defaultForText(
                ContextBuildInput.builder()
                        .mode(ContextMode.HYBRID_EXTRA_CONTEXT)
                        .toolGroupRegistry(ToolGroupRegistry.defaultRegistry())
                        .build())
                .build(TestRequestSessions.textSession(
                        "req-1", "conv-1", "user-a", "chat", "client-1", "打开空调"))
                .frame();

        assertTrue(frame.tokenEstimate() > 0);
        int expected = ContextBudgetManager.defaultBudget().estimateTokens(
                frame.renderedExtraContext());
        assertEquals(expected, frame.tokenEstimate());
    }

    @Test
    public void smallBudget_trimsRenderedExtraAndMarksTruncation() {
        ContextBudgetManager smallBudget = new ContextBudgetManager(
                50, 200, 500, 150);
        ContextOrchestrator orchestrator = ContextOrchestrator.defaultForText(
                ContextBuildInput.builder()
                        .mode(ContextMode.HYBRID_EXTRA_CONTEXT)
                        .toolGroupRegistry(ToolGroupRegistry.defaultRegistry())
                        .budgetManager(smallBudget)
                        .build());
        ContextFrame frame = orchestrator.build(
                TestRequestSessions.textSession(
                        "req-1", "conv-1", "user-a", "chat", "client-1", "你好"))
                .frame();

        assertTrue(frame.renderedExtraContext().length() <= 150);
        boolean anyTruncated = frame.sections().stream()
                .anyMatch(ContextSection::truncated);
        assertTrue(anyTruncated);
    }
}
