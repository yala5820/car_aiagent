# AgentRuntime 引入阶段总结

## 工作目标

在 `AIAgentService` 与 `AgentLoopOrchestrator` 之间新增 `AgentRuntime` 中间层，将 TEXT 请求的 Agent 对话主流程下沉到 runtime 模块，同时保持 VOICE、IMAGE、CONTROL 原逻辑不变。

## 修改内容

### 新增 runtime 模块（10 个生产代码 + 4 个测试）

`app/src/main/java/com/hirain/aiagent/runtime/` 下新增 10 个文件：

| 文件 | 职责 |
|------|------|
| `AgentExecutor.java` | `@FunctionalInterface`，runtime 调用 orchestrator 的最小抽象，便于单元测试 mock |
| `IdGenerator.java` | requestId 生成接口 |
| `UuidIdGenerator.java` | UUID 默认实现，前缀 `req-` |
| `TimeProvider.java` | 时间来源接口，测试可固定时间 |
| `SystemTimeProvider.java` | `System.currentTimeMillis()` 默认实现 |
| `RequestSession.java` | 不可变单次请求快照，orchestratorContext 使用 `Collections.unmodifiableMap` 防护 |
| `RequestSessionFactory.java` | 从 `AgentRequest` + `TraceContext` 规范化 requestId/sessionId/userId |
| `RuntimeResult.java` | Runtime 层统一结果：success / failure / timeout / fromAgentResult / fromException 工厂方法 |
| `RuntimeResponseMapper.java` | RuntimeResult 到 AgentResponse 的映射器 |
| `AgentRuntime.java` | 主入口：startSession / execute / timeoutResult / errorResult |

`app/src/test/java/com/hirain/aiagent/runtime/` 下新增 4 个测试文件，共 **10 个测试用例**，覆盖：

- `RequestSessionFactoryTest`：缺失 ID 自动生成、已有 ID 保留、extraContext 拷贝
- `RuntimeResultTest`：fromAgentResult 成功/失败映射、fromException、timeout 工厂
- `RuntimeResponseMapperTest`：成功、异常、超时、通用失败四种 case
- `AgentRuntimeTest`：execute 正常流、executor 抛异常映射、timeoutResult 生成

### AIAgentService.kt 修改

- 新增 `agentRuntime` 和 `runtimeResponseMapper` 字段
- 在 `chatOrchestrator` 初始化后创建 `AgentRuntime`（SAM 转换适配 `AgentExecutor`）和 `RuntimeResponseMapper`
- `handleTextRequest` 改造：
  - Service 保留：`TraceSession` 创建/关闭、`TraceResponseDispatcher`、timeout Runnable 调度、root span makeCurrent
  - Runtime 接管：`startSession` → `execute` → `toAgentResponse` → dispatch
  - 删除手写 `AgentResponse().apply {...}` 和直接 `chatOrchestrator.execute(...)` 调用

## 设计要点

1. **职责切割**：Service 专心负责 AIDL/生命周期/Trace/超时调度；Runtime 只做 Session 规范化和 Agent 调用；Mapper 做响应映射
2. **测试解耦**：`AgentExecutor` 接口使 Runtime 单元测试完全不依赖 Android Context、SQLite 或真实 LLM
3. **不可变 Session**：`RequestSession.orchestratorContext()` 返回 `Collections.unmodifiableMap`，防止 Service 侧意外篡改
4. **异常安全**：`AgentRuntime.execute()` 内部捕获所有 Exception 并包装为 `EXCEPTION` 类型 RuntimeResult，Service 侧 `catch` 仅作为防御性兜底

## 验证结果

### 单测结果（全部 BUILD SUCCESSFUL）

| 测试范围 | 命令 | 结果 |
|---------|------|------|
| Runtime 模块 | `.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.runtime.*"` | ✅ |
| Trace 布线 | `.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.trace.AIAgentServiceTraceWiringTest"` | ✅ |
| Orchestrator Trace | `.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.core.AgentLoopOrchestratorTraceTest"` | ✅ |
| 全量 JVM 单测 | `.\gradlew.bat testDebugUnitTest` | ✅ |

### 静态验收

- `handleTextRequest` 中不再直接调用 `chatOrchestrator.execute(...)` ✅
- `handleVoiceRequest` 中仍保留现有 `chatOrchestrator.execute(...)`（本阶段未迁移）✅
- `handleImageRequest` 和 `handleControlRequest` 未触及 ✅
- `AIAgentService.kt` 编译通过 ✅

## 架构变更对比

```
变更前:
AIAgentService.handleTextRequest()
  → TraceManager.startAgentRequest()    // Trace 创建 (Service)
  → TraceResponseDispatcher/超时调度     // 超时调度 (Service)
  → chatOrchestrator.execute(text, ctx) // Agent 调用 (Service 直接)
  → 手写 AgentResponse 映射              // 响应映射 (Service)
  → notifyAIAgentListeners             // 通知回调 (Service)

变更后:
AIAgentService.handleTextRequest()
  → TraceManager.startAgentRequest()    // Trace 创建 (Service — 不变)
  → TraceResponseDispatcher/超时调度     // 超时调度 (Service — 不变)
  → agentRuntime.startSession()         // Session 规范化 (Runtime)
  → agentRuntime.execute(session)       // Agent 调用 (Runtime — 新层)
    → chatOrchestrator.execute(text,ctx)// 底层 orchestrator (不变)
  → runtimeResponseMapper.toAgentResponse() // 响应映射 (Runtime)
  → notifyAIAgentListeners             // 通知回调 (Service — 不变)
```

## 未迁移范围

- **VOICE**：本阶段不迁移，仍由 AIAgentService.handleVoiceRequest 管理 ASR/TTS 包装和对话调用
- **IMAGE**：仍由 AIAgentService.handleImageRequest 调用 VlManager
- **CONTROL**：仍由 AIAgentService.handleControlRequest 处理控制命令
- **IntentRouter / PolicyEngine / ContextOrchestrator / ToolGroup / Eval**：本阶段不接入

## 本阶段禁止变更项（已遵守）

- 未改变 ToolRegistry / ToolDispatcher / PromptManager / MemoryOrchestrator / VehicleStateMachine
- 未改变 AgentLoopOrchestrator 主循环行为
- 未改变 AIDL 接口 / AgentRequest / AgentResponse 字段
- 未改变 VOICE / IMAGE / CONTROL 处理逻辑

## 后续建议

1. **Phase 2**：抽取 VOICE 识别文本后的 Agent 对话部分让其复用 AgentRuntime，但保留 TTS 包装在 Service
2. **Phase 3**：引入 IntentRouter，把 inputType 和 intent 路由从 Service 中分离
3. **Phase 4**：引入 ContextOrchestrator，把 RequestSession.orchestratorContext 升级为统一 context assembly
4. **Phase 5**：引入 PolicyEngine 管理 timeout、并发、TTS 打断和场景触发抑制
5. **Phase 6**：引入 ToolGroup，沉淀工具分组和场景可用工具策略
6. **Phase 7**：引入 Eval，建立 AgentRuntime 级别回归评测
