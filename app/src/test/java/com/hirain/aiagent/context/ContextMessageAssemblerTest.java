package com.hirain.aiagent.context;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ContextMessageAssemblerTest {

    private static ContextFrame createFrameWithContributions(List<ContextContribution> contributions) {
        return new ContextFrameBuilder()
                .requestId("req-1")
                .clientMessageId("client-1")
                .userId("user-a")
                .sessionId("session-1")
                .personaId("chat")
                .inputType("TEXT")
                .rawUserInput("你好")
                .normalizedUserInput("你好")
                .contributions(contributions)
                .build();
    }

    private static TextContextContribution systemPrompt(String content) {
        return new TextContextContribution(
                "prompt", ContextVisibility.MODEL_VISIBLE, ContextTrustLevel.TRUSTED_SYSTEM,
                ContextPriority.CRITICAL, ContextLifecycle.REQUEST_STATIC, true,
                "PromptContextProvider", TextContextContribution.TARGET_SYSTEM,
                content, Map.of());
    }

    private static TextContextContribution contextData(String key, String content,
                                                        ContextTrustLevel trust) {
        return new TextContextContribution(
                key, ContextVisibility.MODEL_VISIBLE, trust,
                ContextPriority.NORMAL, ContextLifecycle.REQUEST_STATIC, false,
                "TestProvider", TextContextContribution.TARGET_CONTEXT_DATA,
                content, Map.of());
    }

    private static MessageContextContribution sessionMemory(List<ChatMessage> msgs) {
        return new MessageContextContribution(
                "session_memory", ContextVisibility.MODEL_VISIBLE, ContextTrustLevel.TRUSTED_DATA,
                ContextPriority.HIGH, ContextLifecycle.ITERATION_DYNAMIC, true,
                "SessionMemoryContextProvider", MessageContextContribution.SOURCE_SESSION_MEMORY,
                msgs, Map.of());
    }

    // ── 正常装配 ──

    @Test
    public void systemPromptOnly_createsSingleSystemMessage() {
        List<ChatMessage> history = new ArrayList<>();
        history.add(UserMessage.from("你好"));
        history.add(AiMessage.from("你好！有什么可以帮你的？"));
        ContextFrame frame = createFrameWithContributions(List.of(
                systemPrompt("You are a car assistant"),
                sessionMemory(history)));

        ContextAssemblyResult result = ContextMessageAssembler.assemble(frame, null, null);

        assertTrue(result.success());
        assertEquals(3, result.messages().size());
        assertTrue(result.messages().get(0) instanceof SystemMessage);
        assertTrue(result.messages().get(0).toString().contains("car assistant"));
        // session messages follow
        assertTrue(result.messages().get(1).toString().contains("你好"));
    }

    @Test
    public void systemAndContextData_producesCorrectOrder() {
        ContextFrame frame = createFrameWithContributions(List.of(
                systemPrompt("You are a car assistant"),
                contextData("long_term_memory", "用户喜欢温度24度",
                        ContextTrustLevel.UNTRUSTED_DATA),
                contextData("vehicle_state", "{\"speed\":0,\"ac\":\"on\"}",
                        ContextTrustLevel.TRUSTED_DATA),
                sessionMemory(List.of())));

        ContextAssemblyResult result = ContextMessageAssembler.assemble(frame, null, null);

        assertTrue(result.success());
        List<ChatMessage> messages = result.messages();
        assertEquals(2, messages.size());
        assertTrue(messages.get(0) instanceof SystemMessage);
        assertTrue(messages.get(1) instanceof UserMessage);
        String contextText = messages.get(1).toString();
        assertTrue(contextText.contains("温度24度"));
        assertTrue(contextText.contains("speed"));
    }

    @Test
    public void sessionMessages_appendedAfterContextData() {
        List<ChatMessage> history = new ArrayList<>();
        history.add(UserMessage.from("打开空调"));
        history.add(AiMessage.from(dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                .id("req-1").name("set_ac_status").arguments("{}").build()));
        history.add(new ToolExecutionResultMessage("req-1", "set_ac_status", "success"));

        ContextFrame frame = createFrameWithContributions(List.of(
                systemPrompt("You are a car assistant"),
                sessionMemory(history)));

        ContextAssemblyResult result = ContextMessageAssembler.assemble(frame, null, null);

        assertTrue(result.success());
        assertEquals(4, result.messages().size());
        assertTrue(result.messages().get(0) instanceof SystemMessage);
        assertTrue(result.messages().get(3) instanceof ToolExecutionResultMessage);
    }

    @Test
    public void currentUserAppearsOnlyOnce() {
        List<ChatMessage> history = new ArrayList<>();
        history.add(UserMessage.from("当前用户消息")); // 这条已经在 SessionMemory 中
        history.add(AiMessage.from("好的"));
        ContextFrame frame = createFrameWithContributions(List.of(
                systemPrompt("You are a car assistant"),
                sessionMemory(history)));

        ContextAssemblyResult result = ContextMessageAssembler.assemble(frame, null, null);

        assertTrue(result.success());
        // 系统 + 当前用户 + AiMessage = 3 条，用户消息不应重复
        assertEquals(3, result.messages().size());
        long userCount = result.messages().stream()
                .filter(m -> m instanceof UserMessage).count();
        assertEquals("Current user should appear exactly once", 1, userCount);
    }

    // ── 失败场景 ──

    @Test
    public void nullFrame_returnsFailure() {
        ContextAssemblyResult result = ContextMessageAssembler.assemble(null, null, null);

        assertFalse(result.success());
        assertNotNull(result.errorCode());
    }

    @Test
    public void toolNameConflict_differentSchema_fails() {
        ToolSpecification spec1 = ToolSpecification.builder()
                .name("get_weather").description("Get weather").build();
        ToolSpecification spec2 = ToolSpecification.builder()
                .name("get_weather").description("Get weather forecast for a location").build();

        List<ContextContribution> contributions = new ArrayList<>();
        contributions.add(systemPrompt("You are a weather bot"));
        contributions.add(new ToolContextContribution(
                "tool", ContextVisibility.MODEL_VISIBLE, ContextTrustLevel.TRUSTED_DATA,
                ContextPriority.HIGH, ContextLifecycle.REQUEST_STATIC, true,
                "ToolProvider", ToolContextContribution.MODE_SELECTED,
                List.of(spec1, spec2), Map.of()));
        contributions.add(sessionMemory(List.of()));
        ContextFrame frame = createFrameWithContributions(contributions);

        ContextAssemblyResult result = ContextMessageAssembler.assemble(frame, null, null);

        assertFalse("Different schemas for same tool name should fail", result.success());
        assertEquals(ContextErrorCode.MESSAGE_SEQUENCE_INVALID, result.errorCode());
    }

    @Test
    public void noSystemMessage_fails() {
        ContextFrame frame = createFrameWithContributions(List.of(
                contextData("time", "当前时间：2026-07-11 10:00",
                        ContextTrustLevel.TRUSTED_DATA),
                sessionMemory(List.of())));

        ContextAssemblyResult result = ContextMessageAssembler.assemble(frame, null, null);

        assertFalse("Should fail without SystemMessage", result.success());
        assertEquals(ContextErrorCode.MESSAGE_SEQUENCE_INVALID, result.errorCode());
    }

    // ── ToolSpecification ──

    @Test
    public void toolContribution_producesToolSpecs() {
        ToolSpecification spec = ToolSpecification.builder()
                .name("set_ac_status").description("Set AC status").build();
        List<ContextContribution> contributions = new ArrayList<>();
        contributions.add(systemPrompt("You are a car assistant"));
        contributions.add(new ToolContextContribution(
                "tool", ContextVisibility.MODEL_VISIBLE, ContextTrustLevel.TRUSTED_DATA,
                ContextPriority.HIGH, ContextLifecycle.REQUEST_STATIC, true,
                "ToolProvider", ToolContextContribution.MODE_SELECTED,
                List.of(spec), Map.of()));
        contributions.add(sessionMemory(List.of()));
        ContextFrame frame = createFrameWithContributions(contributions);

        ContextAssemblyResult result = ContextMessageAssembler.assemble(frame, null, null);

        assertTrue(result.success());
        assertEquals(1, result.toolSpecifications().size());
        assertEquals("set_ac_status", result.toolSpecifications().get(0).name());
    }
}
