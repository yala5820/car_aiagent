# Context 模块 Phase 1-3 验收审查

## 结论

本次提交**不通过 Phase 1-3 验收**。

原因不是“代码没跑起来”，而是**实现已经明显偏离已批准计划**，并且在偏离过程中引入了新的行为风险与 trace 诊断问题。当前代码更像是“提前把后续 phase 的 contribution/assemble/trace 方案整体并入”，而不是按 `docs/plan/context-module-implementation-plan.md` 完成已批准的 Phase 1-3。

---

## 主要问题

### P0-1：Phase 1-3 的核心契约没有按计划落地，实际交付物已经换成了另一套接口

计划要求的关键契约是：

- Phase 1 引入 `ContextMode`，并让 `ContextFrame` 持有 `mode()`，同时提供 `toOrchestratorContext(...)`
  - 见 `docs/plan/context-module-implementation-plan.md:191-208`
  - 见 `docs/plan/context-module-implementation-plan.md:297-329`
  - 见 `docs/plan/context-module-implementation-plan.md:380-405`
- Phase 2 要求 `ContextBuildInput` 包含 `mode`
  - 见 `docs/plan/context-module-implementation-plan.md:620-713`
- Phase 3 要求 `ContextOrchestrator.build(session)` 返回 `ContextBuildResult`
  - 见 `docs/plan/context-module-implementation-plan.md:1055-1133`

但当前实现里：

- `app/src/main/java/com/hirain/aiagent/context` 目录下**没有** `ContextMode.java`
- 同目录下**没有** `ContextBuildResult.java`
- [ContextBuildInput.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextBuildInput.java:18) 到 [ContextBuildInput.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextBuildInput.java:25) 的字段没有 `mode`
- [ContextFrame.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextFrame.java:35) 到 [ContextFrame.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextFrame.java:44) 没有 `mode` 字段；[ContextFrame.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextFrame.java:118) 到 [ContextFrame.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextFrame.java:125) 也没有 `mode()` / `toOrchestratorContext(...)`
- [ContextOrchestrator.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextOrchestrator.java:25) 到 [ContextOrchestrator.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextOrchestrator.java:33) 的注释声称“旧 build() 保留兼容”，但本类实际只提供 `prepare(...)` 和 `assemble(...)`，没有计划要求的 `build(session)`

这不是简单的“实现细节不同”，而是**验收对象已经换了**。按批准文档，Phase 1-3 应交付的是 `ContextMode + ContextFrame/BuildResult + HYBRID_EXTRA_CONTEXT build(session)`；当前交付的是另一套 `prepare/assemble + contribution` 契约，因此本项必须判定为未完成。

### P0-2：HYBRID 阶段边界被突破，调用方 extra、长期记忆、时间已经提前直接注入模型

计划对 Phase 1-3 的边界写得很明确：

- `ContextExtraPreProcessor` 只读 `context_rendered_extra`，本阶段**不依赖** `extra_context` 进入 LLM
  - 见 `docs/plan/context-module-implementation-plan.md:423-428`
- Phase 3 的 HYBRID 结果示例明确要求：
  - 包含运行时、意图、工具组
  - **不包含时间**
  - **不包含长期记忆**
  - 见 `docs/plan/context-module-implementation-plan.md:1066-1087`
- HYBRID 只拼接 renderable section，而不是把一切 contribution 直接塞入模型
  - 见 `docs/plan/context-module-implementation-plan.md:1106-1122`

但当前实现中：

- [CallerExtraContextProvider.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/provider/CallerExtraContextProvider.java:40) 到 [CallerExtraContextProvider.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/provider/CallerExtraContextProvider.java:55) 直接读取 `extra_context`，并以 `MODEL_VISIBLE + TARGET_CONTEXT_DATA` 产出
- [LongTermMemoryContextProvider.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/provider/LongTermMemoryContextProvider.java:44) 到 [LongTermMemoryContextProvider.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/provider/LongTermMemoryContextProvider.java:77) 把长期记忆做成 `MODEL_VISIBLE`
- [TimeContextProvider.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/provider/TimeContextProvider.java:41) 到 [TimeContextProvider.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/provider/TimeContextProvider.java:55) 把时间做成 `MODEL_VISIBLE`
- [ContextMessageAssembler.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextMessageAssembler.java:118) 到 [ContextMessageAssembler.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextMessageAssembler.java:134) 会把所有 `TARGET_CONTEXT_DATA + MODEL_VISIBLE` 文本统一格式化成一条 `UserMessage` 发给模型

这意味着：

- 调用方传入的 `extra_context` 已经被提前纳入模型输入
- 长期记忆和时间也已提前纳入模型输入
- 实际运行边界已经超出 Phase 1-3 批准范围

这既是**计划违背**，也是**安全面扩大**。尤其 `CallerExtraContextProvider` 把外部调用方文本作为模型可见上下文拼进去，会显著增加 prompt injection 面积。

### P1-1：新的 trace 结构没有真正去掉“大坨 context”，而是把旧的大字段和新 span 同时保留了

用户这轮需求的核心目标之一，是“完整展现 provider 消息，但不要再输出巨大的一坨 context”。

当前代码并未做到“替换”，而是做成了“叠加”：

- [ContextOrchestrator.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextOrchestrator.java:359) 到 [ContextOrchestrator.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextOrchestrator.java:379)
  - 先调用 `recordAssembledMessages(...)`
  - 再调用 `recordAssembleMessagesAsEvents(...)`
  - 然后又为每个 contribution 记录 `fragment/message/toolset` span
- [ContextTraceRecorder.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextTraceRecorder.java:176) 到 [ContextTraceRecorder.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextTraceRecorder.java:218) 仍然把完整 assembled messages 和 event 内容写到 `context.assemble`
- [ContextTraceRecorder.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextTraceRecorder.java:228) 到 [ContextTraceRecorder.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextTraceRecorder.java:241) 又把 fragment 正文单独写进 `context.fragment.*`

结果不是“从大坨 context 迁移为结构化 span”，而是：

- 旧的大字段还在
- 新的细粒度 span 也在
- 同一份内容可能在 assemble attribute、assemble event、fragment span 三处重复出现

这与本轮 trace 设计目标相反，也会直接放大 Phoenix/导出端的噪声和负担。

### P1-2：Provider 异常路径的耗时统计是错的，会误导 trace 定位

[ContextOrchestrator.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextOrchestrator.java:135) 到 [ContextOrchestrator.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextOrchestrator.java:150) 在 `prepare()` 的异常分支里，用的是：

- `System.currentTimeMillis() - startMs`

这里的 `startMs` 是整个 prepare 的起点，不是当前 provider 的起点。这样一旦中间 provider 抛异常，记录出来的 `provider.duration_ms` 就会被前序 provider 的时间一并算进去。

同时，[ContextOrchestrator.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextOrchestrator.java:281) 到 [ContextOrchestrator.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextOrchestrator.java:295) 在 `assemble()` 的异常分支里直接把 `provider.duration_ms` 写死为 `0`。

这会导致 trace 面板里看到的 provider 耗时：

- 要么偏大
- 要么恒为 0

排障价值明显不足。

### P1-3：`tool.result_writeback` 在异常路径上可能不结束，存在 span 泄漏

[TextAgentLoopOrchestrator.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/core/TextAgentLoopOrchestrator.java:280) 到 [TextAgentLoopOrchestrator.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/core/TextAgentLoopOrchestrator.java:290) 的流程是：

1. `startToolWriteback()`
2. `chatMemory.add(...)`
3. `trace.finishTool(...)`
4. `loopCtx.addToolResult(...)`
5. `finishToolWriteback(...)`

但如果第 2 步到第 4 步之间抛异常，会走到 [TextAgentLoopOrchestrator.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/core/TextAgentLoopOrchestrator.java:291) 到 [TextAgentLoopOrchestrator.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/core/TextAgentLoopOrchestrator.java:304) 的 `catch/finally`。这里：

- `toolSpan` 会结束
- `writebackSpan` 没有任何 `finally` 收口

也就是说，writeback 阶段一旦在真正写回时出错，会出现未结束子 span。这个问题单看 happy path 单元测试发现不了，但在真实内存写回故障时会污染 trace 结构。

### P2-1：测试已经被改写为适配新实现，失去了对批准计划的验收约束

当前测试虽然能通过，但它们验证的已经不是原计划：

- [ContextFrameBuilderTest.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/test/java/com/hirain/aiagent/context/ContextFrameBuilderTest.java:33) 到 [ContextFrameBuilderTest.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/test/java/com/hirain/aiagent/context/ContextFrameBuilderTest.java:50) 只校验基础字段，不再校验计划要求的 `mode()` 和 `toOrchestratorContext(...)`
  - 对比计划 `docs/plan/context-module-implementation-plan.md:289-329`
- [ContextOrchestratorTest.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/test/java/com/hirain/aiagent/context/ContextOrchestratorTest.java:72) 到 [ContextOrchestratorTest.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/test/java/com/hirain/aiagent/context/ContextOrchestratorTest.java:127) 只验证 `prepare()` 的局部行为，不再验证 `build(session)`、HYBRID 渲染内容和 fallback 结果
  - 对比计划 `docs/plan/context-module-implementation-plan.md:1062-1143`
- [ContextProviderFailureTest.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/test/java/com/hirain/aiagent/context/ContextProviderFailureTest.java:27) 到 [ContextProviderFailureTest.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/test/java/com/hirain/aiagent/context/ContextProviderFailureTest.java:52) 验证的是 `prepare()` outcome，并不是计划里的 `ContextBuildResult` fallback
  - 对比计划 `docs/plan/context-module-implementation-plan.md:1151-1170`
- `tool.execute` 相关测试主要集中在 [ToolPhaseTraceTest.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/test/java/com/hirain/aiagent/trace/ToolPhaseTraceTest.java:31) 到 [ToolPhaseTraceTest.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/test/java/com/hirain/aiagent/trace/ToolPhaseTraceTest.java:289)，但这些是 `AgentTraceRecorder` 级别测试，不是 `AgentRuntime -> TextAgentLoopOrchestrator -> ToolExecutor` 的真实 trace 链路验收

因此，“测试通过”不能证明本次提交完成了被批准的 Phase 1-3，只能证明**新实现自定义出来的那套接口**内部大致自洽。

---

## 验证情况

已执行：

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.context.*" --tests "com.hirain.aiagent.trace.*"
```

结果：`BUILD SUCCESSFUL`

说明：

- 这只能证明当前 context/trace 相关单测通过
- 不能证明实现符合批准计划
- 也不能证明真实 TEXT 执行链路一定能稳定产出 `tool.execute`

未完成的补充验证：

- `ContextTextEndToEndTest`
- `TextAgentLoopOrchestratorTest`
- `AgentRuntimeContextTest`

本轮未能继续执行的原因不是代码报错，而是额外提权执行在当前环境被拒绝。

---

## 建议处理顺序

1. 先回到批准计划，明确是否继续按 `ContextMode + ContextBuildResult + build(session)` 这条线收敛；如果不收敛，就必须先补一版正式变更计划，不能拿另一套架构来冒充 Phase 1-3 完成。
2. 在边界未重新批准前，立即移除 `CallerExtraContextProvider`、长期记忆、时间对 HYBRID 输入面的提前注入，恢复 Phase 1-3 的原始行为边界。
3. trace 侧必须二选一：要么保留 assemble 大字段，要么切换到 provider/fragment/message span；不能两套同时写。
4. 修正 provider 异常耗时统计与 `tool.result_writeback` 的异常收口。
5. 补真实链路测试，至少覆盖：
   - `AgentRuntime -> ContextOrchestrator -> TextAgentLoopOrchestrator`
   - 真实 tool call 产生 `tool.execute`
   - writeback 异常时 span 仍能闭合

---

## 最终判定

当前提交**不满足** `docs/plan/context-module-implementation-plan.md` 中 Phase 1-3 的验收条件。

问题不在于“有几个小 bug”，而在于：

- 核心契约未按批准方案落地
- HYBRID 输入边界被提前突破
- trace 结构化改造没有真正替换掉旧的大坨输出
- 测试验收口径已经被实现反向改写

建议按“计划回收敛”而不是“继续往后堆功能”的方式处理。
