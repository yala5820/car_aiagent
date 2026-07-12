# Context TEXT 剩余验收缺口一次性收口方案

## 目标

一次性关闭上一轮审批剩余的两个功能验收缺口：身份切换与全量工具的真实模型输入验证，以及超预算时模型、工具、压缩和 SessionMemory 均不产生副作用。

## 修改范围

- 修改 `TextAgentLoopOrchestrator.java`：把当前 UserMessage 的单次持久化从 assemble/预算检查之前，移动到预算通过且模型调用前取消检查通过之后。首轮消息仍由 `ContextPrepareResult.frame()` 参与装配；持久化后供后续工具迭代读取。
- 修改 `ContextTextEndToEndTest.java`：
  - 同一 Session 从 user A 切换到 user B 时，同时断言短期历史保留、A 的长期记忆消失、B 的长期记忆进入真实 `ChatRequest`。
  - 通过真实 `AgentRuntime -> ToolGroupContextProvider -> ContextMessageAssembler -> TextAgentLoopOrchestrator` 验证 allToolsFallback 的完整启用工具集合进入 `ChatRequest.toolSpecifications()`。
  - 通过真实 Context 装配器和 Text Loop 注入极小预算，断言返回 `CONTEXT_BUDGET_EXCEEDED`，模型与工具调用次数为 0，SessionMemory 不变，compaction plan/execute 次数为 0。

## 边界

- 不修改 Provider、Memory 存储实现、ToolGroupSelector 决策和预算数值。
- 不启用裁剪、压缩或二次 assemble。
- 不处理 IMAGE、VOICE、CONTROL、SCENE、VL。
- 不通过手工构造消息绕过 `ContextMessageAssembler`。

## 验证

1. 运行 `ContextTextEndToEndTest` 和 `TextAgentLoopOrchestratorTest`。
2. 运行完整 `testDebugUnitTest --rerun-tasks`，要求零失败。
3. 运行 `assembleDebug --rerun-tasks`，要求构建成功。

