package com.hirain.aiagent.core;

import com.hirain.aiagent.core.component.LoopTerminator;
import com.hirain.aiagent.core.component.ModelCaller;
import com.hirain.aiagent.core.component.PostProcessor;
import com.hirain.aiagent.core.component.PreProcessor;
import com.hirain.aiagent.core.component.ResultCollector;
import com.hirain.aiagent.core.component.SafetyGuard;
import com.hirain.aiagent.core.component.ToolExecutor;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import dev.langchain4j.agent.tool.ToolSpecification;

/**
 * 不可变 Agent 配置 — 完全定义一个"人格"的全部行为。
 * <p>
 * 通过 {@link Builder} 构建，{@link com.hirain.aiagent.core.factory.AgentConfigFactory AgentConfigFactory}
 * 提供三个预设（chat / scene / vision_qa）。
 */
public final class AgentConfig {

    public enum MemoryPolicy {
        /** SQLite 持久化，进程重启后恢复 */
        PERSISTENT,
        /** 进程内临时记忆，不持久化 */
        EPHEMERAL,
        /** 无记忆，每次调用新建 */
        NONE
    }

    private final String personaId;
    private final String systemPromptTemplateName;
    private final int maxIterations;
    private final int maxMemoryMessages;
    private final MemoryPolicy memoryPolicy;
    private final String chatMemoryStoreId;
    private final Duration timeout;

    // 组件链
    private final List<PreProcessor> preProcessors;
    private final ModelCaller modelCaller;
    private final ToolExecutor toolExecutor;
    private final List<ToolSpecification> toolSubset;
    private final List<SafetyGuard> safetyGuards;
    private final List<PostProcessor> postProcessors;
    private final LoopTerminator terminator;
    private final ResultCollector resultCollector;

    private AgentConfig(Builder b) {
        this.personaId = b.personaId;
        this.systemPromptTemplateName = b.systemPromptTemplateName;
        this.maxIterations = b.maxIterations;
        this.maxMemoryMessages = b.maxMemoryMessages;
        this.memoryPolicy = b.memoryPolicy;
        this.chatMemoryStoreId = b.chatMemoryStoreId;
        this.timeout = b.timeout;
        this.preProcessors = Collections.unmodifiableList(b.preProcessors);
        this.modelCaller = b.modelCaller;
        this.toolExecutor = b.toolExecutor;
        this.toolSubset = b.toolSubset != null
                ? Collections.unmodifiableList(b.toolSubset) : null;
        this.safetyGuards = Collections.unmodifiableList(b.safetyGuards);
        this.postProcessors = Collections.unmodifiableList(b.postProcessors);
        this.terminator = b.terminator;
        this.resultCollector = b.resultCollector;
    }

    // ── 读取器 ──

    public String personaId() { return personaId; }
    public String systemPromptTemplateName() { return systemPromptTemplateName; }
    public int maxIterations() { return maxIterations; }
    public int maxMemoryMessages() { return maxMemoryMessages; }
    public MemoryPolicy memoryPolicy() { return memoryPolicy; }
    public String chatMemoryStoreId() { return chatMemoryStoreId; }
    public Duration timeout() { return timeout; }
    public List<PreProcessor> preProcessors() { return preProcessors; }
    public ModelCaller modelCaller() { return modelCaller; }
    public ToolExecutor toolExecutor() { return toolExecutor; }
    public List<ToolSpecification> toolSubset() { return toolSubset; }
    public List<SafetyGuard> safetyGuards() { return safetyGuards; }
    public List<PostProcessor> postProcessors() { return postProcessors; }
    public LoopTerminator terminator() { return terminator; }
    public ResultCollector resultCollector() { return resultCollector; }

    // ── Builder ──

    public static class Builder {
        private final String personaId;
        private String systemPromptTemplateName;
        private int maxIterations = 10;
        private int maxMemoryMessages = 50;
        private MemoryPolicy memoryPolicy = MemoryPolicy.PERSISTENT;
        private String chatMemoryStoreId;
        private Duration timeout = Duration.ofSeconds(30);
        private List<PreProcessor> preProcessors = List.of();
        private ModelCaller modelCaller;
        private ToolExecutor toolExecutor;
        private List<ToolSpecification> toolSubset;
        private List<SafetyGuard> safetyGuards = List.of();
        private List<PostProcessor> postProcessors = List.of();
        private LoopTerminator terminator;
        private ResultCollector resultCollector;

        public Builder(String personaId) {
            this.personaId = personaId;
        }

        public Builder systemPromptTemplateName(String v) { this.systemPromptTemplateName = v; return this; }
        public Builder maxIterations(int v) { this.maxIterations = v; return this; }
        public Builder maxMemoryMessages(int v) { this.maxMemoryMessages = v; return this; }
        public Builder memoryPolicy(MemoryPolicy v) { this.memoryPolicy = v; return this; }
        public Builder chatMemoryStoreId(String v) { this.chatMemoryStoreId = v; return this; }
        public Builder timeout(Duration v) { this.timeout = v; return this; }
        public Builder preProcessors(List<PreProcessor> v) { this.preProcessors = v; return this; }
        public Builder modelCaller(ModelCaller v) { this.modelCaller = v; return this; }
        public Builder toolExecutor(ToolExecutor v) { this.toolExecutor = v; return this; }
        public Builder toolSubset(List<ToolSpecification> v) { this.toolSubset = v; return this; }
        public Builder safetyGuards(List<SafetyGuard> v) { this.safetyGuards = v; return this; }
        public Builder postProcessors(List<PostProcessor> v) { this.postProcessors = v; return this; }
        public Builder terminator(LoopTerminator v) { this.terminator = v; return this; }
        public Builder resultCollector(ResultCollector v) { this.resultCollector = v; return this; }

        public AgentConfig build() {
            if (personaId == null) throw new IllegalStateException("personaId is required");
            if (systemPromptTemplateName == null) throw new IllegalStateException("systemPromptTemplateName is required");
            if (modelCaller == null) throw new IllegalStateException("modelCaller is required");
            if (toolExecutor == null) throw new IllegalStateException("toolExecutor is required");
            if (terminator == null) throw new IllegalStateException("terminator is required");
            if (resultCollector == null) throw new IllegalStateException("resultCollector is required");
            return new AgentConfig(this);
        }
    }
}
