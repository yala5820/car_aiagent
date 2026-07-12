package com.hirain.aiagent.runtime;

import com.hirain.aiagent.AgentRequest;
import com.hirain.aiagent.context.ContextFrame;
import com.hirain.aiagent.context.ContextPrepareResult;
import com.hirain.aiagent.core.AgentResult;
import com.hirain.aiagent.memory.SessionIdResolver;
import com.hirain.aiagent.prompt.PromptManager;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;

public class AgentRuntimeResolvedSessionTest {

    private static final SessionIdResolver FIXED_RESOLVER =
            (userId, requestedSessionId, title, personaId, sourceApp) -> "resolved-session-1";

    @Test
    public void missingRequestSessionIdIsResolvedBeforeContextBuild() {
        AtomicReference<ContextFrame> frameRef = new AtomicReference<>();
        RecordingExecutor executor = new RecordingExecutor(frameRef);
        AgentRuntime runtime = createRuntime(executor, FIXED_RESOLVER);

        AgentRequest request = new AgentRequest();
        request.setInputType("TEXT");
        request.setUserId("user_a");
        request.setText("打开空调");

        RequestSession session = runtime.startSession(request, null);
        RuntimeResult result = runtime.execute(session);

        assertEquals("resolved-session-1", session.sessionId());
        assertEquals("resolved-session-1", session.orchestratorContext().get("session_id"));
        assertEquals("resolved-session-1", frameRef.get().sessionId());
        assertEquals("resolved-session-1", result.sessionId());
        assertTrue(result.success());
    }

    @Test
    public void explicitSessionIdPassedThroughResolver() {
        AtomicReference<ContextFrame> frameRef = new AtomicReference<>();
        RecordingExecutor executor = new RecordingExecutor(frameRef);

        // 当 resolver 收到非空 requestedSessionId 时应当返回它
        SessionIdResolver passthroughResolver =
                (userId, requestedSessionId, title, personaId, sourceApp) ->
                        requestedSessionId != null ? requestedSessionId : "auto-created";
        AgentRuntime runtime = createRuntime(executor, passthroughResolver);

        AgentRequest request = new AgentRequest();
        request.setInputType("TEXT");
        request.setUserId("user_a");
        request.setText("打开空调");
        request.setSessionId("explicit-session");

        RequestSession session = runtime.startSession(request, null);
        RuntimeResult result = runtime.execute(session);

        assertEquals("explicit-session", session.sessionId());
        assertEquals("explicit-session", result.sessionId());
        assertTrue(result.success());
    }

    /**
     * 验证 extraContext 中的 session_id 不可覆盖 Runtime 解析出的 canonical sessionId。
     * <p>
     * 设计原因：RequestSessionFactory 先合并 caller extraContext，后写入 canonical 字段，
     * 确保 Runtime 解析的 sessionId 始终优先。
     */
    @Test
    public void extraContextSessionIdDoesNotOverrideCanonicalResolvedSessionId() {
        AtomicReference<ContextFrame> frameRef = new AtomicReference<>();
        SessionIdResolver canonicalResolver =
                (userId, requestedSessionId, title, personaId, sourceApp) -> "canonical-session";
        RecordingExecutor executor = new RecordingExecutor(frameRef);
        AgentRuntime runtime = AgentRuntimeBuilder.create(executor, canonicalResolver);

        AgentRequest request = new AgentRequest();
        request.setInputType("TEXT");
        request.setUserId("user_a");
        request.setText("打开空调");
        // caller 在 extraContext 中放入恶意 session_id
        request.setExtraContext(Map.of("session_id", "evil-session"));

        RequestSession session = runtime.startSession(request, null);
        RuntimeResult result = runtime.execute(session);

        // canonical 必须始终优先
        assertEquals("canonical-session", session.sessionId());
        assertEquals("canonical-session", session.orchestratorContext().get("session_id"));
        assertEquals("canonical-session", result.sessionId());
        assertTrue(result.success());
    }

    @Test
    public void nullResolverPreservesOldPassthroughBehavior() {
        RecordingExecutor executor = new RecordingExecutor(new AtomicReference<>());
        // 使用不带 resolver 的构造器（旧行为）
        PromptManager pm = new PromptManager(null) {
            @Override public String render(String templateName) {
                return "System prompt for " + templateName;
            }
        };
        com.hirain.aiagent.ai.langchain4j.tool.ToolRegistry tr = new com.hirain.aiagent.ai.langchain4j.tool.ToolRegistry() {
            @Override public java.util.List<dev.langchain4j.agent.tool.ToolSpecification> toolSpecificationsByNames(
                    java.util.List<String> names) {
                return names != null ? names.stream().map(n -> dev.langchain4j.agent.tool.ToolSpecification.builder()
                        .name(n).description("default " + n).build()).toList() : java.util.List.of();
            }
            @Override public java.util.List<dev.langchain4j.agent.tool.ToolSpecification> enabledToolSpecifications() { return java.util.List.of(); }
            @Override public java.util.List<dev.langchain4j.agent.tool.ToolSpecification> getToolSpecifications() { return java.util.List.of(); }
            @Override public int size() { return 0; }
        };
        AgentRuntime runtime = new AgentRuntime(executor,
                com.hirain.aiagent.context.ContextOrchestrator.defaultForText(
                        com.hirain.aiagent.context.ContextBuildInput.builder()
                                .promptManager(pm)
                                .toolRegistry(tr)
                                .toolGroupRegistry(com.hirain.aiagent.toolgroup.ToolGroupRegistry.defaultRegistry())
                                .build()),
                () -> "req-1", () -> 1000L);

        AgentRequest request = new AgentRequest();
        request.setInputType("TEXT");
        request.setText("你好");

        // 无 sessionId 的请求 → sessionId 为 null（旧行为）
        RequestSession session = runtime.startSession(request, null);
        assertNull(session.sessionId());
        RuntimeResult result = runtime.execute(session);
        assertNull(result.sessionId());
    }

    // ── 辅助 ──

    private static AgentRuntime createRuntime(RecordingExecutor executor,
                                              SessionIdResolver resolver) {
        return AgentRuntimeBuilder.create(executor, resolver);
    }

    /**
     * 用于测试的 AgentRuntime 构建工具。
     * 使用 SessionIdResolver 的生产 4-arg 构造器 + neverCancelled()，
     * 确保 resolvedSessionId 的完整链路可测。
     */
    private static final class AgentRuntimeBuilder {
        static AgentRuntime create(AgentExecutor executor, SessionIdResolver resolver) {
            PromptManager pm = new PromptManager(null) {
                @Override public String render(String templateName) {
                    return "System prompt for " + templateName;
                }
            };
            com.hirain.aiagent.ai.langchain4j.tool.ToolRegistry tr = new com.hirain.aiagent.ai.langchain4j.tool.ToolRegistry() {
                @Override public java.util.List<dev.langchain4j.agent.tool.ToolSpecification> toolSpecificationsByNames(
                        java.util.List<String> names) {
                    return names != null ? names.stream().map(n -> dev.langchain4j.agent.tool.ToolSpecification.builder()
                            .name(n).description("default " + n).build()).toList() : java.util.List.of();
                }
                @Override public java.util.List<dev.langchain4j.agent.tool.ToolSpecification> enabledToolSpecifications() { return java.util.List.of(); }
                @Override public java.util.List<dev.langchain4j.agent.tool.ToolSpecification> getToolSpecifications() { return java.util.List.of(); }
                @Override public int size() { return 0; }
            };
            return new AgentRuntime(executor,
                    com.hirain.aiagent.context.ContextOrchestrator.defaultForText(
                            com.hirain.aiagent.context.ContextBuildInput.builder()
                                    .promptManager(pm)
                                    .toolRegistry(tr)
                                    .toolGroupRegistry(com.hirain.aiagent.toolgroup.ToolGroupRegistry.defaultRegistry())
                                    .build()),
                    resolver,
                    RuntimeCancelChecker.neverCancelled());
        }
    }

    /**
     * 录制 ContextFrame 的 AgentExecutor。
     * 含义：使用新 3-arg AgentExecutor.execute(RequestSession, ContextFrame) 重载，
     * 捕获 ContextFrame 供后续断言使用。
     */
    private static final class RecordingExecutor implements AgentExecutor {
        private final AtomicReference<ContextFrame> frameRef;

        RecordingExecutor(AtomicReference<ContextFrame> frameRef) {
            this.frameRef = frameRef;
        }

        @Override
        public AgentResult execute(RequestSession session, ContextPrepareResult prepareResult) {
            frameRef.set(prepareResult.frame());
            return AgentResult.success("完成", 1, 1L, List.of());
        }
    }
}
