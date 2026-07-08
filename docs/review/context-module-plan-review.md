# Context 模块计划文档 审查报告

**审查日期：** 2026-07-07
**审查范围：** `docs/plan/context_demo_plan.md`（任务目标与边界）、`docs/plan/context-module-implementation-plan.md`（实现计划）
**审查人：** Claude Code

---

## 一、总体评估

**有条件通过，3 个问题需要确认/修正后方可启动实施。** 整体方案结构完整、边界意识清晰、分阶段策略合理。9 个 ContextProvider 划分得当，HYBRID_EXTRA_CONTEXT 渲染策略正确避免重复注入。但存在 3 个可能影响编译或架构一致性的问题，以及若干值得优化的细节。

---

## 二、需在实施前解决的问题

### 问题 1（阻塞）：`ContextBuildInput` 引用 `VehicleStatusPreProcessor.VehicleStatusProvider` 引发包依赖倒挂

**位置：** 实现计划 Task 2.1 Step 4

**描述：**
`ContextBuildInput` 的字段声明为：
```java
private final VehicleStatusPreProcessor.VehicleStatusProvider vehicleStatusProvider;
```

这导致 `com.hirain.aiagent.context` 包依赖 `com.hirain.aiagent.core.preprocessor`。按当前架构分层，context 是介于 runtime 与 core 之间的协调层，不应直接引用 core 的内部组件。这将产生以下风险：

- `context` 包直接绑定到 `VehicleStatusPreProcessor` 的内部接口，若该 PreProcessor 重构（拆分或合并），context 包必须同步修改。
- context 模块的 Provider 实现在 `context.provider` 子包中，一旦 VehicleStatusPreProcessor 修改，9 个 provider 中的 VehicleStateContextProvider 也要改，耦合跨两层。

**建议方案（二选一）：**

方案 A：在 `context` 包内定义独立 `VehicleStatusProvider` 函数式接口（或直接使用 `java.util.function.Supplier<String>`），因为需要的只是一个 `() -> String` 的快照获取。生产初始化时用 `VehicleStatusPreProcessor.VehicleStatusProvider` 的 lambda 引用适配。

方案 B：将 `VehicleStatusPreProcessor.VehicleStatusProvider` 提升到 `com.hirain.aiagent.runtime` 包，与 `TimeProvider` / `IdGenerator` 同级。

推荐方案 A，改动最小且不破坏现有 PreProcessor 结构。

---

### 问题 2（阻塞）：`PromptContextProvider` 的 persona→template 映射与 `AgentConfigFactory` 重复

**位置：** 实现计划 Task 2.4 Step 5

**描述：**
PromptContextProvider 中硬编码了 persona 到 prompt template 名的映射：
```java
if ("friendly".equals(personaId)) return PromptConstants.SYSTEM_ASSISTANT_FRIENDLY;
if ("concise".equals(personaId)) return PromptConstants.SYSTEM_ASSISTANT_CONCISE;
return PromptConstants.SYSTEM_ASSISTANT_DEFAULT;
```

而 `AgentConfigFactory.switchPersonaTemplate()` 已有完全相同的映射逻辑。未来新增 persona 时必须在两处同步修改，容易遗漏。

**建议方案（二选一）：**

方案 A：PromptContextProvider 改为调用 `AgentConfigFactory` 的静态方法获取 template 名称，消除重复。

方案 B：将 persona→template 映射抽取到 `PromptConstants` 中（如 `templateForPersona(personaId)`），两边共同引用。

推荐方案 B，因为 `AgentConfigFactory` 在 `core.factory` 包中，PromptContextProvider 若引用它同样产生包依赖问题。放在 `PromptConstants` 中更合理。

---

### 问题 3（阻塞）：Task 6.2 测试引用了未定义的 `runtimeWithContextCapture()` 辅助方法

**位置：** 实现计划 Phase 6 Task 6.2 Step 1-4

**描述：**
测试用例中使用了：
```java
RequestSession session = runtimeWithContextCapture().startSession(request, null);
```

该方法在计划中未有定义或实现。`AgentRuntimeContextTest` 需要一种方式构造一个既含 `ContextOrchestrator` 又含 `AgentRuntime` 的测试环境。若无此辅助方法，Task 6.2 的 4 个测试将无法通过编译。

**建议方案：**
在 Task 4.2 实施时同步定义该辅助方法。预期形态：
```java
private AgentRuntime runtimeWithContextCapture() {
    ContextOrchestrator orchestrator = ContextOrchestrator.defaultForText(
        ContextBuildInput.builder()
            .mode(ContextMode.HYBRID_EXTRA_CONTEXT)
            .toolGroupRegistry(ToolGroupRegistry.defaultRegistry())
            .build());
    return new AgentRuntime(
        (userInput, context) -> AgentResult.success("ok", 1, 1L, List.of()),
        orchestrator,
        () -> "req-1", () -> 1000L);
}
```

Task 6.2 的测试由 `startSession()` 触发 `IntentRouter` + `ToolGroupSelector`，若 `IntentRouter` 依赖完整的关键词规则才能工作，则测试输入的文本（如"打开空调"）**必须**对应真实关键词规则才能得到预期的 ToolGroupSelectionResult。请确认当前 `KeywordIntentRouter` 对"打开空调"、"打开车窗"、"打开座椅加热"、"你好"的匹配结果是否符合测试预期。

---

## 三、建议修复的非阻塞问题

### 3.1 `ContextBuildInput` 缺少 builder 实现细节

实现计划 Phase 2 声明了 `ContextBuildInput.builder()` 使用方式，但未展示 builder 类的实现代码。考虑到该类有 6+ 个字段且大部分有缺省值（`mode` 缺省 `HYBRID_EXTRA_CONTEXT`，`budgetManager` 缺省 `ContextBudgetManager.defaultBudget()`），builder 的实现细节应在 Task 2.1 中明确列出，避免实施时临时定义。

**建议：** 在 Task 2.1 Step 4 中补充 builder 代码片段。

### 3.2 FULL_CONTEXT 模式实际行为与命名偏差

计划声明 FULL_CONTEXT 模式下 `ContextOrchestrator` 按 HYBRID 渲染并记录 `full_context_deferred=true`。这意味着 FULL_CONTEXT 在当前版本中与 HYBRID_EXTRA_CONTEXT 行为完全一致。enum 成员存在但无差异行为，可能对后续开发者造成困惑。

**建议：** 在 `ContextMode` 的注释中明确标注：
```java
/**
 * FULL_CONTEXT: 一期未实现。当前行为同 HYBRID_EXTRA_CONTEXT，
 * debugInfo 中标记 full_context_deferred=true。
 * 后续版本将启用所有 section 的渲染。
 */
```

### 3.3 ToolGroupContextProvider 的「selected tool descriptions」说法不精确

`context_demo_plan.md` 边界要求第 13 条说"只渲染 selectedToolNames 对应工具说明"，但实现计划的 ToolGroupContextProvider 内容格式只包含 **group level 描述**和 **tool names 列表**，不包含每个工具的详细参数描述（即 `ToolSpecification.description`）。如果期望的内容仅是 group 描述 + tool names，则描述无误；如果期望包含 `ToolSpecification.description`，则需要将 `ToolRegistry` 加入 `ContextBuildInput`。

**建议：** 明确 `renderedExtraContext` 中工具相关内容的粒度——仅 group 描述 + tool names，还是包含完整 tool specification 片段。当前实现计划中的格式示例是 group 级描述，应让 `context_demo_plan.md` 的描述与之对齐。

### 3.4 AgentRuntime 构造函数膨胀

当前已有 4 个构造函数。新增 `ContextOrchestrator` 参数后，若为每个构造函数都添加重载，构造函数组合数将持续增长。实现计划采用"测试构造函数内部使用默认 ContextOrchestrator"的策略是合理的，但未明确展示最终构造函数列表。

**建议：** 在 Phase 4 Task 4.2 中明确列出最终构造函数签名列表，确认没有遗漏。

---

## 四、确认无误的设计点

以下各点在审查中确认可行，无需修改：

| 检查项 | 结论 |
|--------|------|
| RequestSession 作为 ContextFrame 唯一输入源，禁止重新生成 ID | ✅ 设计正确 |
| ContextFrameBuilder.fromSession() 只读取 RequestSession 字段 | ✅ |
| ContextSection 不可变 + unmodifiableMap | ✅ |
| provider 异常不阻断构建、进入 fallback 链路 | ✅ 已在 Phase 3 实现 |
| ToolGroupContextProvider 只渲染 selected groups，不调用 allGroups() | ✅ |
| Memory/Vehicle/Time/Prompt Provider 在 HYBRID 模式下 renderable=false | ✅ |
| AgentExecutor 通过 default method 保持向后兼容 | ✅ |
| AgentRuntime.execute() 内 ContextOrchestrator.build() 先于 executor | ✅ |
| ContextOrchestrator.build() 异常进入 RuntimeResult.fromException | ✅ |
| FULL_CONTEXT 阶段 deferred（不接入 runtime） | ✅ |
| ContextTraceRecorder 使用 comma-separated，不写 JSON | ✅ |
| OBSERVE_ONLY 模式：section 构建但不渲染 | ✅ |
| 9 个 provider 的执行顺序合理 | ✅ |
| Trace 11 个 context 字段命名符合现有 `agent.*` 命名体系 | ✅ |
| `TestTraceSupport` 存在于 `app/src/test/.../trace/` | ✅ 已验证 |
| `VehicleStatusPreProcessor.VehicleStatusProvider` 是 `public @FunctionalInterface` | ✅ 已验证可访问 |
| `ToolGroupRegistry.group(ToolGroupId)` 可获取 group 描述 | ✅ |

---

## 五、额外观察项

### 5.1 Task 6.2 测试应明确命名区分"tool group selection"和"context frame"

Task 6.2 的 4 个测试断言的是 `session.toolGroupSelectionResult()` 而非 `ContextFrame.selectedToolNames()`，因此它们本质上是在验证 ToolGroupSelector 的映射规则（已在 Phase 2 验证过），而非 Context 模块的功能。建议将测试类名改为 `AgentRuntimeToolGroupSelectionTest`，或在测试中额外断言 `frame.selectedToolNames()` 的一致性。

### 5.2 renderedExtraContext 覆盖 AgentRequest.getExtraContext() 的潜在影响

`ContextFrame.toOrchestratorContext()` 中：
```java
if (!renderedExtraContext.isEmpty()) {
    merged.put("extra_context", renderedExtraContext);
}
```

若调用方之前通过 `AgentRequest.setExtraContext()` 传入了自定义 extra_context，该值会被 context 模块的 renderedExtraContext 覆盖。鉴于本阶段目标就是让 Context 模块成为 extra_context 的唯一来源，此行为符合预期。但建议在 release notes 中注明此变更。

### 5.3 ContextOrchestrator 生命周期

`ContextOrchestrator` 被设计为无状态（依赖全在 `ContextBuildInput` 中），因此可以在 Service 初始化时创建一次、全局复用。这是正确的设计，不需要 per-request 创建。

---

## 六、审查结论

**有条件通过。** 请在实施前处理以下 3 个阻塞问题：

1. **包依赖倒挂** — `ContextBuildInput` 改用独立接口或 `Supplier<String>`，不直接引用 `VehicleStatusPreProcessor.VehicleStatusProvider`。
2. **prompt 映射重复** — 将 persona→template 映射集中到 `PromptConstants`，消除 `PromptContextProvider` 和 `AgentConfigFactory` 两处的硬编码。
3. **runtimeWithContextCapture() 未定义** — 补充辅助方法实现，并确认测试输入文本对应的关键词规则匹配结果。

上述问题修正后即可进入 Phase 1 实施。
