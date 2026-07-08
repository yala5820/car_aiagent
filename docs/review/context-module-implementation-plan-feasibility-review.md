# Context 模块实现计划可行性审查报告

**审查日期：** 2026-07-07  
**审查范围：**
- `docs/plan/context_demo_plan.md`
- `docs/plan/context-module-implementation-plan.md`
- 当前源码中的 `AgentRuntime` / `AgentExecutor` / `AgentLoopOrchestrator` / `AIAgentService` / ToolGroup / Trace 相关实现

**审查方法：** 使用 `superpowers:writing-plans` 的计划质量标准，按“目标覆盖、接口一致性、边界约束、测试可执行性、失败路径”逐项核对。

---

## 一、总体结论

当前实现计划**不建议直接进入实施**。计划的分阶段结构、文件拆分、HYBRID 兼容策略总体方向是合理的，但仍存在 4 个会影响一期目标达成或导致编译失败的实质问题。

最关键的问题是：计划把 `renderedExtraContext` 写入 `extra_context`，但当前 `AgentLoopOrchestrator.execute(userInput, extraContext)` 并不会把 `extra_context` 文本转成 `ChatMessage` 注入给 LLM。也就是说，按当前计划完成后，ContextFrame 会构建、Trace 也可能可见，但 HYBRID 上下文文本实际不会进入模型输入，无法满足“prompt / extraContext 只出现 selected tools”等目标。

---

## 二、阻塞问题

### 问题 1：`renderedExtraContext` 不会真正进入 LLM 输入

**涉及位置：**
- `docs/plan/context-module-implementation-plan.md`：`ContextFrame.toOrchestratorContext()` 将 `renderedExtraContext` 写入 `context_rendered_extra` 和 `extra_context`
- `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java`
- `app/src/main/java/com/hirain/aiagent/core/AgentLoopContext.java`

**现状：**
`AgentLoopOrchestrator.execute()` 只是把 `extraContext` 放入 `AgentLoopContext`。真正进入 LLM 的临时消息来自各个 `PreProcessor.prepare(ctx)`，当前 TEXT persona 的 preprocessor 是 `MemoryPreProcessor`、`VehicleStatusPreProcessor`、`TimeContextPreProcessor`。源码中没有通用逻辑读取 `extra_context` / `context_rendered_extra` 并生成 `UserMessage`。

**风险：**
计划即使完成，`ContextFrame.renderedExtraContext` 也只是 Map 里的调试数据，不会影响 LLM 上下文。这样会导致以下验收项失真：

- `renderedExtraContext` 只出现 selected tools
- 普通聊天不渲染全量 47 个工具说明
- HYBRID_EXTRA_CONTEXT 作为兼容上下文接入 AgentLoop

**建议：**
实施前必须补一项明确设计，二选一：

1. 新增 `ContextExtraPreProcessor`，只在首轮读取 `context_rendered_extra` 并生成一条 `UserMessage`。这是最符合现有 AgentLoop 架构的方案。
2. 修改 `AgentLoopOrchestrator` 的消息组装逻辑，直接把 `context_rendered_extra` 加入 `transientMessages`。改动更集中，但会让 core 直接认识 context key。

推荐方案 1，并把“不重复注入”测试升级为“模型请求 transientMessages 中包含 Context section，但不包含 memory/vehicle/time/system prompt”。

---

### 问题 2：取消检查无法满足“Context 构建后、AgentLoop 前”语义

**涉及位置：**
- `docs/plan/context_demo_plan.md`：要求 Context 构建前后配合 `ActiveRequestRegistry` 检查取消状态，已取消则不进入 AgentLoop
- `docs/plan/context-module-implementation-plan.md`：Task 5.2 只在 `runtime.execute()` 前和返回后检查 `activeRequest.isCancelled`
- `app/src/main/java/com/hirain/aiagent/AIAgentService.kt`
- `app/src/main/java/com/hirain/aiagent/runtime/AgentRuntime.java`

**现状：**
计划把 `ContextOrchestrator.build(session)` 放进 `AgentRuntime.execute(session)` 内部。Service 层只能在调用 `runtime.execute()` 前检查一次，在 `runtime.execute()` 返回后再检查一次。若取消发生在 Context 构建完成后、`chatExecutor.execute(session, contextFrame)` 前，Service 层没有插入点阻止进入 AgentLoop。

**风险：**
计划能阻止 late success 响应，但不能保证“取消后不继续进入 AgentLoop”。这与目标文档第 17 条存在语义差距。

**建议：**
实施前需要用户确认其中一个方案：

1. `AgentRuntime` 增加可注入取消检查器，在 `contextOrchestrator.build(session)` 后、`chatExecutor.execute(...)` 前检查，取消时返回 `RuntimeResult.cancelled(...)`。
2. 将 Context 构建显式拆到 Service worker 中：Service 调用 build，检查取消，再调用 runtime 执行。这样会削弱“AgentRuntime 统一协调 Context”的边界。
3. 放宽验收语义，只要求 late result suppression，不要求阻止 AgentLoop 启动。若选此项，需要修改目标文档。

推荐方案 1，改动最小且保留 Runtime 统一入口。

---

### 问题 3：`AgentRuntime` 构造函数计划与测试代码不一致

**涉及位置：**
- `docs/plan/context-module-implementation-plan.md` Task 4.2
- `docs/plan/context-module-implementation-plan.md` Task 6.2

**现状：**
计划中的测试使用：

```java
new AgentRuntime(executor, contextOrchestrator, () -> "req-1", () -> 1000L)
```

但 Task 4.2 列出的最终构造函数组合中没有这个四参签名，只列出了：

```java
AgentRuntime(AgentExecutor, ContextOrchestrator)
AgentRuntime(AgentExecutor, IdGenerator, TimeProvider)
AgentRuntime(AgentExecutor, ContextOrchestrator, IntentRouter, ToolGroupSelector, IdGenerator, TimeProvider)
```

**风险：**
按计划执行到 `AgentRuntimeContextTest` 或 `runtimeWithContextCapture()` 时会直接编译失败。

**建议：**
二选一：

1. 增加四参构造函数 `AgentRuntime(AgentExecutor, ContextOrchestrator, IdGenerator, TimeProvider)`。
2. 修改所有测试，使用六参全量构造函数。

推荐方案 1，测试可读性更好，也符合当前已有 `AgentRuntime(AgentExecutor, IdGenerator, TimeProvider)` 的测试注入习惯。

---

### 问题 4：`selected tool descriptions` 目标被降级，计划与目标边界不一致

**涉及位置：**
- `docs/plan/context_demo_plan.md`：要求 `renderedExtraContext` 只能渲染 `selectedToolNames` 对应工具说明，并限制 selected tool descriptions 长度
- `docs/plan/context-module-implementation-plan.md`：Task 2.3 明确一期只渲染 selected group 描述 + selected tool names，不读取 `ToolSpecification.description`

**现状：**
当前 `ToolGroupRegistry` 只有 group 描述和 toolName 列表；真实工具描述存在于 `ai.langchain4j.tool.ToolRegistry.getToolSpecifications()` 返回的 LangChain4j `ToolSpecification` 中。计划将完整 selected tool specification 渲染推迟到后续阶段。

**风险：**
如果目标文档里的 “selected tool descriptions” 指的是每个 `@Tool` 的真实说明，那么当前计划没有覆盖该目标。只渲染 toolName 和 group description 对 LLM 帮助有限，尤其是多个相似工具如空调温度、风量、出风口控制时，模型仍缺少参数语义。

**建议：**
需要用户确认目标口径：

1. 若一期必须满足“selectedToolNames 对应工具说明”，则 `ContextBuildInput` 应增加只读的 `List<ToolSpecification>` 或查询接口，`ToolGroupContextProvider` 按 `selectedToolNames` 过滤并预算裁剪。注意这不等于动态绑定工具集合，不需要改 `ModelCaller`。
2. 若一期只要求观测和轻量提示，则应修改 `context_demo_plan.md`，把 “selected tool descriptions” 改成 “selected tool names + selected group descriptions”。

推荐方案 1，范围仍可控，且更符合原始目标。

---

## 三、重要非阻塞问题

### 3.1 测试没有证明 Context 文本进入模型请求

计划中的测试主要验证 `ContextFrame`、Map key、Trace attribute 和 selectedToolNames 一致性。即使这些测试全过，也不能证明 LLM 的 `ChatRequest.messages()` 中出现了 Context 文本。

建议新增 `AgentLoopOrchestrator` 级别测试，使用 fake `ModelCaller` 捕获 `ChatRequest.messages()`，断言：

- 首轮 transient message 包含 `【运行时上下文】` / `【意图上下文】` / `【工具组上下文】`
- 不包含 system prompt、长期记忆、车辆状态、当前时间的重复注入
- OBSERVE_ONLY 模式下不出现 Context transient message

### 3.2 `AgentRequest.extraContext["extra_context"]` 被覆盖需要确认

计划文档已经在阶段总结模板里说明：`ContextFrame.toOrchestratorContext()` 会覆盖调用方原有 `extra_context`。这是行为变更。虽然当前 AgentLoop 不读取该 key，但外部调用方或后续逻辑可能依赖它。

建议实施前确认：

- 是覆盖旧值；
- 还是保留旧值到 `caller_extra_context`，Context 文本写入 `context_rendered_extra`；
- 或将两者拼接，但需要明确顺序和预算。

### 3.3 `PromptContextProvider` 是否“复用 PromptManager”的口径不清

目标文档要求 `PromptContextProvider` 必须复用现有 `PromptManager`。实现计划为了避免重复 system prompt 注入，只复用 `PromptConstants.textPersonaTemplateName()`，并不调用 `PromptManager.render()`。

这个选择从“不重复注入”角度是合理的，但需要把目标口径明确为“复用现有 prompt 命名和映射，不重新渲染 system prompt”。否则后续验收可能认为没有满足 “复用 PromptManager”。

### 3.4 `MemoryContextProvider` 的 memorySummary 为空可能不满足“ContextFrame 包含 memoryContext 或 memorySummary”

目标文档要求 `ContextFrame` 至少包含 `memoryContext` 或 `memorySummary`，同时又要求 HYBRID 模式不重复注入 memory。计划当前做法是 metadata 记录 memory owner，`memorySummary=""`。

建议明确验收口径：一期是否允许 `memorySummary` 为空但带 metadata，还是必须提供不参与渲染的摘要字段。若需要真实摘要，应只读复用 `MemoryOrchestrator` 的长期记忆摘要，不拼接完整短期 ChatMemory。

---

## 四、计划中合理的部分

- 新增 `context` 包和 provider 链的分层方向合理，符合“ContextFrame 统一承载上下文元信息”的目标。
- `ContextFrame` 以 `RequestSession` 为核心输入，不重新生成 requestId/userId/sessionId/personaId/clientMessageId，这一点符合边界。
- `VehicleStatusProvider` 已改为 context 包内独立接口，避免依赖 `VehicleStatusPreProcessor` 内部接口。
- `PromptConstants.textPersonaTemplateName(personaId)` 集中维护 TEXT persona 到 prompt 模板的映射，避免 `AgentConfigFactory` 和 `PromptContextProvider` 重复硬编码。
- 不在一期重构 LangChain4j 动态工具绑定是合理的；当前 `AgentLoopOrchestrator` 的 `effectiveToolSpecs` 在构造时确定，动态绑定应作为单独阶段设计。
- Trace 字段设计基本可落地，`TraceSession.setAttribute(String, String/long/boolean)` 已能支持计划中的 root span attribute 写入。

---

## 五、建议的实施前修订清单

1. 增加 Context 文本进入 LLM 的明确路径，优先新增 `ContextExtraPreProcessor`。
2. 增加 Runtime 内部取消检查机制，确保 Context build 后、AgentLoop 前仍可停止。
3. 补齐或调整 `AgentRuntime(AgentExecutor, ContextOrchestrator, IdGenerator, TimeProvider)` 构造函数。
4. 明确 selected tool descriptions 是否必须来自 `ToolSpecification.description`；若是，将只读 tool spec 查询纳入 ContextBuildInput。
5. 增加 AgentLoop/ModelCaller 级别测试，证明 Context transient message 真的进入 `ChatRequest.messages()`。
6. 明确 `extra_context` 覆盖策略，避免隐藏破坏调用方自定义上下文。
7. 明确 `PromptContextProvider` 和 `MemoryContextProvider` 在 HYBRID 模式下的验收口径。

---

## 六、结论

该计划可以作为 Context 模块一期实施的基础，但必须先修订上述阻塞项。尤其是 `renderedExtraContext` 注入路径和取消检查插入点，如果不调整，实施结果会出现“ContextFrame 可观测但不真正影响 Agent 输入”的空转问题，也无法严格满足“已取消不进入 AgentLoop”的边界要求。
