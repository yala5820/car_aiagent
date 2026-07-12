package com.hirain.aiagent.context;

import com.hirain.aiagent.context.provider.CallerExtraContextProvider;
import com.hirain.aiagent.context.provider.LongTermMemoryContextProvider;
import com.hirain.aiagent.context.provider.PromptContextProvider;
import com.hirain.aiagent.context.provider.RuntimeContextProvider;
import com.hirain.aiagent.context.provider.SessionMemoryContextProvider;
import com.hirain.aiagent.context.provider.ToolGroupContextProvider;
import com.hirain.aiagent.context.provider.UserInputContextProvider;
import com.hirain.aiagent.context.provider.VehicleStateContextProvider;
import com.hirain.aiagent.prompt.PromptManager;
import com.hirain.aiagent.runtime.RequestSession;
import com.hirain.aiagent.toolgroup.ToolGroupRegistry;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ContextProviderRequiredPolicyTest {

    // ── PromptManager 测试用 fake ──

    private static PromptManager testPrompt(String templateName) {
        return new PromptManager(null) {
            @Override public String render(String name) {
                if (!name.equals(templateName)) {
                    throw new IllegalArgumentException("Unexpected template: " + name);
                }
                return "System prompt for " + name;
            }
        };
    }

    private static PromptManager failingPrompt(String message) {
        return new PromptManager(null) {
            @Override public String render(String name) {
                throw new RuntimeException(message);
            }
        };
    }

    // ═══════════════════════════════════════════
    // required() 策略测试
    // ═══════════════════════════════════════════

    @Test
    public void promptProvider_alwaysRequired() {
        assertTrue(new PromptContextProvider().required(
                TestRequestSessions.textSession("r","c","u","chat","cl","hi"),
                ContextBuildInput.builder().build()));
    }

    @Test
    public void userInputProvider_alwaysRequired() {
        assertTrue(new UserInputContextProvider().required(
                TestRequestSessions.textSession("r","c","u","chat","cl","hi"),
                ContextBuildInput.builder().build()));
    }

    @Test
    public void runtimeProvider_alwaysRequired() {
        assertTrue(new RuntimeContextProvider().required(
                TestRequestSessions.textSession("r","c","u","chat","cl","hi"),
                ContextBuildInput.builder().build()));
    }

    @Test
    public void sessionMemoryProvider_alwaysRequired() {
        assertTrue(new SessionMemoryContextProvider().required(
                TestRequestSessions.textSession("r","c","u","chat","cl","hi"),
                ContextBuildInput.builder().build()));
    }

    @Test
    public void toolProvider_acRequest_required() {
        RequestSession session = TestRequestSessions.textSession(
                "r","c","u","chat","cl","打开空调");
        ContextBuildInput input = ContextBuildInput.builder()
                .toolGroupRegistry(ToolGroupRegistry.defaultRegistry())
                .build();
        assertTrue(new ToolGroupContextProvider().required(session, input));
    }

    // ═══════════════════════════════════════════
    // PromptContextProvider 内容测试
    // ═══════════════════════════════════════════

    @Test
    public void promptProvider_nullManager_returnsFailed() {
        RequestSession s = TestRequestSessions.textSession("r","c","u","chat","cl","hi");
        ContextBuildInput input = ContextBuildInput.builder().build(); // promptManager=null
        ContextProviderResult result = new PromptContextProvider().provide(s, input);
        assertFalse(result.success());
    }

    @Test
    public void promptProvider_renderException_returnsFailed() {
        RequestSession s = TestRequestSessions.textSession("r","c","u","chat","cl","hi");
        ContextBuildInput input = ContextBuildInput.builder()
                .promptManager(failingPrompt("render error"))
                .build();
        ContextProviderResult result = new PromptContextProvider().provide(s, input);
        assertFalse(result.success());
    }

    @Test
    public void promptProvider_emptyPrompt_returnsFailed() {
        RequestSession s = TestRequestSessions.textSession("r","c","u","chat","cl","hi");
        PromptManager pm = new PromptManager(null) {
            @Override public String render(String name) { return ""; }
        };
        ContextBuildInput input = ContextBuildInput.builder()
                .promptManager(pm)
                .build();
        ContextProviderResult result = new PromptContextProvider().provide(s, input);
        assertFalse(result.success());
    }

    @Test
    public void promptProvider_chatFriendlyConcise_rendersCorrectly() {
        String[][] personas = {{"chat", "system/assistant_default"},
                               {"friendly", "system/assistant_friendly"},
                               {"concise", "system/assistant_concise"}};
        for (String[] p : personas) {
            String personaId = p[0];
            String templateName = p[1];
            RequestSession s = TestRequestSessions.textSession("r","c","u", personaId, "cl","hi");
            ContextBuildInput input = ContextBuildInput.builder()
                    .promptManager(testPrompt(templateName))
                    .build();
            ContextProviderResult result = new PromptContextProvider().provide(s, input);
            assertTrue("Persona " + personaId + " should succeed", result.success());
            assertFalse(result.contributions().isEmpty());
            TextContextContribution contrib = (TextContextContribution) result.contributions().get(0);
            assertEquals("System prompt for " + templateName, contrib.content());
        }
    }

    // ═══════════════════════════════════════════
    // ToolGroupContextProvider 内容测试
    // ═══════════════════════════════════════════

    @Test
    public void toolProvider_chatOnly_returnsEmpty() {
        // 使用 CHAT_ONLY selection
        RequestSession s = TestRequestSessions.chatOnlySession("r","c","u","chat","cl","hi");
        ContextBuildInput input = ContextBuildInput.builder().build();
        ContextProviderResult result = new ToolGroupContextProvider().provide(s, input);
        assertTrue(result.success());
        assertFalse(result.contributions().isEmpty());
        ToolContextContribution c = (ToolContextContribution) result.contributions().get(0);
        assertEquals(ToolContextContribution.MODE_NONE, c.selectionMode());
        assertTrue(c.toolSpecifications().isEmpty());
    }

    // ═══════════════════════════════════════════
    // CallerExtra & LongTermMemory 基础测试
    // ═══════════════════════════════════════════

    @Test
    public void callerExtraProvider_untrustedData() {
        RequestSession s = TestRequestSessions.textSession("r","c","u","chat","cl","hi");
        ContextBuildInput input = ContextBuildInput.builder().build();
        ContextProviderResult result = new CallerExtraContextProvider().provide(s, input);
        assertTrue(result.success());
        assertFalse(result.contributions().isEmpty());
        TextContextContribution c = (TextContextContribution) result.contributions().get(0);
        assertEquals(ContextTrustLevel.UNTRUSTED_DATA, c.trustLevel());
    }
}
