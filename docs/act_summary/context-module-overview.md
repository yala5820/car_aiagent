# Context 模块现状与初始计划完成度总结

**更新日期：** 2026-07-12  
**对照基线：** `docs/plan_overall/2026-07-12-context-text-minimal-recovery-plan.md`  
**当前结论：** TEXT 模型输入统一控制的核心目标已经完成；预算裁剪、生产自动压缩和迁移债务彻底清理仍未完成。

---

## 一、当前定位

Context 是 TEXT Agent 的模型输入控制层，而不是 Memory、Prompt、Tool 等能力模块的替代品。

- Context 负责决定本轮 LLM 能看到哪些 `ChatMessage` 和 `ToolSpecification`，并负责调用 Provider、排序、校验、预算估算和失败控制。
- PromptManager 继续负责模板加载与渲染。
- Memory 模块继续负责短期记忆、长期记忆、持久化、提取和压缩能力。
- ToolGroup/ToolRegistry 继续负责工具选择、规格查询和工具执行。
- AgentLoop 继续负责模型调用、SafetyGuard、工具执行、结果写回和循环终止。

当前完成的是 **TEXT 唯一输入权**，不是所有输入类型的统一接管。

---

## 二、当前生产调用链

```text
AIAgentService
  -> AgentRuntime.startSession()
       -> IntentRouter / ToolGroupSelector
       -> RequestSession
  -> AgentRuntime.execute()
       -> 空 TEXT 校验
       -> agent.loop span
       -> ContextOrchestrator.prepare()
            -> request-static Providers
            -> ContextPrepareResult / ContextFrame
       -> TextAgentLoopOrchestrator.execute()
            -> ContextOrchestrator.assemble()（每轮）
                 -> iteration-dynamic Providers
                 -> ContextMessageAssembler
                 -> ContextAssemblyResult
            -> 预算与取消检查
            -> ChatRequest(messages, toolSpecifications)
            -> ModelCaller / SafetyGuard / ToolExecutor
            -> Memory 写回
```

生产 TEXT 路径中，`ChatRequest.messages()` 和 `toolSpecifications()` 均直接来自 `ContextAssemblyResult`。`TextAgentLoopOrchestrator` 不再自行渲染 System Prompt、拼接消息或维护固定模型可见工具集合。

iteration 0 包含当前用户 `CURRENT_USER` 消息，位于 SessionMemory 之后、消息序列末尾。iteration 1+ 不重复追加当前用户，SessionMemory 已包含上一轮提交的用户消息。

---

## 三、当前模块结构

### 3.1 强类型数据契约

| 类型 | 作用 |
|---|---|
| `ContextContribution` | 所有上下文来源的统一抽象，声明来源、可见性、信任级别、优先级和生命周期 |
| `TextContextContribution` | System Prompt、长期记忆、车辆、时间、caller extra 等文本上下文 |
| `MessageContextContribution` | 当前用户消息和 Session ChatMemory 消息序列 |
| `ToolContextContribution` | 真实 LangChain4j `ToolSpecification` 集合 |
| `ContextDataFormatter` | 将 Context Data 格式化为带 `[source=xxx trust=YYY]` 标签的 `[CONTEXT_DATA_BEGIN]` 包围的 envelope |
| `ContextPrepareResult` | 请求级 Provider 的准备结果、当前用户消息、取消检查器和静态 Frame |
| `ContextAssemblyRequest` | 每轮装配输入，包含 Frame、迭代号、预算、取消状态和 RequestSession |
| `ContextAssemblyResult` | 最终消息、工具规格、预算报告、Provider outcome 和稳定错误信息 |

### 3.2 Provider 生命周期与顺序

**REQUEST_STATIC，一次请求只执行一次：**

1. `RuntimeContextProvider`
2. `PersonaContextProvider`
3. `PromptContextProvider`
4. `UserInputContextProvider`
5. `IntentContextProvider`
6. `ToolGroupContextProvider`
7. `LongTermMemoryContextProvider`
8. `CallerExtraContextProvider`

`MemoryContextProvider` 已在最小恢复中删除，职责由 `LongTermMemoryContextProvider` 和 `SessionMemoryContextProvider` 分别承担。

**ITERATION_DYNAMIC，每轮模型调用前重新读取：**

1. `SessionMemoryContextProvider`
2. `VehicleStateContextProvider`
3. `TimeContextProvider`

required Provider 只有返回 `SUCCESS` 才能继续；`FALLBACK` 和 `FAILED` 都会终止。optional Provider 可以降级并记录 outcome。

### 3.3 最终消息顺序

`ContextMessageAssembler` 根据 `iteration` 参数输出：

**iteration = 0（首轮）：**
1. 唯一 `SystemMessage`，只接受受信 System Contribution。
2. 可选 Context Data `UserMessage`（使用 `ContextDataFormatter` 的 `[CONTEXT_DATA_BEGIN]`/`[CONTEXT_DATA_END]` envelope 格式化）。
3. Session ChatMemory 消息序列。
4. **Current UserMessage**（来自 `SOURCE_CURRENT_USER`），必须是消息末尾且恰好出现一次。

**iteration > 0（工具迭代）：**
1. 唯一 `SystemMessage`。
2. 可选 Context Data `UserMessage`。
3. Session ChatMemory 消息序列（已包含上一轮提交的当前用户和完整 ToolExchange）。
4. 不追加 `CURRENT_USER`。

Assembler 同时校验消息序列和 ToolExchange，拒绝重复 System、孤立 Tool Result、缺少当前用户、重复当前用户和工具 schema 冲突。

---

## 四、初始计划完成情况

### Phase 1：强类型骨架与基础测试

**完成：**

- Contribution、生命周期、可见性、信任级别、优先级和 Provider outcome 已建立。
- `ContextMessageAssembler` 与 `ContextMessageSequenceValidator` 已实现。
- 唯一 System、固定消息顺序、当前用户唯一性、工具交换合法性已有测试。
- Trace parent-aware API 和 parent/child 测试已建立。

**未按初始方案完成：**

- 没有形成计划要求的 `context-shadow-baseline-matrix.md` 完整影子基线交付物。
- 迁移最终采用直接恢复并切换 TEXT 链路，没有依赖完整影子比较流程推进。

**判断：基本完成，影子基线部分未实现。**

### Phase 2：Provider 真实化与能力模块窄接口

**完成：**

- Prompt、用户输入、长期记忆、短期记忆、车辆、时间、caller extra 均已成为真实 Provider。
- 长期记忆按 `userId` 读取，短期记忆按原始 `sessionId` 读取。
- `MemorySnapshot.sessionId()` 与 Store memoryId 边界已区分。
- Tool Provider 从 ToolRegistry 解析真实 `ToolSpecification`。
- CHAT_ONLY 返回零工具；明确选择严格解析；allToolsFallback 返回完整启用工具集合。
- request-static 与 iteration-dynamic Provider 链已拆分。

**判断：核心目标已完成。**

### Phase 3：影子装配与差异验证

**已实现的最终能力：**

- `AgentRuntime` 通过强类型 `ContextPrepareResult` 调用 TEXT executor。
- AgentLoop 每轮调用 Context Assembly，取消检查贯穿 prepare、assemble、模型和工具边界。
- 多轮工具消息可重新装配，动态 Provider 每轮刷新。

**没有实现：**

- 没有保留完整的 `ContextShadowComparator`、Shadow Recorder、scenarioId 差异矩阵和迁移开关。
- 没有经历“旧链路继续生产、Context 只影子比较”的完整中间态。

后续恢复过程中选择了直接建立真实端到端测试并切换唯一输入权，因此最终架构不依赖影子设施，但这不等于初始 Phase 3 原样完成。

**判断：最终能力已由其他路径实现，原计划的影子迁移机制未实现。**

### Phase 4：预算、压缩能力与 Context Trace

**完成：**

- `ContextBudgetPolicy`、`ModelContextWindowProfiles.qwenTurboDemo()` 和低成本估算器已实现。
- 当前 Demo 配置为 32768 上下文、2048 输出预留、1024 安全余量，最大输入 29696。
- 消息和完整 Tool Schema 均参与保守 token 估算。
- 超预算在模型调用前返回 `CONTEXT_BUDGET_EXCEEDED`。
- 超预算不会调用模型、工具或 compaction，也不会写入 SessionMemory。
- Memory compaction plan/result、原子 replace 和 cache eviction 所需接口已经建立。
- `agent.loop -> context.prepare/context.assemble` 父子 Trace 已建立，并记录 Provider、消息、工具和预算指标。

**未实现或未启用：**

- 没有实现完整的按优先级裁剪、ConversationTurn/ToolExchange 原子裁剪和 optional Contribution 逐级删除策略。
- token 估算器没有实现初始计划要求的静态内容摘要缓存和真实 usage 误差校准。
- 生产 Context 不调用 `planSessionCompaction()` 或 `executeCompactionPlan()`，没有自动压缩、重读 ChatMemory 和二次 assemble。
- `prompt.assembly` 旧 Trace 常量与 recorder 仍存在，尚未彻底被 `context.assemble` 清理替代。

**判断：预算硬失败与 Trace 已完成；裁剪和生产压缩未完成。**

### Phase 5：Context 独占切换

**完成：**

- TEXT 使用独立 `TextAgentLoopOrchestrator`，构造器强制依赖 `ContextAssemblyGateway`。
- TEXT 的消息和工具只取自 `ContextAssemblyResult`。
- TEXT 无 PreProcessor 链，消息输入唯一来源是 `ContextMessageAssembler`。
- Service 装配真实 PromptManager、Memory Gateway、ToolRegistry、车辆状态和 ContextOrchestrator。
- 空 TEXT 在 Runtime 返回 `INVALID_INPUT`，不会进入 Context、模型或 Memory。
- 当前 UserMessage 只出现在 iteration 0 末尾，iteration 1+ 不重复追加。
- Context Data 使用 `ContextDataFormatter` 的 `[CONTEXT_DATA_BEGIN]`/`[CONTEXT_DATA_END]` envelope 格式化，含 source 和 trust 标签。
- CHAT_ONLY 场景车辆状态不注入模型（`POLICY_ONLY` 可见性）。
- Session 切换隔离短期历史；同 Session 切换用户保留短期历史并切换长期记忆；Persona 切换更新 System Prompt。
- CHAT_ONLY、明确工具、allToolsFallback、required 失败、预算、取消和多工具闭合已有真实链路测试。
- 当前 UserMessage 只在预算和取消检查通过后写入一次。

**没有按初始计划实现：**

- Phase 5 原计划要求生产启用”一次压缩 -> 重读 ChatMemory -> 二次 assemble”，最终为了避免错误删除和扩大改动，该能力被明确停用。

**判断：TEXT 唯一输入权已完成；生产自动压缩未完成。**

### Phase 6：迁移债务删除与最终验收

**已完成：**

- `ContextMode`、旧 `ContextBuildResult`、`ContextExtraPreProcessor` 已删除。
- TEXT 不再存在 HYBRID/OBSERVE/FULL 模式分支。
- TEXT 不再注册 Vehicle/Time/Memory 消息型 PreProcessor。
- 旧影子设施没有进入最终生产路径。
- 完整 JVM 测试和 Debug APK 构建已通过。

**尚未完成：**

- `ContextSection`、`ContextSectionType`、`ContextDebugInfo` 和 `ContextFrame` 部分旧兼容字段仍保留。
- `ContextBudgetManager` 的旧字符预算/decision API 仍保留，但不参与当前生产装配。
- `ContextOrchestrator` 仍有兼容测试使用的二参构造器。
- legacy `AgentLoopOrchestrator`、`buildSystemPromptMessage()`、固定工具和非 TEXT PreProcessor 仍存在；当前保留是为了冻结 SCENE/VL 等非 TEXT 路径。
- `TraceSpanNames.PROMPT_ASSEMBLY` 和对应 recorder 尚未清零。
- 没有完成初始计划要求的全仓旧引用为零。
- Android 设备上的连续对话、SQLite、真实 ToolRegistry、取消/超时和 Phoenix Trace 验证尚未执行。

**判断：关键生产旁路已删除，但技术债务清理和设备验收未完成。**

---

## 五、核心能力完成度

| 能力 | 状态 | 说明 |
|---|---|---|
| TEXT ChatMessage 唯一生成权 | 已完成 | 所有模型消息来自 ContextAssemblyResult |
| TEXT ToolSpecification 唯一生成权 | 已完成 | selected、CHAT_ONLY、allToolsFallback 均走 Tool Provider |
| Prompt/Memory/Vehicle/Time/Caller Extra 接入 | 已完成 | 由真实 Provider 调用能力模块窄接口 |
| Session 隔离 | 已完成 | sessionId 维度隔离短期历史 |
| 用户切换语义 | 已完成 | 同 Session 保留短期历史，长期记忆按 userId 切换 |
| Persona 切换 | 已完成 | 当前 System Prompt 随 persona 切换 |
| required/optional Provider 契约 | 已完成 | required 仅 SUCCESS 放行 |
| 消息序列与工具交换校验 | 已完成 | 非法序列在模型前失败 |
| 预算估算与硬失败 | 已完成 | 超限零模型、零工具、零 compaction、零 Memory 写入 |
| 优先级裁剪 | 未实现 | 当前不裁剪，直接超限失败 |
| 生产自动压缩与二次装配 | 未实现 | Memory 能力接口存在，生产 Context 不调用 |
| Context Trace | 基本完成 | prepare/assemble span 含 Provider event、assembled messages、逐消息 event、gen_ai.chat 记录完整请求消息 |
| 迁移债务清零 | 部分完成 | 核心旁路已删除，ContextSection/旧 Budget API/旧 AgentLoop 仍保留 |
| 非 TEXT 输入统一接管 | 未实现 | IMAGE/VOICE/CONTROL/SCENE/VL 不属于本轮范围 |

综合判断：**TEXT Context 核心架构约 90% 完成；以”统一生成 TEXT 模型输入”为标准已经完成，以初始六阶段全部任务和清理项为标准尚未 100% 完成。**

---

## 六、Trace 现状

当前主要层级为：

```text
agent.request
  -> agent.loop
       -> context.prepare
       -> context.assemble
       -> gen_ai.chat
       -> tool.execute
       -> memory.extract / memory.compress（由对应能力实际执行时产生）
  -> response.dispatch
```

**context.prepare span：**
- 汇总属性：phase、result、provider_count、success/fallback/failed 计数、contribution_count、current_user.contribution_count、duration_ms、error_code、error_detail
- 每个 request-static Provider 一条 `context.provider.output` event：provider.name、lifecycle、required、status、contribution_count、error_code、error_reason

**context.assemble span：**
- 汇总属性：phase、iteration、result、message_count、tool_count、current_user.included/count/is_last、estimated_tokens、max_input_tokens、within_budget、sequence_valid、error_code
- 每条最终消息一个 `context.message` event：message.index、role、content
- `context.assembled_messages`：完整消息列表（含角色和内容）
- `context.assembled_tool_specs`：完整工具规格（name、description、parameters）
- 每个 iteration-dynamic Provider 一条 `context.provider.output` event

**gen_ai.chat span（LLM 请求）：**
- `gen_ai.request.messages`：完整请求消息
- `gen_ai.request.tool_specs`：完整请求工具规格
- 模型响应使用 `enrichLlmResponse()` 记录

所有内容完整记录（不超过 OpenTelemetry 属性长度限制），不建设脱敏/截断策略。

---

## 七、当前验证状态

截至 2026-07-12：

- `testDebugUnitTest --rerun-tasks`：270 tests，0 failures，0 errors。
- `assembleDebug --rerun-tasks`：成功。
- 已覆盖真实 Context + Text Loop 的普通聊天、会话切换、用户切换、Persona、工具选择、全量 fallback、required 失败、预算、取消、Context Data envelope、Provider Trace event 和 LLM Trace 请求内容。
- 尚未完成 Android/车机设备验证，因此不能宣称真实 SQLite、真实车控 ToolRegistry、网络超时和 Phoenix 导出已经完成生产验收。

---

## 八、后续建议

Demo 阶段不建议继续扩大 Context 架构修改，优先进行设备联调。后续工作按优先级排序：

1. 在车机执行 TEXT 连续对话、Session/User/Persona 切换、明确/模糊工具、取消与超时冒烟测试。
2. 验证 SQLite SessionMemory、长期记忆切换和真实 ToolRegistry 行为。
3. 在 Phoenix 验证 `agent.loop -> context.prepare/context.assemble -> gen_ai.chat/tool.execute` 父子关系。
4. 根据真实超预算数据决定是否需要优先级裁剪和生产自动压缩；没有真实需求前不启用现有 compaction 接口。
5. 在决定删除 SCENE/VL 等 legacy 路径后，再集中清理 ContextSection、旧 Budget API、旧 AgentLoop 和 `prompt.assembly`，避免为了形式上的零引用破坏冻结路径。

