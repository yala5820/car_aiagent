package com.hirain.aiagent.context;

import com.hirain.aiagent.ai.langchain4j.tool.ToolRegistry;
import com.hirain.aiagent.memory.ContextMemoryGateway;
import com.hirain.aiagent.prompt.PromptManager;
import com.hirain.aiagent.runtime.SystemTimeProvider;
import com.hirain.aiagent.runtime.TimeProvider;
import com.hirain.aiagent.toolgroup.ToolGroupRegistry;

/**
 * Context 构建期依赖容器 — 保存 ContextOrchestrator 构建 ContextFrame 所需的全局依赖。
 * <p>
 * 设计原因：将 AgentRuntime / AIAgentService 中分散的依赖集中管理，便于 Provider 按需读取。
 * 依赖缺失时 Provider 必须 fallback 而非 NPE。
 */
public final class ContextBuildInput {

    private final ToolGroupRegistry toolGroupRegistry;
    private final PromptManager promptManager;
    private final ContextMemoryGateway memoryGateway;
    private final VehicleStatusProvider vehicleStatusProvider;
    private final TimeProvider timeProvider;
    private final ToolRegistry toolRegistry;
    private final ContextTokenEstimator tokenEstimator;

    private ContextBuildInput(Builder builder) {
        this.toolGroupRegistry = builder.toolGroupRegistry;
        this.promptManager = builder.promptManager;
        this.memoryGateway = builder.memoryGateway;
        this.vehicleStatusProvider = builder.vehicleStatusProvider;
        this.timeProvider = builder.timeProvider;
        this.toolRegistry = builder.toolRegistry;
        this.tokenEstimator = builder.tokenEstimator;
    }

    public static Builder builder() {
        return new Builder();
    }

    public ToolGroupRegistry toolGroupRegistry() { return toolGroupRegistry; }
    public PromptManager promptManager() { return promptManager; }
    public ContextMemoryGateway memoryGateway() { return memoryGateway; }
    public VehicleStatusProvider vehicleStatusProvider() { return vehicleStatusProvider; }
    public TimeProvider timeProvider() { return timeProvider; }
    public ToolRegistry toolRegistry() { return toolRegistry; }
    public ContextTokenEstimator tokenEstimator() { return tokenEstimator; }

    public static final class Builder {
        private ToolGroupRegistry toolGroupRegistry;
        private PromptManager promptManager;
        private ContextMemoryGateway memoryGateway;
        private VehicleStatusProvider vehicleStatusProvider;
        private TimeProvider timeProvider = new SystemTimeProvider();
        private ToolRegistry toolRegistry;
        private ContextTokenEstimator tokenEstimator = new HeuristicContextTokenEstimator();

        private Builder() {}

        public Builder toolGroupRegistry(ToolGroupRegistry value) {
            this.toolGroupRegistry = value;
            return this;
        }

        public Builder promptManager(PromptManager value) {
            this.promptManager = value;
            return this;
        }

        public Builder memoryGateway(ContextMemoryGateway value) {
            this.memoryGateway = value;
            return this;
        }

        public Builder vehicleStatusProvider(VehicleStatusProvider value) {
            this.vehicleStatusProvider = value;
            return this;
        }

        public Builder timeProvider(TimeProvider value) {
            if (value != null) this.timeProvider = value;
            return this;
        }

        public Builder toolRegistry(ToolRegistry value) {
            this.toolRegistry = value;
            return this;
        }

        public Builder tokenEstimator(ContextTokenEstimator value) {
            if (value != null) this.tokenEstimator = value;
            return this;
        }

        public ContextBuildInput build() {
            return new ContextBuildInput(this);
        }
    }
}
