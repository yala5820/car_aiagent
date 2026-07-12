package com.hirain.aiagent.context;

/**
 * Context 装配网关 — AgentLoop 每轮调用的稳定装配接口。
 * <p>
 * 设计原因：AgentLoop 通过此窄接口调用 Context 装配，
 * 不需要了解 ContextOrchestrator 内部的 Provider 链或 BudgetManager。
 * 便于测试替换。
 */
public interface ContextAssemblyGateway {
    ContextAssemblyResult assemble(ContextAssemblyRequest request);
}
