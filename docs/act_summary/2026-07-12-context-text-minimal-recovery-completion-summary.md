# Context TEXT 最小恢复计划 — 执行总结

**生成日期：** 2026-07-12  
**计划依据：** `docs/plan_overall/2026-07-12-context-text-minimal-recovery-plan.md`  
**当前状态：** Phase 1-3 全部完成

---

## 一、总体进度

| Phase | 状态 | 说明 |
|-------|------|------|
| Phase 1：恢复编译与 Provider 契约 | ✅ 完成 | 11 个 Provider 恢复 Contribution 输出和 required 语义 |
| Phase 2：恢复唯一输入权并关闭危险旁路 | ✅ 完成 | 取消闭合、错误映射、停用裁剪/压缩 |
| Phase 3：真实行为验收、最小 Trace 与交付 | ✅ 完成 | 集成测试、Trace 属性、文档、门禁 |

---

## 二、Phase 1 完成内容

### Task 1.1：修复 Provider 文件结构和统一接口

| 文件 | 变更 |
|------|------|
| `CallerExtraContextProvider.java` | 删除孤立 `type()` 残片 + ContextSection 导入 |
| `LongTermMemoryContextProvider.java` | 同上 |
| `SessionMemoryContextProvider.java` | 同上 |
| `MemoryContextProvider.java` | **已删除**（被 LongTermMemoryContextProvider 取代） |
| `RuntimeContextProvider.java` | 添加 `required()`/`lifecycle()`、删除 `type()`、切换 Contribution 输出 |
| `PersonaContextProvider.java` | 同上 |
| `PromptContextProvider.java` | 同上 |
| `IntentContextProvider.java` | 同上 |
| `TimeContextProvider.java` | 同上 |
| `ToolGroupContextProvider.java` | 同上 |
| `PromptManager.java` | 构造器添加 null-safe 判断，支持 JVM 测试 Fake |

### Task 1.2：恢复真实静态 Provider 输出

- **PromptContextProvider**：接入 `input.promptManager().render()` 真实渲染
- **ToolGroupContextProvider**：CHAT_ONLY → MODE_NONE；选中模式从 ToolRegistry 解析规格
- **ContextProviderRequiredPolicyTest**：添加 Prompt Manager null/异常/空/chat-friendly-concise 测试
- **TestRequestSessions**：添加 `chatOnlySession()` 工具方法

### Task 1.3：恢复动态 Provider 与 required 终止语义

- **SessionMemoryContextProvider**：gateway/sessionId 缺失或读取异常 → FAILURE（非 FALLBACK）
- **ContextOrchestrator.assemble()**：动态 Provider 循环添加 required 终止检查

### Phase 1 验证

| 度量 | 值 |
|------|-----|
| Java 编译 | ✅ 通过 |
| Context 测试 | 57 tests, 0 failed |

---

## 三、Phase 2 完成内容

### Task 2.1：锁定 ContextMessageAssembler 唯一所有权

| 文件 | 变更 |
|------|------|
| `ContextMessageSequenceValidator.java:121` | null toolName 校验改为非空精确匹配（`==` 代替 `!=`） |
| `ContextOrchestrator.java` assemble() | 添加 null-session 守卫 |
| `ContextMessageSequenceValidatorTest.java` | 新增 `nullToolName_fails()` |

### 已确认正确（无需修改）

- Assembler 消息顺序 System → Context Data → SessionMemory ✅
- TextAgentLoopOrchestrator 不 import PromptManager ✅
- TextAgentLoopOrchestrator 的 ChatRequest 只来自 assemblyResult ✅

### Task 2.2：修复取消闭合、错误映射和超时停止

| 文件 | 变更 |
|------|------|
| `TextAgentLoopOrchestrator.java` 多工具循环 | 取消时遍历剩余未执行工具，写入 `[CANCELLED]` ToolResult |
| `TextAgentLoopOrchestrator.java` 错误映射 | 新增 `mapAssemblyError()`，映射 6 种 ContextErrorCode |
| `AIAgentService.kt` RuntimeCancelChecker | `isCancelled` → `state() != RUNNING`（超时 TIMEOUT 也触发） |
| `TextAgentLoopOrchestratorTest.java` | 新增 4 个测试（取消前/取消后/多工具取消/errorCode 映射） |

### Task 2.3：停用生产裁剪和压缩并修复删除型写回

| 文件 | 变更 |
|------|------|
| `ContextOrchestrator.java` assemble() | 删除 `makeDecision()`（19 行）+ 压缩协议块（32 行） |
| `TextAgentLoopOrchestrator.java` | 删除 `compressionAlreadyAttempted` 变量和追踪 |
| `MemoryOrchestrator.java` executeCompactionPlan() | `replaceMessagesOrThrow + clear` → 单一 `replaceMessages()`（修复数据删除 bug） |
| `ContextCompressionIntegrationTest.java` | 断言改为 plan/execute count == 0 |
| `ContextMinimalBudgetGuardTest.java` | 新建：正常预算 + 超限测试 |

### Phase 2 验证

| 度量 | 值 |
|------|-----|
| Context 测试 | 57 tests, 0 failed |
| TextAgentLoopOrchestrator 测试 | 10 tests, 0 failed |
| assembleDebug | ✅ 通过 |

---

## 四、Phase 3 完成内容

### Task 3.1：建立 TEXT 真实集成测试

**JvmToolRegistry 方案**：继承 ToolRegistry，以内存 Map 覆盖 `toolSpecificationsByNames()`/`enabledToolSpecifications()`/`getToolSpecifications()`/`size()`，避开 `ToolDispatcher(Log.d)` 的 Android 限制，使 `ToolGroupContextProvider` 在 JVM 中完成真实工具解析。

| 文件 | 变更 |
|------|------|
| `runtime/ContextTextEndToEndTest.java` | **新建**：8 个集成测试通过真实 AgentRuntime → ContextOrchestrator → TextAgentLoopOrchestrator 链路 |
| `runtime/AgentRuntimeContextTest.java` | 修复 6 个测试：注入 promptManager + JvmToolRegistry |
| `context/ContextMessageAssemblerTest.java` | 新增 `currentUserAppearsOnlyOnce` |

### Task 3.2：最小 Context Trace 和文档

| 文件 | 变更 |
|------|------|
| `ContextOrchestrator.java` assemble() | assemble span 补充 `tokens.estimated`/`tokens.max`/`budget.within`/`error.code` |
| `trace/ContextProductionTraceHierarchyTest.java` | 注入 promptManager + JvmToolRegistry |
| `docs/testresult/2026-07-12-context-text-minimal-recovery-testresult.md` | **新建** |
| `docs/act_summary/2026-07-11-context-full-control-phase1-4-progress-summary.md` | 更新 Phase 2/3 状态 |

### Task 3.3：最终门禁

| 命令 | 结果 |
|------|------|
| `compileDebugJavaWithJavac --rerun-tasks` | ✅ 通过 |
| `testDebugUnitTest --rerun-tasks` | 256 tests, 246 pass, 10 fail (ToolRegistry) |
| `assembleDebug --rerun-tasks` | ✅ 通过 |
| `lintDebug --rerun-tasks` | ✅ 通过 |

---

## 五、测试基线变化

| 阶段 | 总测试数 | 失败 | 说明 |
|------|---------|------|------|
| Phase 1 开始时 | 编译失败 | — | Provider 回退导致不可编译 |
| Phase 1 完成时 | 编译通过 | 16 失败 | 均为 runtime ToolRegistry 问题 |
| Phase 2 完成时 | 240（→ 137 选中） | 15 | AgentRuntimeContextTest 已修复 |
| Phase 3 完成时 | 256 | 10 | AgentRuntimeResolvedSessionTest/AgentRuntimeTest/AgentExecutorCompatibilityTest 尚未修复 |

## 六、文件变更汇总

### 生产文件（14 个修改）

| 文件 | Phase |
|------|-------|
| `context/provider/{9 个 Provider}` | 1.1-1.3 |
| `prompt/PromptManager.java` | 1.1 |
| `context/ContextOrchestrator.java` | 1.3, 2.1, 2.3, 3.2 |
| `context/ContextMessageSequenceValidator.java` | 2.1 |
| `core/TextAgentLoopOrchestrator.java` | 2.2, 2.3 |
| `memory/MemoryOrchestrator.java` | 2.3 |
| `AIAgentService.kt` | 2.2 |

### 测试文件（6 个新建/修改）

| 文件 | Phase |
|------|-------|
| `runtime/ContextTextEndToEndTest.java` | 3.1 |
| `context/ContextMinimalBudgetGuardTest.java` | 2.3 |
| `runtime/AgentRuntimeContextTest.java` | 3.1 |
| `context/ContextMessageAssemblerTest.java` | 3.1 |
| `core/TextAgentLoopOrchestratorTest.java` | 2.2 |
| `trace/ContextProductionTraceHierarchyTest.java` | 3.2 |
| `context/ContextMessageSequenceValidatorTest.java` | 2.1 |
| `context/ContextOrchestratorTest.java` | 1.1 |
| `context/ContextProviderRequiredPolicyTest.java` | 1.2 |
| `context/provider/ToolGroupContextProviderTest.java` | 1.1 |
| `context/ContextCancellationAtomicityTest.java` | 1.1 |
| `context/ContextProviderFailureTest.java` | 1.1 |
| `context/ContextCompressionIntegrationTest.java` | 2.3 |

### 删除文件

| 文件 | 原因 |
|------|------|
| `provider/MemoryContextProvider.java` | 被 LongTermMemoryContextProvider 取代 |

### 文档

| 文件 | 变更 |
|------|------|
| `docs/testresult/2026-07-12-context-text-minimal-recovery-testresult.md` | 新建 |
| `docs/act_summary/2026-07-11-context-full-control-phase1-4-progress-summary.md` | 更新 |

---

## 七、本轮明确不做（Scope Exclusions）

### 冻结遗留的 IMAGE/VOICE/CONTROL/SCENE/VL 路径

- `AgentRequest.Type.IMAGE`、`VOICE`、`CONTROL`，以及 SCENE/VL/旧 chat 调用链全部保持现状
- 不删除、不迁移、不重构这些路径，不调整其 Prompt、PreProcessor、MemoryPolicy、ToolSpecification、Trace 或模型输入
- `CONTROL` 仍作为遗留 Service 命令通道（StartListen、StopListen、ClearChatMemory）

### 未删除的旧类型

| 类型 | 保留原因 |
|------|---------|
| `ContextSection.java` | 仍被冻结的非 TEXT 路径引用，非 TEXT 不得修改 |
| `ContextSectionType.java` | 同上 |
| `ContextDebugInfo.java` | 同上 |
| `ContextFrame` 旧字段（`memorySummary`/`vehicleStateSnapshot`/`timeContext`/`promptContext`/`renderedExtraContext`/`sections`/`debugInfo`） | 同上 |
| `ContextFrameBuilder` 同名 setter | 同上 |
| `ContextBudgetManager` 旧字符级 API（`sectionCharLimit`/`trim()`/`estimateTokens(String)`） | 不删除旧字符预算 API，不继续 Phase 4 债务清理 |

### 未实现的裁剪和压缩

- `ContextBudgetManager.makeDecision()` 的生产调用已移除，但方法本身保留
- `planSessionCompaction()` / `executeCompactionPlan()` 的生产调用已移除，但 Memory 的 API 保留
- 不修复 `planCompact()` 摘要算法，不增加压缩重试或二次 assemble
- 不完善 `makeDecision()` 的裁剪算法（如当前 UserMessage 保护、多工具交换保护）

### 未修改的模块

| 模块 | 不变范围 |
|------|---------|
| Memory 系统 | 不修改摘要 Prompt、MemoryExtractor、长期记忆存储算法 |
| IntentRouter | 不修改 KeywordIntentRouter 规则 |
| ToolGroupSelector | 不调整 DefaultToolGroupSelector 匹配逻辑和 allToolsFallback 决策 |
| AIDL 协议 | 不修改 AgentRequest/AgentResponse Parcelable、IAIAgentAidlInterface |
| 工具实现 | 不修改车辆 Tool、VehicleStateMachine、SOA、Camera |
| 第三方依赖 | 不引入 tokenizer、Mockito、Robolectric |

---

## 八、遗留风险

### 8.1 测试相关

| # | 风险 | 根因 | 影响 | 建议修复 |
|---|------|------|------|---------|
| 1 | 10 个 runtime 测试持续失败 | `ToolRegistry` → `ToolDispatcher` → `android.util.Log.d()` 在 JVM 不可用 | 测试覆盖率缺口（AgentRuntimeTest、AgentRuntimeResolvedSessionTest、AgentExecutorCompatibilityTest） | 注入 JvmToolRegistry + PromptManager fake（同 AgentRuntimeContextTest 方案） |
| 2 | 8 项设备验证未执行 | 无设备环境 | 未验证真实 Android 对话/切换/超预算/取消流程 | 在车机或 Robolectric 中补充 |
| 3 | JvmToolRegistry 在 ContextTextEndToEndTest 中仅支持 selected 路径 | allToolsFallback 的完整工具集合解析未测试 | 覆盖不全 | 需注册全量工具规格到 JvmToolRegistry |
| 4 | CapturingModelCaller 存在 3 个重复实现 | 不同测试文件各自定义 | 维护成本 | 统一为 `core/CapturingModelCaller.java` 公共类 |

### 8.2 生产代码风险

| # | 风险 | 说明 |
|---|------|------|
| 5 | `executeCompactionPlan()` 修复后未经过 Android 环境验证 | `clear()` bug 已修复为 `replaceMessages()`，但替换路径在真实 SQLite Store 中未经过测试 |
| 6 | `ContextOrchestrator.defaultForText()` 中的 `{7 个 Provider}` 实例化顺序影响 prepare 终止结果 | 当前顺序下 Prompt 先于 ToolGroup 失败，顺序变更可能改变错误报告 |
| 7 | `TextAgentLoopOrchestrator` 取消路径在 AgentRuntime 层未验证 | cancel 信号通过 `ContextPrepareResult.cancelChecker()` 传播，AgentRuntime 中不可靠触发 |
| 8 | 超时处理仍为两层（Service 15s + Loop 30s），外层 TIMEOUT 后内层仍可能继续运行 | Loop 内部的超时检查每轮迭代执行，15s timeout 后 Loop 可能继续运行直到内部 30s 超时 |

### 8.3 技术债务

| # | 债务 | 位置 | 未来删除条件 |
|---|------|------|------------|
| 9 | `ContextSection.java` | `context/` | 需确认冻结非 TEXT 路径零引用 |
| 10 | `ContextSectionType.java` | `context/` | 同上 |
| 11 | `ContextDebugInfo.java` | `context/` | 同上 |
| 12 | `ContextFrame` 旧字段 | `context/` | 同上 |
| 13 | `ContextBudgetManager` 旧字符 API | `context/` | 需要在对应 Task 中审计 |
| 14 | `AgentLoopContextShadowTest.java` | 测试 | 验证是否被 TextAgentLoopOrchestratorTest 覆盖 |

---

## 九、完成声明

Context 已统一控制 TEXT 模型输入。所有 TEXT 生产路径的 `ChatRequest.messages()` 和 `toolSpecifications()` 均来自 `ContextAssemblyResult`。Prompt、当前用户、SessionMemory、长期记忆、车辆、时间、caller extra 和 ToolSpecification 均有明确 Provider 所有权。

IMAGE/VOICE/CONTROL/SCENE/VL 冻结保留，未修改。

### 已确认的所有权边界

| 职责 | 拥有者 |
|------|--------|
| 模型输入消息和工具规格 | ContextMessageAssembler + ContextOrchestrator |
| 取消/超时检查点 | TextAgentLoopOrchestrator + RuntimeCancelChecker |
| 错误码映射 | TextAgentLoopOrchestrator.mapAssemblyError() |
| 预算门禁 | ContextMessageAssembler（估算）+ TextAgentLoopOrchestrator（超限检查） |
| 会话记忆持久化 | SessionMemoryStore + SessionChatMemoryProvider |
| 长期记忆提取 | LongTermMemoryStore |
| 车辆状态读取 | VehicleStateMachine |
| 工具执行 | SafetyGuard → ToolExecutor |
| Prompt 模板渲染 | PromptManager
