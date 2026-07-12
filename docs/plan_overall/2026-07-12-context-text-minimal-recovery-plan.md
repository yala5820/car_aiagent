# Context TEXT 唯一输入权最小恢复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不继续扩大重构范围的前提下，恢复当前工程可编译状态，并确保 TEXT 请求进入 LLM 的 `ChatMessage` 与 `ToolSpecification` 只能由 Context 模块生成。

**Architecture:** Prompt、Memory、Tool、Vehicle、Time 等模块继续拥有各自的实现能力，Provider 只负责读取这些模块并转换成 `ContextContribution`；`ContextOrchestrator + ContextMessageAssembler` 是 TEXT 模型输入的唯一装配入口；`TextAgentLoopOrchestrator` 只消费 `ContextAssemblyResult`，不自行拼接 Prompt、历史消息或固定工具。当前不修复生产裁剪和压缩协议，只保留 Token 估算与超限前置失败，避免继续引入数据破坏风险。

**Tech Stack:** Java/Kotlin、Android Service/AIDL、LangChain4j 1.16.3、JUnit4、OpenTelemetry、SQLite ChatMemoryStore、现有 Prompt/Memory/ToolGroup/ToolRegistry 模块。

---

## 1. 计划定位

### 1.1 决策来源

本计划采用已确认的“方案 A：最小恢复方案”。它取代
`docs/plan_overall/2026-07-11-context-full-control-remaining-issues-remediation-plan.md`
中尚未完成的后续工作。旧计划只作为问题来源和历史记录，不再继续执行 Phase 4，也不得继续批量删除 Provider、Section 或兼容结构。

### 1.2 当前已确认状态

- 当前 `compileDebugJavaWithJavac` 失败，首批报告 55 个 Provider 语法错误。
- `CallerExtraContextProvider`、`LongTermMemoryContextProvider`、`SessionMemoryContextProvider` 的旧 `type()` 方法签名已被删掉，但残留了孤立的 `@Override / return ContextSectionType.X; / }` 代码片段；修复动作是删除整个残留片段和旧 import，不是恢复 `type()`。
- 其余多个 Provider 被回退到旧 `ContextSection`/HYBRID 实现，与当前 `ContextProvider` 和 `ContextProviderResult` 契约不兼容。
- `TextAgentLoopOrchestrator`、`ContextMessageAssembler`、Contribution 类型和 Service TEXT wiring 已经存在，应在其上做最小修复，不重新设计第二套链路。
- 当前预算裁剪算法不能可靠保护当前 UserMessage 和多工具交换；当前生产压缩会错误删除 Store 中刚写入的消息。
- 当前工作区包含大量未提交和未跟踪文件，任何破坏性 Git 操作都可能再次丢失已完成工作。

### 1.3 本轮完成定义

只有同时满足以下条件，才能宣布本计划完成：

1. 工程重新通过 Java/Kotlin 编译、JVM 测试、`assembleDebug` 和 `lintDebug`。
2. TEXT 生产路径的最终 `ChatRequest.messages()` 和 `toolSpecifications()` 只来自 `ContextAssemblyResult`。
3. Prompt、当前用户、SessionMemory、长期记忆、车辆、时间、caller extra 和 ToolSpecification 均有明确 Provider 所有权。
4. Session 切换隔离短期历史；同一 Session 切换用户保留短期历史但切换长期记忆；Persona 切换只切换当前 System Prompt。
5. required Provider 失败、消息序列非法、预算超限和取消均在模型调用前或安全检查点终止。
6. 生产 Context 不调用 `ContextBudgetManager.makeDecision()`、`planSessionCompaction()` 或 `executeCompactionPlan()`。
7. IMAGE、VOICE、CONTROL、SCENE、VL 的生产实现与测试没有被修改。

---

## 2. 固定工作边界

### 2.1 本轮必须完成

- 手工恢复全部 Context Provider 的可编译状态和 Contribution 输出。
- 恢复 request-static / iteration-dynamic Provider 生命周期。
- 恢复 required Provider 的 SUCCESS/FALLBACK/FAILED 判定。
- 确保 ContextMessageAssembler 是唯一消息和工具装配器。
- 修复多工具取消闭合和 ContextErrorCode 映射。
- 阻止超时请求在响应超时后继续写 AiMessage 或执行新工具。
- 停用生产预算裁剪和生产压缩，仅保留估算与超限失败。
- 增加能够运行真实 Context + Text Loop 的行为测试。
- 更新 Context overview、阶段总结和最终测试结果。

### 2.2 本轮明确不做

- 不删除 `ContextSection`、`ContextSectionType`、`ContextDebugInfo`、`ContextFrame` 旧字段。
- 不删除旧字符预算 API，不继续 Phase 4 技术债务清理。
- 不完善 `ContextBudgetManager.makeDecision()` 的裁剪算法。
- 不实现摘要、压缩重读、二次 assemble 或自动恢复。
- 不修改 Memory 摘要 Prompt、MemoryExtractor、长期记忆存储算法。
- 不统一或删除 IMAGE、VOICE、CONTROL、SCENE、VL。
- 不修改 AIDL 字段、AgentRequest/AgentResponse Parcelable 协议。
- 不调整 ToolGroupSelector 的规则匹配逻辑和 allToolsFallback 决策。
- 不引入 tokenizer、Mockito、Robolectric 或其他新依赖。
- 不进行与 Context TEXT 恢复无关的格式化和重构。

### 2.3 强制操作纪律

- 禁止运行 `git checkout --`、`git reset`、`git clean`、批量覆盖 Provider 目录等破坏性命令。
- 禁止使用 awk/sed/正则批量重写 Java 类；每个 Provider 必须单独读取、单独修改、单独核对。
- 不得把当前 untracked 文件当作可丢弃生成物。
- 每个 Task 开始前记录 `git status --short`；结束后仅列出本 Task 实际修改的文件。
- 当前基线已知无法编译，因此 Phase 1 允许先修复语法和契约再运行测试；从首次编译成功开始，后续 Task 必须先写失败测试再改生产代码。
- 任一 Task 若需要修改非 TEXT 生产文件才能继续，立即停止并报告，不自行扩大边界。

---

## 3. 最终调用链与所有权

```text
AgentRequest(TEXT)
  -> AgentRuntime.startSession()
  -> ContextOrchestrator.prepare()
       -> request-static Providers
       -> ContextPrepareResult
  -> TextAgentLoopOrchestrator.execute()
       -> 将当前 UserMessage 写入 session ChatMemory 一次
       -> ContextOrchestrator.assemble()
            -> iteration-dynamic Providers
            -> ContextMessageAssembler
            -> ContextAssemblyResult(messages, toolSpecifications, budgetReport)
       -> ChatRequest.builder()
            .messages(assemblyResult.messages())
            .toolSpecifications(assemblyResult.toolSpecifications())
       -> ModelCaller
       -> SafetyGuard -> ToolExecutor -> ToolResult
```

### 3.1 禁止旁路

TEXT 生产路径中不得出现以下行为：

- AgentLoop 调用 `PromptManager.render()` 或创建 `SystemMessage`。
- AgentLoop 把 `chatMemory.messages()` 作为独立参数传给 Assembler。
- AgentLoop 使用 `config.toolSubset()`、`effectiveToolSpecs` 或 Service 启动时的固定工具列表构造 ChatRequest。
- PreProcessor 向 TEXT ChatRequest 添加 Prompt、Memory、Vehicle、Time 或 Tool 文本。
- Context 直接执行 Tool、调用主对话模型或写入 Memory Store。

### 3.2 Provider 输出矩阵

| Provider | 生命周期 | required 规则 | 输出 | 异常处理 |
|---|---|---|---|---|
| RuntimeContextProvider | REQUEST_STATIC | TEXT 始终 required | TRACE_ONLY/POLICY_ONLY 诊断 Contribution；校验 requestId/sessionId/userId/personaId | 缺少必需身份返回 FAILED |
| PersonaContextProvider | REQUEST_STATIC | optional | POLICY_ONLY Contribution，记录 effective persona | 缺失时使用 Runtime 已规范化 persona，不向模型拼文本 |
| PromptContextProvider | REQUEST_STATIC | 始终 required | 唯一 `TARGET_SYSTEM`、`TRUSTED_SYSTEM`、MODEL_VISIBLE TextContribution | manager/模板/渲染/空结果返回 FAILED |
| UserInputContextProvider | REQUEST_STATIC | 始终 required | `SOURCE_CURRENT_USER` MessageContribution | null/blank 返回 FAILED |
| IntentContextProvider | REQUEST_STATIC | optional | POLICY_ONLY Contribution | 缺失时 optional fallback |
| ToolGroupContextProvider | REQUEST_STATIC | 非 CHAT_ONLY 时 required | ToolContextContribution，承载真实 ToolSpecification | registry 缺失或名称解析失败返回 TOOL_SPEC_RESOLUTION_FAILED |
| LongTermMemoryContextProvider | REQUEST_STATIC | optional | 当前 userId 的长期记忆 CONTEXT_DATA | 读取失败 optional fallback，不混入 SystemMessage |
| CallerExtraContextProvider | REQUEST_STATIC | optional | UNTRUSTED_DATA CONTEXT_DATA | 缺失返回空 SUCCESS，读取异常 fallback |
| SessionMemoryContextProvider | ITERATION_DYNAMIC | TEXT 始终 required | `SOURCE_SESSION_MEMORY` MessageContribution | gateway/sessionId/读取失败返回 FAILED |
| VehicleStateContextProvider | ITERATION_DYNAMIC | requiredContextKeys 含 vehicle_status 时 required | TRUSTED_DATA CONTEXT_DATA | required 时失败；普通聊天允许 fallback |
| TimeContextProvider | ITERATION_DYNAMIC | optional | TRUSTED_DATA CONTEXT_DATA | 失败 optional fallback |

`MemoryContextProvider` 不在 `defaultForText()` 中，生产代码对它为零引用，真实长期/短期职责已经分别由
`LongTermMemoryContextProvider` 和 `SessionMemoryContextProvider` 承担。本轮直接删除该旧类及其过时测试断言，
不再维护一个永不进入生产链的 compatibility Provider。

### 3.3 简化预算契约

- `ContextMessageAssembler` 使用现有 `ContextTokenEstimator` 估算消息和完整 Tool Schema。
- `ContextBudgetReport.withinBudget=true`：Text Loop 可以调用模型。
- `withinBudget=false`：Text Loop 返回 `CONTEXT_BUDGET_EXCEEDED`，模型调用次数必须为 0。
- 本轮不做裁剪、不做压缩、不修改历史消息。
- `ContextBudgetManager.makeDecision()` 和 Memory compaction API 可以保留，但必须脱离生产调用链，并在注释中标明“当前未启用”。

---

## 4. 文件责任映射

**路径约定：** 下文 `context/...`、`core/...`、`runtime/...`、`memory/...` 均相对于
`app/src/main/java/com/hirain/aiagent/`；测试路径均相对于
`app/src/test/java/com/hirain/aiagent/`。执行者必须按完整路径定位文件，不得因为同名类或旧文档中的历史路径修改错误文件。

### 4.1 主要生产文件

- `app/src/main/java/com/hirain/aiagent/context/ContextProvider.java`
- `app/src/main/java/com/hirain/aiagent/context/ContextProviderResult.java`
- `app/src/main/java/com/hirain/aiagent/context/ContextOrchestrator.java`
- `app/src/main/java/com/hirain/aiagent/context/ContextMessageAssembler.java`
- `app/src/main/java/com/hirain/aiagent/context/ContextMessageSequenceValidator.java`
- `app/src/main/java/com/hirain/aiagent/context/ContextAssemblyResult.java`
- `app/src/main/java/com/hirain/aiagent/context/provider/*.java`
- `app/src/main/java/com/hirain/aiagent/core/TextAgentLoopOrchestrator.java`
- `app/src/main/java/com/hirain/aiagent/runtime/AgentRuntime.java`
- `app/src/main/java/com/hirain/aiagent/runtime/RuntimeResponseMapper.java`
- `app/src/main/java/com/hirain/aiagent/AIAgentService.kt`
- `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java`
- `app/src/main/java/com/hirain/aiagent/memory/SessionChatMemoryProvider.java`

### 4.2 主要测试文件

- `app/src/test/java/com/hirain/aiagent/context/ContextProviderRequiredPolicyTest.java`
- `app/src/test/java/com/hirain/aiagent/context/ContextMessageAssemblerTest.java`
- `app/src/test/java/com/hirain/aiagent/context/ContextMessageSequenceValidatorTest.java`
- `app/src/test/java/com/hirain/aiagent/context/ContextCancellationAtomicityTest.java`
- `app/src/test/java/com/hirain/aiagent/context/ContextMinimalBudgetGuardTest.java`（新增）
- `app/src/test/java/com/hirain/aiagent/core/TextAgentLoopOrchestratorTest.java`
- `app/src/test/java/com/hirain/aiagent/runtime/AgentRuntimeContextTest.java`
- `app/src/test/java/com/hirain/aiagent/runtime/RuntimeResponseMapperTest.java`
- `app/src/test/java/com/hirain/aiagent/runtime/ContextTextEndToEndTest.java`（新增）
- `app/src/test/java/com/hirain/aiagent/trace/ContextProductionTraceHierarchyTest.java`

---

## 5. Phase 1：恢复编译与 Provider 契约

**阶段目标：** 修复损坏和回退的 Provider，使生产 Context 链重新编译，并恢复全部 Provider 的 Contribution、生命周期和 required 语义。

**阶段边界：** 只修复 Context Provider 及直接编译引用；不删除旧类型、不修改 AgentLoop 行为、不启用预算或压缩。

### Task 1.1：修复 Provider 文件结构和统一接口

**生产文件：**

- Modify: `context/provider/CallerExtraContextProvider.java`
- Modify: `context/provider/LongTermMemoryContextProvider.java`
- Modify: `context/provider/SessionMemoryContextProvider.java`
- Modify: `context/provider/RuntimeContextProvider.java`
- Modify: `context/provider/PromptContextProvider.java`
- Modify: `context/provider/IntentContextProvider.java`
- Modify: `context/provider/PersonaContextProvider.java`
- Modify: `context/provider/TimeContextProvider.java`
- Delete: `context/provider/MemoryContextProvider.java`
- Modify: `context/provider/ToolGroupContextProvider.java`
- Verify only: `context/provider/UserInputContextProvider.java`
- Verify only: `context/provider/VehicleStateContextProvider.java`
- Verify only: `context/ContextProvider.java`
- Verify only: `context/ContextProviderResult.java`
- Modify: `app/src/test/java/com/hirain/aiagent/context/ContextOrchestratorTest.java`（删除只验证旧 MemoryContextProvider section/renderable 的断言）

**实施步骤：**

- [ ] 对 `CallerExtraContextProvider`、`LongTermMemoryContextProvider`、`SessionMemoryContextProvider`，删除从孤立 `@Override` 到紧随其后的 `}` 的完整残片；同时删除 `ContextSection`、`ContextSectionType` 和未使用 `ContextContribution/ContextProviderStatus` import。禁止补写或恢复 `type()` 方法。
- [ ] 逐个打开保留的 11 个 Provider，确认每个类完整包含 `name()`、`lifecycle()`、`required(session,input)`、`provide(session,input)`。
- [ ] 所有 Provider 停止调用旧 `ContextProviderResult.success(name, section)` 和 `fallback(name, section, reason)` 签名，统一使用当前 contributions 工厂。
- [ ] 暂时保留 `ContextSection` 等类型文件；Provider 可以删除不再使用的 import，但不得删除旧类型本身。
- [ ] 全仓确认 `MemoryContextProvider` 的生产引用为零后删除该文件；从 `ContextOrchestratorTest` 删除旧 import 和 `section().renderable()` 断言，不新增空替代测试。历史文档引用留到 Phase 3 overview 更新，不为删除旧类批量改写所有历史文档。
- [ ] 每修复 2-3 个 Provider，使用 `compileDebugJavaWithJavac` 获取下一批真实错误；不得一次性猜测修改所有报错。

**禁止事项：** 不恢复 HYBRID 注入逻辑，不重新引入 `type()` 接口，不从 Provider 写 Store，不通过返回空 null 掩盖编译问题。

**验证命令：**

```powershell
.\gradlew.bat compileDebugJavaWithJavac --rerun-tasks
```

**完成证据：** Java 编译成功；三个损坏文件中 `ContextSectionType` 引用为零；Provider 目录搜索旧工厂签名为零；保留的 11 个 Provider 都实现当前四方法契约；生产与测试源码中 `MemoryContextProvider` 引用为零。

### Task 1.2：恢复真实静态 Provider 输出

**生产文件：**

- Modify: `context/provider/RuntimeContextProvider.java`
- Modify: `context/provider/PersonaContextProvider.java`
- Modify: `context/provider/PromptContextProvider.java`
- Modify: `context/provider/UserInputContextProvider.java`
- Modify: `context/provider/IntentContextProvider.java`
- Modify: `context/provider/ToolGroupContextProvider.java`
- Modify: `context/provider/LongTermMemoryContextProvider.java`
- Modify: `context/provider/CallerExtraContextProvider.java`
- Test: `context/ContextProviderRequiredPolicyTest.java`
- Test: `context/provider/ToolGroupContextProviderTest.java`

**实施要求：**

- [ ] Runtime Provider 校验规范化后的 requestId/sessionId/userId/personaId；内容只用于 Trace/Policy，不作为 System Prompt 或 Context Data 注入。
- [ ] Prompt Provider 根据 `session.personaId()` 选择模板名，调用 `input.promptManager().render()`，产生唯一、非空、可信的 System Contribution。
- [ ] UserInput Provider 只产生一个 CURRENT_USER UserMessage；不得把同一用户输入同时作为 TextContribution 注入。
- [ ] ToolGroup Provider 必须先判断 CHAT_ONLY：CHAT_ONLY 在读取 ToolRegistry、解析 toolName 之前直接返回 MODE_NONE 空集合和 SUCCESS，不得返回 FALLBACK/FAILED，也不得因 Registry 为 null 抛异常。
- [ ] 非 CHAT_ONLY 再根据 `ToolGroupSelectionResult.selectedToolNames()` 从真实 `ToolRegistry` 解析规格；allToolsFallback 返回 Registry 的完整启用集合；明确名称缺失必须失败，不能悄悄少工具。
- [ ] LongTermMemory Provider 只调用 `memoryGateway.longTermMemorySnapshot(session.userId())`；长期记忆进入不可信 Context Data，不进入 SystemMessage。
- [ ] Caller extra 始终标记 `UNTRUSTED_DATA`，只能进入 Context Data。
- [ ] Persona 和 Intent 只提供 Policy/Trace Contribution，不重复向模型解释内部路由元数据。

**关键测试：**

- PromptManager 为 null、render 抛异常、返回空字符串时，Provider 返回 FAILED。
- chat/friendly/concise 分别渲染对应模板，且每次只有一个 System Contribution。
- CHAT_ONLY 工具为空且 SUCCESS。
- selected 模式精确返回选中 ToolSpecification。
- allToolsFallback 返回完整启用工具集合。
- 任一 selected toolName 在 Registry 缺失时返回 `TOOL_SPEC_RESOLUTION_FAILED`。
- 长期记忆按 userId 读取，user A 与 user B 输出不同。

**完成证据：** 静态 Provider 测试通过；ContextMessageAssembler 可以从静态 contributions 找到唯一 System 和 ToolContribution。

### Task 1.3：恢复动态 Provider 与 required 终止语义

**生产文件：**

- Modify: `context/provider/SessionMemoryContextProvider.java`
- Modify: `context/provider/VehicleStateContextProvider.java`
- Modify: `context/provider/TimeContextProvider.java`
- Modify: `context/ContextOrchestrator.java`
- Modify: `context/ContextPrepareResult.java`（仅在需要保留失败 outcomes 时）
- Modify: `context/ContextAssemblyResult.java`（仅在需要保留失败 outcomes 时）
- Test: `context/ContextProviderRequiredPolicyTest.java`
- Test: `context/ContextProviderFailureTest.java`
- Test: `context/ContextOrchestratorTest.java`

**实施要求：**

- [ ] SessionMemory Provider 每次 assemble 按当前 sessionId 调用快照接口；允许成功空历史，不允许 gateway/sessionId/读取异常 fallback。
- [ ] Vehicle Provider 根据 Registry 聚合的 requiredContextKeys 动态计算 required；required 时空状态或异常必须 FAILED，普通聊天时允许 optional fallback。
- [ ] Time Provider 每轮读取时间，异常只作为 optional fallback。
- [ ] `ContextOrchestrator.prepare()` 和 `assemble()` 使用同一规则：`required=true` 时只有 `status == SUCCESS` 可以继续，FALLBACK 和 FAILED 均终止。
- [ ] 动态 Provider 的异常不得只记录后继续；required 动态异常必须返回稳定 ContextErrorCode。
- [ ] required failure 返回前保留已执行 Provider outcomes 和失败 Provider 名称。
- [ ] optional fallback 不应阻塞后续 Provider 和 Assembler。

**阶段验证：**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.context.*" --rerun-tasks
.\gradlew.bat compileDebugJavaWithJavac --rerun-tasks
```

**阶段退出状态：** Context 包与 Provider 测试通过；required 失败时模型入口尚未被调用；当前工程重新具备可编译基线。

---

## 6. Phase 2：恢复唯一输入权并关闭危险旁路

**阶段目标：** 证明 TEXT ChatRequest 只消费 ContextAssemblyResult，同时修复取消、错误码和超时执行边界；停用不安全裁剪与压缩。

**阶段边界：** 允许修改 TEXT Loop、Context assemble、Runtime 取消映射和 Memory 压缩写回的明显删除错误；不重新设计压缩算法，不修改非 TEXT Loop。

### Task 2.1：锁定 ContextMessageAssembler 唯一所有权

**生产文件：**

- Modify: `context/ContextMessageAssembler.java`
- Modify: `context/ContextMessageSequenceValidator.java`
- Modify: `context/ContextOrchestrator.java`
- Modify: `core/TextAgentLoopOrchestrator.java`
- Verify: `AIAgentService.kt`
- Test: `context/ContextMessageAssemblerTest.java`
- Test: `context/ContextMessageSequenceValidatorTest.java`
- Test: `core/TextAgentLoopOrchestratorTest.java`

**实施要求：**

- [ ] Assembler 固定按“唯一 System -> 可选 Context Data -> SessionMemory”生成消息，并从 ToolContribution 生成工具规格。
- [ ] CURRENT_USER 由 Text Loop 写入当前 session ChatMemory 一次；Assembler 只读取 SessionMemory Provider 快照，最终请求中当前用户只能出现一次。
- [ ] Assembler 拒绝缺失/重复 System、缺失/重复 SessionMemory、重复工具名不同 Schema 和非法 ToolExchange。
- [ ] Validator 对 ToolResult 的 id 和 toolName 都执行非空、精确匹配；null toolName 不能绕过校验。
- [ ] Text Loop 构建 ChatRequest 时只读取 `assemblyResult.messages()` 和 `assemblyResult.toolSpecifications()`。
- [ ] Text Loop 创建 `ContextAssemblyRequest` 时必须使用包含 `RequestSession session` 的六参数构造器；生产代码不得使用把 session 设为 null 的五参数兼容构造器。
- [ ] ContextOrchestrator.assemble() 从 `request.session()` 取得动态 Provider 的唯一 RequestSession；request 或 session 为 null 时返回 `CONTEXT_INTERNAL_ERROR`，不得进入 Vehicle/Time/SessionMemory Provider 后触发空指针。
- [ ] Text Loop 不调用 PromptManager、不读取 `chatMemory.messages()` 作为模型输入、不使用 config.toolSubset/effectiveToolSpecs。
- [ ] Service 继续只为 TEXT 创建 `TextAgentLoopOrchestrator`；旧 AgentLoop 保持冻结。

**必须测试：**

- 空历史、单轮历史、多轮工具历史均能正确装配。
- 当前用户输入只出现一次。
- Provider 快照与 Loop 本地假数据不一致时，最终 ChatRequest 使用 Provider 快照。
- selected tools 原样进入 ChatRequest；CHAT_ONLY 为零工具；all fallback 为完整集合。
- wrong id、null name、wrong name、duplicate result、missing result 全部失败。

**完成证据：** 全仓 TEXT ChatRequest 构造点只有 Text Loop 一处，且 messages/tools 参数均来自同一个 ContextAssemblyResult。

### Task 2.2：修复取消闭合、错误映射和超时停止

**生产文件：**

- Modify: `core/TextAgentLoopOrchestrator.java`
- Modify: `runtime/AgentRuntime.java`
- Modify: `runtime/RuntimeResponseMapper.java`
- Modify: `AIAgentService.kt`（仅 TEXT RuntimeCancelChecker）
- Test: `context/ContextCancellationAtomicityTest.java`
- Test: `core/TextAgentLoopOrchestratorTest.java`
- Test: `runtime/AgentRuntimeContextTest.java`
- Test: `runtime/RuntimeResponseMapperTest.java`

**实施要求：**

- [ ] 模型返回后、写 AiMessage 前检查取消；取消时丢弃模型结果。
- [ ] AiMessage 已持久化且包含多工具请求时，取消后停止执行剩余工具，并为每个未执行请求写入确定性的 cancelled ToolResult，保证 ToolExchange 闭合。
- [ ] cancelled ToolResult 不计入成功工具记录、不触发 MemoryExtractor、不再次调用模型。
- [ ] Text Loop 根据 `ContextAssemblyResult.errorCode()` 映射 CANCELLED、MESSAGE_SEQUENCE_INVALID、TOOL_SPEC_RESOLUTION_FAILED、REQUIRED_PROVIDER_FAILED、CONTEXT_BUDGET_EXCEEDED 和通用内部错误。
- [ ] Runtime 和 ResponseMapper 保留上述错误类型，不压成 EXCEPTION 或 CONTEXT_BUILD_FAILED。
- [ ] Service 的 TEXT cancel checker 将“请求已不处于 RUNNING”视为终止；timeout 抢占后，即使 HTTP 模型稍后返回，也不得写 AiMessage 或启动工具。
- [ ] 不尝试强制中断底层 HTTP 线程；本 Task 只保证迟到结果和迟到副作用被抑制。

**必须测试：**

- prepare 前取消不写 UserMessage。
- UserMessage 写入后取消保留 UserMessage，但不写 AiMessage。
- 模型返回后取消不写 AiMessage。
- 两个工具中第一个完成后取消：第一个保留真实结果，第二个写 cancelled result，下一次 assemble 合法。
- timeout 后模型迟到返回：ToolExecutor 调用次数为 0，AiMessage 不进入 ChatMemory。
- 每种 ContextErrorCode 到 AgentResponse 的 errorType/status 保持一致。

### Task 2.3：停用生产裁剪和压缩并修复删除型写回

**生产文件：**

- Modify: `context/ContextOrchestrator.java`
- Modify: `core/TextAgentLoopOrchestrator.java`（仅删除/停用压缩状态使用）
- Modify: `memory/MemoryOrchestrator.java`
- Verify: `memory/SessionChatMemoryProvider.java`
- Create: `context/ContextMinimalBudgetGuardTest.java`
- Modify: `context/ContextCompressionIntegrationTest.java`
- Modify: relevant memory tests

**实施要求：**

- [ ] 从 ContextOrchestrator 生产 assemble 路径移除 `makeDecision()`、`planSessionCompaction()`、`executeCompactionPlan()` 调用。
- [ ] Assembler 仍生成包含完整 Tool Schema 的 `ContextBudgetReport`。
- [ ] 超限时 Text Loop 在模型调用前返回 `CONTEXT_BUDGET_EXCEEDED`，不修改 SessionMemory。
- [ ] Fake MemoryGateway 记录 plan/execute 调用次数；生产 assemble 超限测试必须断言两者均为 0。
- [ ] 修复 `MemoryOrchestrator.executeCompactionPlan()` 中“replace 后调用 clear 导致删除 Store”的错误：如保留该 API，只能通过 `SessionChatMemoryProvider.replaceMessages()` 完成原子替换与 cache eviction，不能调用删除语义的 `clear()`。
- [ ] 保留 compaction API 供后续独立计划使用，但在注释和测试中明确当前 Context 生产路径未启用。
- [ ] 不修复 `planCompact()` 摘要算法，不增加压缩重试或二次 assemble。

**关键测试：**

- 正常预算：模型调用一次，Memory compaction 调用零次。
- 超预算：模型调用零次，plan/execute 调用零次，Store 和 live ChatMemory 消息不变。
- 直接调用 `executeCompactionPlan()` 的隔离测试：写回后 Store 保留 proposedMessages，不被 clear 删除；旧 cache 被失效。

**阶段验证：**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.context.*" --tests "com.hirain.aiagent.core.TextAgentLoopOrchestratorTest" --tests "com.hirain.aiagent.runtime.*" --tests "com.hirain.aiagent.memory.*" --rerun-tasks
.\gradlew.bat assembleDebug --rerun-tasks
```

**阶段退出状态：** Context 是 TEXT 唯一输入出口；取消/超时不会留下非法消息或迟到工具副作用；预算超限安全失败；生产链没有裁剪和压缩调用。

---

## 7. Phase 3：真实行为验收、最小 Trace 与交付

**阶段目标：** 用接近生产 wiring 的测试证明消息所有权、身份隔离和工具边界，并形成可重复的最终验证记录。

**阶段边界：** 只新增 TEXT 行为测试、最小 Context Trace 属性和文档；不再修改 Provider 架构，不扩大到非 TEXT。

### Task 3.1：建立 TEXT 真实集成测试

**测试文件：**

- Create: `runtime/ContextTextEndToEndTest.java`
- Modify: `runtime/AgentRuntimeContextTest.java`
- Modify: `core/TextAgentLoopOrchestratorTest.java`
- Modify: `context/ContextMessageAssemblerTest.java`

**测试装配要求：**

- 使用真实 `AgentRuntime`、`ContextOrchestrator.defaultForText()`、`TextAgentLoopOrchestrator` 和 `ContextMessageAssembler`。
- 不使用 AgentRuntime 的默认 Context 构造器；测试必须先用完整 `ContextBuildInput` 创建 `ContextOrchestrator.defaultForText(input)`，再把该实例注入 AgentRuntime，避免得到缺少 Prompt/Memory/Tool 依赖的默认 Context。
- Prompt 使用 `new PromptManager(null) { override render(...) }` 的 JVM 测试实现；只返回固定 persona 模板文本，不访问 Android assets。
- ToolRegistry 本身可以在 JVM 中空构造；真正的 Android 障碍位于 `registerAll()` 创建 `ToolDispatcher` 后调用 `android.util.Log`。测试不得调用 `registerAll()`。
- 在 `ContextTextEndToEndTest` 内定义测试专用 `JvmToolRegistry extends ToolRegistry`：用测试自己的 `Map<String, ToolSpecification>` 保存规格，并覆盖 `toolSpecificationsByNames()`、`enabledToolSpecifications()`、`getToolSpecifications()` 和 `size()`；这些覆盖方法只操作内存 Map，不创建 ToolDispatcher、不执行 Android Log。
- 将 `JvmToolRegistry` 注入 ContextBuildInput，使真实 `ToolGroupContextProvider` 完成 selected/all-fallback/NONE 决策并生成真实 ToolContextContribution；工具执行阶段另注入 CountingToolExecutor，不调用 ToolRegistry.dispatch()。
- 其余只替换 Android/网络边界：使用内存 ContextMemoryGateway、CapturingModelCaller、固定 VehicleStatusProvider 和固定 TimeProvider。
- 禁止 FakeAssemblyGateway 直接返回手工消息来代替 Context。
- 禁止以“FakeAssemblyGateway 只替换工具解析”为由绕过 ToolGroupContextProvider；该做法无法证明 selectedToolNames 经 Provider 解析后真正进入 ContextAssemblyResult。
- 每个测试捕获真实 ChatRequest，并断言消息类型、顺序、内容来源和工具集合。

**必须覆盖：**

1. 普通聊天：唯一 System、当前用户一次、历史顺序正确。
2. Session A -> Session B：B 请求不包含 A 的 User/Ai/Tool 历史。
3. 同一 Session user A -> user B：短期历史保留，长期记忆只来自 B。
4. 同一 Session chat -> friendly -> concise：历史保留，每轮只有当前 persona 的 System Prompt。
5. CHAT_ONLY：零工具。
6. 明确车控：只出现选中 ToolSpecification，SafetyGuard 在 ToolExecutor 前执行。
7. allToolsFallback：完整启用工具集合进入 ChatRequest。
8. Prompt/SessionMemory/required Vehicle/Tool 解析失败：模型调用次数为 0。
9. 超预算：模型和 compaction 调用次数均为 0。
10. 多工具取消后下一请求仍可通过消息序列校验。

**完成证据：** 测试必须检查实际 CapturingModelCaller 收到的 ChatRequest，不允许只断言 RuntimeResult.success。

### Task 3.2：补齐最小 Context Trace 和文档

**生产/测试文件：**

- Modify: `context/ContextTraceRecorder.java`
- Modify: `context/ContextOrchestrator.java`
- Modify: `trace/ContextProductionTraceHierarchyTest.java`
- Modify: `docs/overview/context-module-overview.md`
- Modify: `docs/act_summary/2026-07-11-context-full-control-phase1-4-progress-summary.md`
- Create: `docs/testresult/2026-07-12-context-text-minimal-recovery-testresult.md`

**最小 Trace 范围：**

- 保留 `agent.request -> agent.loop -> context.prepare/context.assemble` 父子关系。
- `context.prepare` 记录 provider.count、success/fallback/failed 数量和 errorCode。
- `context.assemble` 记录 iteration、message.count、tool.count、tokens.estimated、tokens.max、budget.within 和 errorCode。
- 不增加 compression span，不扩展复杂 Provider 子 span，不改旧 prompt/memory/tool span。
- 测试必须断言 prepare/assemble 的 parentSpanId 为 agent.loop，而不只是检查名称存在。

**文档要求：**

- Overview 明确 Context 已接管 TEXT，而非所有输入类型。
- 明确生产裁剪和压缩当前停用，超限采用前置失败。
- 明确 IMAGE/VOICE/CONTROL/SCENE/VL 为冻结遗留路径。
- Progress summary 不再声称 Phase 3 压缩闭环完成，改为记录本轮实际恢复结果。
- Testresult 只记录本轮新鲜执行结果，不复用历史 235 tests 或 BUILD SUCCESSFUL。

### Task 3.3：最终门禁与交付检查

**执行顺序：**

```powershell
.\gradlew.bat compileDebugJavaWithJavac --rerun-tasks
.\gradlew.bat testDebugUnitTest --rerun-tasks
.\gradlew.bat assembleDebug --rerun-tasks
.\gradlew.bat lintDebug --rerun-tasks
```

**静态所有权审计：**

- TEXT Service 只创建 `TextAgentLoopOrchestrator`。
- TextAgentLoop 不 import PromptManager，不创建 SystemMessage，不读取固定 ToolSpecification。
- ContextMessageAssembler 不访问数据库、Vehicle、Time、Trace 或模型。
- TEXT ChatRequest 的 messages/tools 均来自 ContextAssemblyResult。
- ContextOrchestrator 生产路径没有 makeDecision/planSessionCompaction/executeCompactionPlan 调用。
- 非 TEXT 生产文件没有本轮差异。

**设备验证：**

- 连续普通 TEXT 对话至少 10 轮，无 Agent busy、重复 UserMessage 或历史丢失。
- 新建并切换 Session，确认短期历史隔离。
- 同一 Session 切换 user，确认历史保留且长期记忆切换。
- chat/friendly/concise 切换，确认 System Prompt 不串扰。
- 明确车控、CHAT_ONLY、模糊 allToolsFallback，核对模型可见工具集合。
- 多工具调用中途取消，确认未执行工具有 cancelled result，下一轮可继续。
- 构造超预算输入，确认返回预算错误且 SessionMemory 未变化。
- Phoenix 核对 context.prepare/context.assemble 父子关系和最小属性。

**最终 testresult 必须包含：** 日期、当前 Git 状态、完整命令、exit code、测试数量、失败/跳过数量、Lint error/warning、APK 路径、设备项 executed/not-executed、未执行原因和遗留风险。

---

## 8. 问题覆盖矩阵

| 已知问题 | 处理位置 |
|---|---|
| 三个 Provider 语法损坏 | Phase 1 Task 1.1 |
| 七个 Provider 回退旧 Section/HYBRID 契约 | Phase 1 Task 1.1-1.2 |
| required fallback 被当作成功 | Phase 1 Task 1.3 |
| 动态 required Provider 失败仍继续 | Phase 1 Task 1.3 |
| Prompt/Tool/SessionMemory Contribution 丢失 | Phase 1 Task 1.2-1.3 |
| TEXT 消息或工具出现旧旁路 | Phase 2 Task 2.1 |
| ToolResult null name 可绕过校验 | Phase 2 Task 2.1 |
| 多工具取消留下未闭合请求 | Phase 2 Task 2.2 |
| ContextErrorCode 被压成通用错误 | Phase 2 Task 2.2 |
| timeout 后仍可能执行工具 | Phase 2 Task 2.2 |
| 预算裁剪可能删除当前 User/破坏 ToolExchange | Phase 2 Task 2.3 停用生产调用，算法延期 |
| 压缩写回后 clear 删除 Store | Phase 2 Task 2.3 |
| 压缩无重读/重装配且测试假阳性 | Phase 2 Task 2.3 停用生产压缩，完整机制延期 |
| 测试使用 FakeAssembly 绕过真实 Context | Phase 3 Task 3.1 |
| Trace 只验证 span 存在 | Phase 3 Task 3.2 |
| ContextSection/旧字段技术债务 | 明确延期，不属于本计划 |
| IMAGE/VOICE/CONTROL/SCENE/VL | 冻结保留，后续独立删除计划 |

---

## 9. 停止条件与交接要求

- 任一 Phase 未通过编译和目标测试，不得进入下一 Phase。
- 发现必须修改非 TEXT 才能继续时，停止并报告，不实施修改。
- 发现新的数据删除、ToolExchange 不闭合、取消后继续工具执行时，按阻断问题处理。
- 不允许通过删除失败测试、放宽断言、扩大 lint ignore 或恢复 HYBRID 让门禁通过。
- 不允许把 `ContextSection` 零引用或删除数量作为本计划成功指标。
- 每个 Task 的交接必须列出：修改文件、行为变化、命令、测试结果、未完成项、发现但未处理的问题。
- 最终只能宣称“Context 已统一控制 TEXT 模型输入”；不得宣称已接管所有输入类型、已完成生产压缩或已清理全部迁移债务。
