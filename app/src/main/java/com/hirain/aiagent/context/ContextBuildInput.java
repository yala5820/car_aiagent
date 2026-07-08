package com.hirain.aiagent.context;

import com.hirain.aiagent.memory.MemoryOrchestrator;
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

    private final ContextMode mode;
    private final ToolGroupRegistry toolGroupRegistry;
    private final PromptManager promptManager;
    private final MemoryOrchestrator memoryOrchestrator;
    private final VehicleStatusProvider vehicleStatusProvider;
    private final TimeProvider timeProvider;
    private final ContextBudgetManager budgetManager;

    private ContextBuildInput(Builder builder) {
        this.mode = builder.mode;
        this.toolGroupRegistry = builder.toolGroupRegistry;
        this.promptManager = builder.promptManager;
        this.memoryOrchestrator = builder.memoryOrchestrator;
        this.vehicleStatusProvider = builder.vehicleStatusProvider;
        this.timeProvider = builder.timeProvider;
        this.budgetManager = builder.budgetManager;
    }

    public static Builder builder() {
        return new Builder();
    }

    public ContextMode mode() { return mode; }
    public ToolGroupRegistry toolGroupRegistry() { return toolGroupRegistry; }
    public PromptManager promptManager() { return promptManager; }
    public MemoryOrchestrator memoryOrchestrator() { return memoryOrchestrator; }
    public VehicleStatusProvider vehicleStatusProvider() { return vehicleStatusProvider; }
    public TimeProvider timeProvider() { return timeProvider; }
    public ContextBudgetManager budgetManager() { return budgetManager; }

    public static final class Builder {
        private ContextMode mode = ContextMode.HYBRID_EXTRA_CONTEXT;
        private ToolGroupRegistry toolGroupRegistry;
        private PromptManager promptManager;
        private MemoryOrchestrator memoryOrchestrator;
        private VehicleStatusProvider vehicleStatusProvider;
        private TimeProvider timeProvider = new SystemTimeProvider();
        private ContextBudgetManager budgetManager = ContextBudgetManager.defaultBudget();

        private Builder() {}

        public Builder mode(ContextMode value) {
            if (value != null) this.mode = value;
            return this;
        }

        public Builder toolGroupRegistry(ToolGroupRegistry value) {
            this.toolGroupRegistry = value;
            return this;
        }

        public Builder promptManager(PromptManager value) {
            this.promptManager = value;
            return this;
        }

        public Builder memoryOrchestrator(MemoryOrchestrator value) {
            this.memoryOrchestrator = value;
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

        public Builder budgetManager(ContextBudgetManager value) {
            if (value != null) this.budgetManager = value;
            return this;
        }

        public ContextBuildInput build() {
            return new ContextBuildInput(this);
        }
    }
}
