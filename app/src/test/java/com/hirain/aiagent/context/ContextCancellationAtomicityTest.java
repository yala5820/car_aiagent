package com.hirain.aiagent.context;

import com.hirain.aiagent.core.AgentLoopOrchestrator;
import com.hirain.aiagent.core.AgentResult;
import com.hirain.aiagent.core.AgentConfig;
import com.hirain.aiagent.core.collector.DirectTextCollector;
import com.hirain.aiagent.core.component.ModelCaller;
import com.hirain.aiagent.core.postprocessor.NoOpPostProcessor;
import com.hirain.aiagent.core.terminator.NoToolCallTerminator;
import com.hirain.aiagent.runtime.RequestSession;
import com.hirain.aiagent.trace.AgentTraceRecorder;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ContextCancellationAtomicityTest {

    private AgentConfig configWith(CapturingModelCaller caller, Object toolExec, AtomicBoolean cancelFlag) {
        return AgentConfig.builder("chat")
                .modelName("qwen-turbo")
                .systemPromptTemplateName("prompts/system/assistant_default")
                .maxIterations(10).maxMemoryMessages(50)
                .memoryPolicy(AgentConfig.MemoryPolicy.PERSISTENT)
                .modelCaller(caller)
                .toolExecutor(toolExec instanceof com.hirain.aiagent.core.component.ToolExecutor
                        ? (com.hirain.aiagent.core.component.ToolExecutor) toolExec
                        : req -> "{}")
                .toolSubset(null)
                .safetyGuards(List.of())
                .postProcessors(List.of(new NoOpPostProcessor()))
                .terminator(new NoToolCallTerminator())
                .resultCollector(new DirectTextCollector())
                .timeout(java.time.Duration.ofSeconds(30))
                .build();
    }

    // ── CapturingModelCaller (same as in AgentLoopOrchestratorTextPathTest) ──
    static class CapturingModelCaller implements ModelCaller {
        final List<ChatResponse> responses;
        int index = 0;
        final AtomicBoolean cancelBeforeWrite = new AtomicBoolean(false);
        CapturingModelCaller(List<ChatResponse> responses) { this.responses = responses; }
        @Override public ChatResponse call(dev.langchain4j.model.chat.request.ChatRequest request) {
            ChatResponse resp = responses.get(Math.min(index, responses.size() - 1));
            index++;
            return resp;
        }
    }

    private static RequestSession session(String input) {
        return com.hirain.aiagent.context.TestRequestSessions.textSession(
                "req-1", "conv-1", "user-a", "chat", "client-1", input);
    }

    // ══════════════════════════════════════
    //  cancel-before-prepare: 在 prepare 前取消
    // ══════════════════════════════════════
    @Test
    public void cancelBeforePrepare_returnsCancelled() {
        ContextCancelChecker alwaysCancelled = () -> true;
        ContextOrchestrator orch = ContextOrchestrator.defaultForText(ContextBuildInput.builder().build());
        ContextPrepareResult result = orch.prepare(session("hi"), alwaysCancelled);

        assertTrue("Should be cancelled", result.isCancelled());
    }

    // ══════════════════════════════════════
    //  cancel-after-user-write: 写入 user message 后、assemble 前取消
    // ═══════════════════════════════════════════════════════════
    @Test
    public void cancelDuringDynamicProvider_returnsCancelled() {
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        ContextCancelChecker canceller = () -> cancelFlag.get();

        // 构建一个有一个 ITERATION_DYNAMIC Provider 的 Orchestrator
        ContextProvider blockingProvider = new ContextProvider() {
            @Override public String name() { return "BlockingProvider"; }
            @Override public ContextLifecycle lifecycle() { return ContextLifecycle.ITERATION_DYNAMIC; }
            @Override public boolean required(RequestSession session, ContextBuildInput input) { return false; }
            @Override public ContextProviderResult provide(RequestSession session, ContextBuildInput input) {
                cancelFlag.set(true); // trigger cancel before the next provider
                return ContextProviderResult.success(name(), null);
            }
        };

        ContextOrchestrator orch = new ContextOrchestrator(
                ContextBuildInput.builder().build(),
                List.of(new com.hirain.aiagent.context.provider.RuntimeContextProvider(),
                        new com.hirain.aiagent.context.provider.UserInputContextProvider()),
                List.of(blockingProvider));

        // prepare 成功
        ContextPrepareResult prepareResult = orch.prepare(session("hi"), ContextCancelChecker.neverCancelled());
        assertTrue("Prepare should succeed", prepareResult.isSuccess());

        // assemble 时应被取消
        ContextFrame frame = prepareResult.frame();
        ContextAssemblyRequest req = new ContextAssemblyRequest(frame, 0,
                ModelContextWindowProfiles.qwenTurboDemo(), canceller, false, session("hi"));
        ContextAssemblyResult assemblyResult = orch.assemble(req);

        assertFalse("Assemble should fail when cancelled", assemblyResult.success());
        assertEquals(ContextErrorCode.CONTEXT_CANCELLED, assemblyResult.errorCode());
    }


    // ── helper: counting tool executor ──
    static class CountingToolExecutor implements com.hirain.aiagent.core.component.ToolExecutor {
        int count = 0;
        @Override public String execute(ToolExecutionRequest request) {
            count++;
            return "{\"ok\":true}";
        }
    }
}
