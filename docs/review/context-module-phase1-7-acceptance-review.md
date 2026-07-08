# Context 模块 Phase 1-7 验收审查报告

审查日期：2026-07-07

审查范围：

- 计划与边界文档：`docs/plan/context_demo_plan.md`、`docs/plan/context-module-implementation-plan.md`
- 本阶段实现：`app/src/main/java/com/hirain/aiagent/context/`、`runtime/`、`core/preprocessor/`、`AIAgentService.kt`
- 本阶段测试与文档：`app/src/test/java/com/hirain/aiagent/context/`、`runtime/`、`core/`、`docs/act_summary/`、`docs/check_accept/`

自动化验证结果：

- `.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.context.*"`：通过
- `.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.runtime.*"`：通过
- `.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.core.ContextExtraPreProcessorTest" --tests "com.hirain.aiagent.core.AgentLoopOrchestratorContextInjectionTest"`：通过
- `.\gradlew.bat testDebugUnitTest`：通过

结论：本阶段主体链路已经接入，TEXT 请求会先构建 `ContextFrame`，再执行取消检查，随后进入 `AgentLoopOrchestrator`。但是仍存在 3 个需要整改的问题，其中前 2 个会直接影响计划目标或验收字段的真实性。

## 问题 1：ContextFrame.tokenEstimate 始终没有被写入

严重级别：P1

证据：

- `ContextFrameBuilder` 提供了 `tokenEstimate(int)` 字段入口，`ContextFrame` 也暴露了 `tokenEstimate()`。
- `ContextOrchestrator.build()` 在第 113-124 行构建 `ContextFrame` 时没有调用 `.tokenEstimate(...)`。
- `ContextOrchestrator.build()` 在第 130 行才计算 `int tokenEst = input.budgetManager().estimateTokens(rendered);`，只在第 131-135 行写入 Trace，没有回写到 `ContextFrame`。

影响：

- `ContextFrame.tokenEstimate()` 在生产链路中会保持默认值 0。
- `docs/plan/context_demo_plan.md` 要求 `ContextFrame` 包含 `tokenEstimate`，当前只满足 Trace 观测，不满足 frame 数据契约。
- 下游如果基于 `ContextFrame.tokenEstimate()` 做预算、诊断或后续压缩策略，会读到错误值。
- 现有测试只验证了 `ContextFrameBuilderTest` 手动设置 token 的场景，没有验证 `ContextOrchestrator` 真实构建出的 frame 是否带有 token 估算，因此漏掉了该问题。

建议修复：

1. 在 `ContextOrchestrator.build()` 中先计算 `rendered` 的 token 估算，再构建 `ContextFrame`。
2. 构建 frame 时显式调用 `.tokenEstimate(tokenEst)`。
3. 增加集成测试：通过 `ContextOrchestrator.defaultForText(...).build(session)` 得到的 `frame.tokenEstimate()` 应等于 `ContextBudgetManager.estimateTokens(frame.renderedExtraContext())`，且在有可渲染上下文时大于 0。

## 问题 2：ContextBudgetManager 只被测试，没有真正参与上下文裁剪

严重级别：P1

证据：

- `ContextBudgetManager` 的注释明确写着“在 Provider 阶段调用 trim 和 estimateTokens 进行预算裁剪”，并定义了 `sectionCharLimit=800`、`memoryCharLimit=500`、`toolContextCharLimit=1200`、`totalCharLimit=3000`。
- 当前生产代码中，`input.budgetManager().estimateTokens(rendered)` 只用于 Trace 的 token 估算。
- 当前没有生产调用 `input.budgetManager().trim(...)`，`ToolGroupContextProvider` 第 50-58 行直接使用完整 `content` 创建 section，`ContextOrchestrator.renderExtraContext()` 第 149-154 行直接拼接所有 renderable section。
- `ContextBudgetManagerTest` 只验证了预算类本身的 `trim()` 和 `estimateTokens()`，没有覆盖 `ContextOrchestrator` 或 Provider 的预算集成。

影响：

- `docs/plan/context_demo_plan.md` 中“限制 context section、memory summary、selected tool descriptions”的边界目标没有真正落地。
- 后续只要工具组描述、用户输入、persona 信息或其他 renderable section 变长，`renderedExtraContext` 就可能超过预期预算。
- `ContextSection.charCount` 和 `truncated` 当前无法真实反映预算裁剪状态，验收和 Trace 会产生“看起来有预算管理，实际未裁剪”的误导。

建议修复：

1. 明确预算裁剪发生位置：优先在 `ContextOrchestrator` 统一裁剪各 section 和总 rendered context，或者在各 Provider 内按 section 类型裁剪。
2. `ToolGroupContextProvider` 至少应使用 `toolContextCharLimit()`；普通 renderable section 使用 `sectionCharLimit()`；汇总后使用 `totalCharLimit()`。
3. 被裁剪时应更新 `ContextSection.charCount`、`truncated=true`，并在 `ContextDebugInfo` 或 section metadata 中记录裁剪原因。
4. 增加低预算集成测试：传入很小的 `ContextBudgetManager`，断言 `renderedExtraContext.length()` 不超过总预算，且相关 section 的 `truncated=true`。

## 问题 3：FULL_CONTEXT 文档语义与实际注入行为不一致

严重级别：P2

证据：

- `ContextMode.FULL_CONTEXT` 注释写明“一期不真正启用，当前行为降级为 HYBRID_EXTRA_CONTEXT”。
- `ContextOrchestrator.renderExtraContext()` 第 145-154 行对 `FULL_CONTEXT` 确实会像 HYBRID 一样拼接 renderable section。
- `ContextExtraPreProcessor.prepare()` 第 20-29 行只接受 `context_mode == "HYBRID_EXTRA_CONTEXT"`，如果 mode 是 `FULL_CONTEXT`，即使 `context_rendered_extra` 已经存在，也会直接返回空列表。
- `docs/act_summary/context-module-introduction-summary.md` 写道：以后想启用完整模式“只需在 Service 初始化时改一下 ContextMode”。按当前代码，改成 `FULL_CONTEXT` 后上下文不会被注入 LLM。

影响：

- FULL_CONTEXT 的降级行为在 `ContextOrchestrator` 和 `ContextExtraPreProcessor` 之间断裂。
- 后续开发者按阶段总结文档把 Service 配置改为 `FULL_CONTEXT` 时，会得到“Trace 中有 renderedExtraContext，但模型实际没收到”的隐蔽故障。
- 现有 `ContextOrchestratorTest.fullContextRecordsDeferredFallback()` 只验证 fallback 标记，没有验证 FULL_CONTEXT 下最终是否仍能注入模型消息。

建议修复：

1. 如果 FULL_CONTEXT 一期定义为“降级为 HYBRID 注入”，`ContextExtraPreProcessor` 应同时接受 `HYBRID_EXTRA_CONTEXT` 和 `FULL_CONTEXT`。
2. 如果 FULL_CONTEXT 一期定义为“只观测不注入”，则 `ContextOrchestrator.renderExtraContext()` 和阶段总结文档都应改为一致语义。
3. 增加测试：`ContextMode.FULL_CONTEXT` 构建出的 `context_rendered_extra` 经 `ContextExtraPreProcessor` 后行为应与产品定义一致。

## 测试覆盖缺口

以下不是独立功能缺陷，但会降低本阶段验收可信度：

1. `AgentLoopOrchestratorContextInjectionTest` 实际只验证了 `ContextOrchestrator -> ContextFrame -> extraContext Map -> ContextExtraPreProcessor`，没有真正实例化 `AgentLoopOrchestrator` 或 fake `ModelCaller` 捕获最终 `ChatRequest.messages()`。
2. 取消保护测试覆盖了 `RuntimeCancelChecker` 阻止 executor 执行，但缺少 Service 层“cancel 回调与 runtime success 竞态只发一次终态”的集成级验证。
3. 手动验收清单要求 Phoenix/Trace 观测和真实 Android 设备验证，目前自动化结果只能证明 JVM 层通过，不能替代设备侧 AIDL、Listener、前台 Service 生命周期和真实 LLM 请求验证。

## 建议整改顺序

1. 先修复 `ContextFrame.tokenEstimate` 未赋值问题，并补充 `ContextOrchestrator` 集成测试。
2. 再接入真实预算裁剪，并补充低预算集成测试。
3. 明确 FULL_CONTEXT 一期语义，统一代码、阶段总结和测试。
4. 在设备侧按 `docs/check_accept/context-module-manual-acceptance-checklist.md` 做一次真实验收，重点看 Trace 字段、Context 注入、取消竞态和非 TEXT 请求非回归。
