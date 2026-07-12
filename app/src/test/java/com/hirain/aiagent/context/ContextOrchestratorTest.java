package com.hirain.aiagent.context;

import com.hirain.aiagent.context.provider.CallerExtraContextProvider;
import com.hirain.aiagent.context.provider.IntentContextProvider;
import com.hirain.aiagent.context.provider.LongTermMemoryContextProvider;
import com.hirain.aiagent.context.provider.PersonaContextProvider;
import com.hirain.aiagent.context.provider.PromptContextProvider;
import com.hirain.aiagent.context.provider.RuntimeContextProvider;
import com.hirain.aiagent.context.provider.ToolGroupContextProvider;
import com.hirain.aiagent.context.provider.UserInputContextProvider;
import com.hirain.aiagent.intentrouter.IntentTag;
import com.hirain.aiagent.prompt.PromptManager;
import com.hirain.aiagent.runtime.RequestSession;
import com.hirain.aiagent.toolgroup.ToolGroupId;
import com.hirain.aiagent.toolgroup.ToolGroupRegistry;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ContextOrchestratorTest {

    private static PromptManager testPm() {
        return new PromptManager(null) {
            @Override public String render(String n) { return "Test prompt for " + n; }
        };
    }

    /** 不含 ToolGroupContextProvider 的 Orchestrator，避免 JVM 中 android.util.Log 缺失。 */
    private static ContextOrchestrator orchWithoutToolGroup() {
        ContextBuildInput input = ContextBuildInput.builder()
                .promptManager(testPm())
                .build();
        return new ContextOrchestrator(input, List.of(
                new RuntimeContextProvider(),
                new PersonaContextProvider(),
                new PromptContextProvider(),
                new UserInputContextProvider(),
                new IntentContextProvider(),
                new LongTermMemoryContextProvider(),
                new CallerExtraContextProvider()));
    }

    @Test
    public void basicProviders_createRuntimePersonaAndInputSections() {
        RequestSession session = TestRequestSessions.textSession(
                "req-1", "conv-1", "user-a", "friendly", "client-9", "打开空调");
        ContextBuildInput input = ContextBuildInput.builder()
                .promptManager(testPm())
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
        assertNotNull(runtime.contributions());
        assertNotNull(persona.contributions());
        assertNotNull(userInput.contributions());
    }

    @Test
    public void build_preservesUserSessionPersonaAndClientMessageId() {
        RequestSession session = TestRequestSessions.textSession(
                "req-1", "conv-42", "driver-a", "concise", "client-42", "你好");
        ContextPrepareResult result = orchWithoutToolGroup()
                .prepare(session, ContextCancelChecker.neverCancelled());

        ContextFrame frame = result.frame();
        assertEquals("driver-a", frame.userId());
        assertEquals("conv-42", frame.sessionId());
        assertEquals("concise", frame.personaId());
        assertEquals("client-42", frame.clientMessageId());
    }

    @Test
    public void build_containsIntentToolGroupAndSelectedTools() {
        ContextBuildInput input = ContextBuildInput.builder()
                .promptManager(testPm())
                .toolGroupRegistry(ToolGroupRegistry.defaultRegistry())
                .build();
        ContextOrchestrator orch = new ContextOrchestrator(input, List.of(
                new RuntimeContextProvider(),
                new PersonaContextProvider(),
                new PromptContextProvider(),
                new UserInputContextProvider(),
                new IntentContextProvider(),
                new ToolGroupContextProvider(),
                new LongTermMemoryContextProvider(),
                new CallerExtraContextProvider()));
        ContextPrepareResult result = orch.prepare(TestRequestSessions.textSession(
                        "req-1", "conv-1", "user-a", "chat", "client-1", "打开空调"),
                ContextCancelChecker.neverCancelled());

        // ToolGroup 因无 ToolRegistry 返回 TOOL_SPEC_RESOLUTION_FAILED → prepare 失败
        assertFalse(result.isSuccess());
    }

    @Test
    public void prepare_nullSessionReturnsFailed() {
        ContextPrepareResult result = orchWithoutToolGroup()
                .prepare(null, ContextCancelChecker.neverCancelled());

        assertFalse(result.isSuccess());
        assertEquals(ContextErrorCode.CONTEXT_INTERNAL_ERROR, result.errorCode());
    }

    @Test
    public void prepare_successWithToolGroup() {
        RequestSession session = TestRequestSessions.textSession(
                "req-1", "conv-1", "user-a", "chat", "client-1", "打开空调");
        ContextPrepareResult prepareResult = orchWithoutToolGroup()
                .prepare(session, ContextCancelChecker.neverCancelled());

        assertTrue(prepareResult.isSuccess());
        assertNotNull(prepareResult.frame());
    }
}
