# Context 模块遗留问题统一收口计划

**日期：** 2026-07-13
**项目阶段：** Demo
**实施范围：** TEXT Context 主链路
**依据文档：** `docs/overview/context-module-overview.md`

---

## 一、计划目标

本计划用于统一解决 Context overview 中记录的遗留问题，不再按照问题数量机械拆分阶段，而是根据代码依赖关系合并为 3 个 Phase：

1. **Phase 1：修复上下文、Provider、工具和 Trace 的正确性问题。**
2. **Phase 2：完成长上下文预算裁剪、Memory 摘要压缩和二次装配闭环。**
3. **Phase 3：清理 Context 技术债并完成自动化、设备和 Phoenix 总体验收。**

计划完成后，TEXT 请求必须具备以下行为：

1. 所有发送给 LLM 的 `ChatMessage` 和 `ToolSpecification` 继续只由 Context 生成。
2. Memory Store 保留完整 Session 历史；Context 按完整 ConversationTurn 和 ToolExchange 读取、校验和预算处理，不在写入阶段按固定消息数破坏性淘汰。
3. 超预算时先安全裁剪 optional Context，再调用 Memory 完成真实摘要压缩，最多重新装配一次。
4. Provider、Tool 和 Trace 的 success、fallback、failed 语义与真实执行一致。
5. Demo 的 `FULL_DEBUG` Trace 完整显示消息、工具 schema、参数和结果，不做应用层截断。
6. 用户实际收到的最终回复与 SessionMemory 中保存的最终 AiMessage 一致。
7. ContextFrame 和 Context 包只保留当前生产链路需要的一套数据模型。
8. 单次 Agent 请求跨全部工具迭代最多尝试一次 Memory 压缩；压缩计划过期时不得覆盖并发产生的新历史。

---

## 二、架构边界

### 2.1 固定职责

- **ContextOrchestrator**：调用 Provider、管理请求级和迭代级 Contribution、协调预算恢复、输出最终装配结果。
- **ContextMessageAssembler**：把 Contribution 纯函数式地转换为候选消息、工具规格和预算结果，不访问数据库、模型、车辆或 Trace exporter。
- **Memory 模块**：负责 SessionMemory 读取、对话边界识别、真实摘要压缩和持久化写回。
- **Prompt 模块**：负责模板选择、加载和渲染。
- **ToolGroup / ToolRegistry / ToolDispatcher**：分别负责工具选择、规格解析和真实工具执行。
- **Trace 模块**：记录真实执行过程，不重新调用 Provider、Memory、Tool 或模型。

### 2.2 本轮明确不做

1. 不修改 AIDL、Parcelable 字段和外部 App 调用接口。
2. 不改变 Prompt 文案和人格内容。
3. 不修改 ToolGroupSelector 的匹配规则，也不取消 Demo 的 allToolsFallback。
4. 不引入第三方 tokenizer，不升级 LangChain4j、OpenTelemetry、Gradle 或其他依赖。
5. 不删除 `IMAGE`、`VOICE`、`CONTROL` 或 Scene 业务入口。
6. 不删除仍被 VOICE / Scene 使用的 `AgentLoopOrchestrator.execute(String, Map)`、旧 PreProcessor 和相关配置。
7. Context Legacy 清理仅限确认无生产引用的 Context 类型、字段、方法和过时注释。
8. 不修改当前工作树中与本计划无关的 Prompt、Persona 或其他未提交内容。

### 2.3 实施纪律

1. 每个 Phase 开始前执行 `git status --short`，不得覆盖其他人的修改。
2. 每个 Task 完成后先运行该 Task 的目标测试；整个 Phase 完成后运行阶段门禁。
3. 禁止通过增大窗口、增大 Token 上限、删除测试或放宽断言掩盖问题。
4. 当前用户消息必须继续遵守原子提交：装配成功、预算通过、模型调用前未取消后才写入 ChatMemory。
5. Ai Tool Call 和它声明的全部 ToolResult 是不可拆分的原子单元。
6. TEXT 短期记忆不得继续依赖 `MessageWindowChatMemory(maxMessages=50)` 作为持久化淘汰策略；模型输入取舍只属于 Context Token 预算决策，不再保留独立的固定 50 条读取窗口。
7. 压缩摘要模型调用期间不得长期持有数据库锁；写回前必须重新校验原始快照仍然有效。
8. 本计划修改前，必须先把当前与 `TextAgentLoopOrchestrator`、`AgentConfigFactory`、`AgentTraceRecorder` 重叠的未提交工作建立明确 Git 基线。不得让子 Agent 在无法区分既有修改的情况下覆盖工作树。

---

## 三、问题与 Task 对应关系

| overview 遗留问题 | 处理位置 |
|---|---|
| SessionMemory 写入和读取阶段固定 50 条可能破坏完整历史 | Phase 1 / Task 1.1 |
| LongTermMemory fallback 被记成 success | Phase 1 / Task 1.2 |
| Tool ALL_FALLBACK 被记成 SELECTED | Phase 1 / Task 1.2 |
| Tool 聚合成功字段与真实执行矛盾 | Phase 1 / Task 1.3 |
| PostProcessor 输出与 Memory 不一致 | Phase 1 / Task 1.4 |
| FULL_DEBUG 内容仍被截断 | Phase 1 / Task 1.5 |
| Context Policy 分散 | Phase 2 / Task 2.1 |
| 优先级裁剪未接入生产 | Phase 2 / Task 2.2 |
| 超预算没有真实压缩恢复 | Phase 2 / Task 2.3 |
| 启发式 Token 估算存在偏差 | Phase 2 / Task 2.4 |
| ContextFrame 保留第二套字段 | Phase 3 / Task 3.1 |
| Legacy 类型、API 和过时注释 | Phase 3 / Task 3.2、3.3 |
| Phoenix 和设备侧效果未证明 | Phase 3 / Task 3.5 |
| 压缩状态只在单次 assemble 内生效，无法限制整个 Agent 请求 | Phase 2 / Task 2.3 |
| 压缩计划缺少过期检测，可能覆盖新写入消息 | Phase 2 / Task 2.3 |
| Provider span 在预算裁剪前结束，无法回填最终入模状态 | Phase 1 / Task 1.5、Phase 2 / Task 2.2 |

---

## 四、Phase 1：修复现有链路的正确性和可观测性

### 4.1 阶段目标

本阶段不引入预算压缩新流程，先修复当前生产链路已经存在的错误：历史窗口可能损坏、Provider 状态不准确、工具失败被标成成功、最终回复写回不一致以及 Trace 内容截断。

### Task 1.1：取消写入阶段的破坏性窗口，并按完整 ConversationTurn 读取 SessionMemory

#### 当前问题

当前问题不只发生在 `sessionMemorySnapshot()`。TEXT 主链路先通过 `SessionChatMemoryProvider` 创建 `MessageWindowChatMemory(maxMessages=50)`；LangChain4j 会在每次 `add()` 时淘汰最旧消息并覆盖 Store。该淘汰只保护被删除 Ai ToolCall 后紧随的 ToolResult，不保护完整 ConversationTurn，因此历史在 Context 读取前就可能已经永久丢失。之后 `sessionMemorySnapshot()` 再使用裸 `subList()`，又会产生第二次边界破坏。

#### 修改位置

- `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java`
- `app/src/main/java/com/hirain/aiagent/memory/ContextMemoryGateway.java`
- `app/src/main/java/com/hirain/aiagent/memory/SessionChatMemoryProvider.java`
- `app/src/main/java/com/hirain/aiagent/memory/SessionMemoryStore.java`
- `app/src/main/java/com/hirain/aiagent/memory/MemorySnapshot.java`
- `app/src/main/java/com/hirain/aiagent/context/provider/SessionMemoryContextProvider.java`
- `app/src/main/java/com/hirain/aiagent/core/TextAgentLoopOrchestrator.java`
- `app/src/main/java/com/hirain/aiagent/core/factory/AgentConfigFactory.java`（校正 TEXT 中 `maxMemoryMessages` 的过时含义）
- 新增 `app/src/main/java/com/hirain/aiagent/memory/PersistentSessionChatMemory.java`
- 新增 `app/src/main/java/com/hirain/aiagent/memory/SessionHistorySequenceValidator.java`
- `app/src/test/java/com/hirain/aiagent/memory/` 下新增窗口测试
- `app/src/test/java/com/hirain/aiagent/context/ContextMessageSequenceValidatorTest.java`

#### 修改方法

1. 新增 `PersistentSessionChatMemory` 实现 LangChain4j `ChatMemory`，继续使用现有 `ChatMemoryStore` 和 session memoryId，但 `add()/set()` 不按消息数量淘汰历史。它只负责完整读写，不负责模型输入窗口。
2. `SessionChatMemoryProvider` 改为缓存 `PersistentSessionChatMemory`，新增不接收 maxMessages 的主入口。`ContextMemoryGateway` 和 TextAgentLoop 改用该入口；旧带 maxMessages 的 overload 仅在仍有 legacy 调用时保留并标记兼容，不允许 TEXT 继续传入无效窗口参数。
3. `PersistentSessionChatMemory` 的 read-modify-write 必须使用 session 级锁；生产 `SessionMemoryStore` 写入走会抛出异常的原子替换方法，不得继续依赖会吞异常的 `updateMessages()`。写入失败必须传播给 AgentLoop 和 Trace。
4. `ContextMemoryGateway` 增加或改用“完整 Session 快照”接口。`SessionMemoryContextProvider` 不再硬编码 50，也不再先截取普通消息列表；它读取 Store 中的完整合法历史，后续 Token 取舍统一交给 Phase 2 的 Context Budget。`AgentConfig.maxMemoryMessages` 对 TEXT 不再生效，只保留给明确仍使用窗口记忆的 legacy 配置。
5. 新增纯 Java `SessionHistorySequenceValidator`，把历史解析为完整 ConversationTurn。一个完整 turn 定义为：一个 UserMessage，后接零到多组 `Ai ToolCall -> 该 AiMessage 声明的全部 ToolResult`，最后可接一个普通 AiMessage；下一条 UserMessage 才开始新 turn。已有压缩摘要是独立历史数据，不作为 CURRENT_USER turn。
6. ToolExchange 的完整性按 Tool Call ID 精确判断：
   - 保留发起调用的 AiMessage。
   - 保留该 AiMessage 声明的全部 Tool Call。
   - 保留每个 ID 对应且 toolName 一致的 ToolResult。
   - 多工具调用不得只保留部分结果。
7. 对历史损坏采用固定恢复规则：
   - 如果唯一问题是尾部 ConversationTurn 因进程中断而存在未闭合 ToolExchange，Memory 在同一 session 锁下原子删除整个尾部不完整 turn，并返回 repair metadata（是否修复、删除消息数、稳定原因）。
   - 未知 ToolResult、重复 Tool Call ID、toolName 不匹配或中间位置未闭合属于不可自动修复的历史损坏，抛出稳定的 Memory 历史异常，由 SessionMemory Provider 返回 required failure。
   - 不伪造 ToolResult，不把损坏历史静默降级为空列表。
8. `MemorySnapshot` 携带结构化摘要、校验后的完整消息和 repair metadata；ProviderOutcome、Contribution metadata 和 Trace 能看到自动修复事实，被移除的非法正文不得继续入模。
9. 本 Task 不触发摘要压缩、不修改 SQLite schema，也不通过扩大 50 的数值掩盖问题。

#### 测试要求

1. 连续写入 49、50、51 和 100 条普通历史后，Store 仍保存全部消息，Context 读取不发生写入阶段淘汰。
2. 第 50 条位于 Ai ToolCall 与 ToolResult 之间时，完整 ToolExchange 仍存在。
3. 一个 AiMessage 同时包含多个 ToolCall，以及同一用户 turn 内连续发生多组 ToolExchange。
4. 尾部未闭合 ToolExchange 自动删除完整尾部 turn，并且只执行一次修复写回。
5. 历史中存在未知 ID、重复 ID、toolName 不匹配和中间未闭合时，Store 不被覆盖并返回 required failure。
6. Store 写入异常会传播，不得出现“内存认为已写入、SQLite 实际失败”的假成功。
7. 完整历史进入真实 Assembler 后仍通过最终消息序列校验。

### Task 1.2：修复 Provider 和 ToolContribution 的状态语义

#### 当前问题

1. `LongTermMemoryContextProvider` 已计算 FALLBACK 和 errorDetail，但结尾无条件调用 `ContextProviderResult.success()`。
2. `ToolGroupContextProvider` 在 allToolsFallback 场景仍写 `MODE_SELECTED`。
3. CHAT_ONLY 主要依赖 `selectionReason` 字符串判断，结构语义不稳定。

#### 修改位置

- `context/provider/LongTermMemoryContextProvider.java`
- `context/provider/ToolGroupContextProvider.java`
- `context/ToolContextContribution.java`
- `toolgroup/ToolGroupSelectionResult.java`（仅在需要稳定辅助方法时修改）
- 新增 `context/provider/LongTermMemoryContextProviderTest.java`
- 新增或完善 `ToolGroupContextProviderTest.java`
- `trace/ContextProviderTraceTest.java`

#### 修改方法

1. LongTermMemory 正常读取返回 SUCCESS；Memory 未配置、userId 缺失或读取异常返回 `ContextProviderResult.fallback()`。
2. LongTermMemory 是 optional Provider，FALLBACK 不阻断 prepare；空内容不能被 Trace 标记为实际入模。
3. ToolContribution 的 mode 按结构化 selection 决定：
   - CHAT_ONLY -> `MODE_NONE`。
   - `allToolsFallback()==true` -> `MODE_ALL_FALLBACK`。
   - 其他非空工具集合 -> `MODE_SELECTED`。
4. CHAT_ONLY 优先根据 groupId 或稳定布尔方法判断，不继续把自由文本 reason 当作唯一业务条件。
5. Tool metadata 写入 fallbackUsed、allToolsFallback、selectedGroupCount、selectedToolCount 和 selectionReason。
6. Context 只解析上游 selection 已经提供的工具名，不在 Provider 内自行扩大工具集合。
7. ProviderResult、ProviderOutcome、Contribution 和 Provider span 的状态必须一致。

#### 测试要求

覆盖长期记忆成功、空记忆、未配置、读取异常，以及 CHAT_ONLY、明确工具、allToolsFallback、ToolRegistry 解析失败。

### Task 1.3：让工具执行结果和 Trace 成功字段保持一致

#### 当前问题

`ToolDispatcher.dispatch()` 捕获失败后返回错误字符串，`TextAgentLoopOrchestrator` 却固定向 `finishToolDispatch()` 传 true；`finishTool()` 又主要根据 SafetyDecision 判断成功。因此参数失败或反射失败可能同时显示 `invoke_success=false`、`dispatch_success=true`、`tool.success=true`。

#### 修改位置

- `ai/langchain4j/tool/ToolDispatcher.java`
- `ai/langchain4j/tool/ToolRegistry.java`
- 新增 `ai/langchain4j/tool/ToolDispatchOutcome.java`
- `core/AgentConfig.java`
- `core/factory/AgentConfigFactory.java`
- `core/TextAgentLoopOrchestrator.java`
- `trace/AgentTraceRecorder.java`
- `trace/TraceAttributeKeys.java`
- `trace/ToolPhaseTraceTest.java`
- 新增或完善 ToolDispatcher 单元测试

#### 修改方法

1. 新增不可变 `ToolDispatchOutcome`，至少包含 registered、argumentParseSuccess、invokeSuccess、dispatchSuccess、resultText、errorType、errorDetail、targetClass 和 targetMethod。字符串 ToolResult 是输出，不再同时承担成功状态协议。
2. `ToolDispatcher` 增加返回 `ToolDispatchOutcome` 的真实入口；现有返回 String 的 `dispatch()` 只作为 legacy 兼容包装，内部委托结构化入口并返回 `resultText`。
3. `ToolRegistry` 增加统一的 `dispatchWithOutcome()`：未注册工具也必须返回结构化失败，而不是让 TextAgentLoop 自己猜测 dispatcher 是否存在。
4. 所有返回路径都必须构造 outcome：未注册、非法 JSON、参数缺失、反射异常为失败；只有目标方法成功返回后 `dispatchSuccess=true`。
5. `AgentConfigFactory.createTextPersona()` 必须显式注入 `.toolRegistry(toolRegistry)`；同步修改 `AgentConfig.toolRegistry` 的契约注释：它对 TEXT 是真实执行依赖，对 legacy / 非 TEXT 配置仍可为空。TEXT 配置缺少 ToolRegistry 时在启动或执行前返回 INVALID_CONFIG，禁止回退到“执行字符串后把两个诊断字段固定为 true”的路径。
6. TEXT 工具执行只走 `ToolRegistry.dispatchWithOutcome()` 这一条路径。`ToolExecutor` 保留给仍在使用的 legacy / 非 TEXT AgentLoop，本 Task 不破坏它的函数式接口。
7. TextAgentLoop 使用真实 outcome 结束 `tool.dispatch`，并删除固定 true 以及基于异常字符串的判断。
8. 修改 `AgentTraceRecorder.finishTool()`，显式接收 executionSuccess 和 outcome，不再用“Safety 允许”代替“工具执行成功”。
9. Safety veto 记录 outcome=VETOED、tool.success=false；dispatch 失败记录 outcome=FAILED；正常执行为 SUCCESS。
10. 即使工具失败或被 veto，也要生成 ToolResult 写回 ChatMemory，使 ToolExchange 合法闭合；如果 ToolResult 持久化失败，本轮请求失败，并由下一次 SessionHistory 修复尾部不完整 turn。
11. TextAgentLoop 必须区分工具业务失败和 `MemoryPersistenceException`。持久化失败时禁止进入通用工具 catch 后再次尝试写一条 error ToolResult，避免重复失败和错误状态覆盖。
12. `tool.result_writeback` 成功只表示结果同时写入 SessionMemory 和 LoopContext 成功，不代表工具业务执行成功。

#### 测试要求

覆盖工具成功、未注册、非法 JSON、缺少参数、目标方法异常、Safety veto、writeback 异常，并同时断言 ToolResult 和三层 span 属性。增加生产配置接线测试，证明 `createTextPersona()` 生成的 TEXT 配置具有真实 ToolRegistry，且 TextAgentLoop 不进入旧 fallback 分支。

### Task 1.4：统一最终回复、PostProcessor 和 SessionMemory

#### 当前问题

`TextAgentLoopOrchestrator` 在判断 ToolCall 和执行 PostProcessor 之前无条件写入原始 AiMessage，返回给用户的却是 processed output。当前 TEXT PostProcessor 虽然主要是 NoOp，但架构上允许以后修改文本。当前 ToolSafetyEngine 拒绝工具后会写入拒绝 ToolResult 并进入下一次模型迭代，并不存在“全部 veto 时直接生成最终说明文本”的独立生产分支，因此计划不得把不存在的行为当成既有修复点。

#### 修改位置

- `core/TextAgentLoopOrchestrator.java`
- `core/component/PostProcessor.java`（只完善契约说明）
- `core/TextAgentLoopOrchestratorTest.java`
- `runtime/ContextTextEndToEndTest.java`

#### 修改方法

1. 模型返回 Tool Call 时，原始 AiMessage 仍必须立即写入 ChatMemory，后续 ToolResult 才能合法配对。
2. 把当前无条件 `chatMemory.add(aiMessage)` 移入 ToolCall 分支；普通文本分支不得先写 raw AiMessage。
3. 模型返回普通文本时，先执行全部 PostProcessor，再构造唯一的 processed AiMessage。若 Terminator 结束本轮，则在最终取消检查通过后写入一次并返回；若 Terminator 要求继续迭代，也必须写入 processed AiMessage 后再继续，使下一轮 Context 读取到模型实际采用的输出。
4. MemoryExtractor 接收的 aiResponse、ResultCollector 返回的文本、写入 SessionMemory 的最终 AiMessage 必须使用同一个 processed output。只有真正返回给用户的最终文本才执行长期记忆提取。
5. Safety veto 继续沿用“写拒绝 ToolResult -> 下一次模型迭代”的当前行为。本 Task 不新增本地拼装的 veto 最终说明，最终说明仍由下一次 LLM 文本回复产生并经过统一 PostProcessor 流程。
6. PostProcessor 抛异常、最终提交前取消或 SessionMemory 写入失败时，不得返回一个声称已经持久化成功的 final AiMessage。
7. 普通最终回复只能写入一次，禁止 raw 和 processed 同时存在。

#### 测试要求

使用会实际修改文本的测试 PostProcessor，覆盖普通回复、工具后回复、Safety veto 后下一次模型回复、Terminator 继续迭代、PostProcessor 异常、持久化异常和取消；断言外部结果、ChatMemory 和 MemoryExtractor 输入一致。

### Task 1.5：取消 FULL_DEBUG 截断并校正 Context 入模 Trace

#### 当前问题

`TraceAttributeWriter` 在 FULL_DEBUG 下仍使用 TEXT=500、ARGUMENT=200、RESULT=200 的限制。另外 `ContextTraceRecorder` 目前根据 visibility 和内容非空推断 included_in_model，后续发生预算裁剪时会产生错误标记。

#### 修改位置

- `trace/TraceAttributeWriter.java`
- `trace/TraceConfig.java`（只校正文档，不新增配置）
- `trace/TraceManager.java`
- `context/ContextTraceRecorder.java`
- `context/ContextOrchestrator.java`
- `context/ContextAssemblyDebugInfo.java`
- `trace/TraceRedactorTest.java`
- `trace/AgentTraceRecorderTest.java`
- `trace/ContextProviderTraceTest.java`
- `trace/ToolPhaseTraceTest.java`

#### 修改方法

1. FULL_DEBUG 直接写入原始内容，不经过任何业务层 `truncate()`。
2. OFF 保持不采集正文；REDACTED 保持现有脱敏语义。本轮不增加开关和新模式。
3. 使用 `git grep` 审计 TEXT 主路径中所有直接 `substring()`、`truncate()` 和绕过 Writer 的正文 `setAttribute()`。`TraceManager.startAgentRequest()`、Context、LLM、Tool 和 Memory 的 TEXT 内容写入必须全部遵守同一 CaptureMode；非 TEXT 的旧 `startSession()` 若暂时保留截断，必须在注释和验收中明确不属于本轮承诺。
4. Formatter、ContextTraceRecorder、AgentTraceRecorder 不得新增其他长度限制。
5. Provider span 只记录 `provider.produced_model_visible`，表示 Provider 产生了非空候选 Contribution。Provider span 在 Provider 返回后立即结束，不再记录或承诺最终 `included_in_model`。
6. 最终 `included_in_model` 只记录在 assemble 后创建的 fragment/message/toolset span。Phase 1 在无裁剪时根据最终装配结果记录；Phase 2 接入裁剪后由 `ContextAssemblyDebugInfo` 中的 sourceKey 决策表精确标记 included、trimmed 和 trimReason。
7. fragment/message/toolset span 必须从最终装配决策创建，禁止继续遍历全部 MODEL_VISIBLE Contribution 并默认认为全部入模。
8. Trace 写入和 Formatter 异常仍不能中断 Agent 业务。
9. 应用层只保证完整提交给 OpenTelemetry；Phoenix 平台自身限制在 Phase 3 实机验证。

#### 测试要求

构造超过 10 KB 的 System Prompt、SessionMemory、Tool schema、工具参数和结果，断言尾部标记存在且没有 `truncated`；同时回归 OFF 和 REDACTED。另断言 Provider span 只表达 produced，最终 fragment/message/toolset span 才表达 included；不得测试或实现“结束后回填 Provider span”。

### 4.2 Phase 1 阶段门禁

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.context.*" --rerun-tasks
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.memory.*" --rerun-tasks
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.trace.*" --rerun-tasks
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.core.TextAgentLoopOrchestratorTest" --rerun-tasks
.\gradlew.bat testDebugUnitTest --rerun-tasks
```

### 4.3 Phase 1 完成标准

1. SessionMemory 写入不再按 50 条破坏性淘汰，Store 保留完整历史；读取、修复和校验不会破坏 ConversationTurn 或 ToolExchange。
2. LongTermMemory 和 Tool fallback 状态准确。
3. Tool 成功字段不存在逻辑矛盾。
4. 用户最终回复与历史一致。
5. FULL_DEBUG 单测能够读取长内容尾部，Provider produced 与最终 included 语义不再混用。
6. Phase 1 全部测试通过后才进入 Phase 2。

---

## 五、Phase 2：完成长上下文预算恢复闭环

### 5.1 阶段目标

把当前“只估算、超限就失败”的行为升级为可恢复流程：统一 Policy -> 生成结构化装配草稿 -> 安全裁剪 -> 必要时调用 Memory 真实摘要压缩 -> 重新读取 SessionMemory -> 二次装配。Context 管理流程，Memory 实现压缩。

### Task 2.1：建立最小集中式 Context Policy

#### 当前问题

required、visibility、priority、lifecycle 和可裁剪性分散在 Provider 与 Assembler 中。当前预算管理无法稳定判断哪些内容允许删除，也容易出现 Provider.required 与 Contribution.required 不一致。

#### 修改位置

- 建议新增 `context/ContextSourcePolicy.java`
- 建议新增 `context/ContextPolicies.java`
- 建议新增 `context/ResolvedContextPolicy.java`
- `context/ContextContribution.java`
- `context/ContextProvider.java`
- `context/TextContextContribution.java`
- `context/MessageContextContribution.java`
- `context/ToolContextContribution.java`
- `context/provider/` 下 11 个生产 Provider
- `context/ContextMessageAssembler.java`
- `context/ContextBudgetManager.java`
- 新增 `context/ContextPoliciesTest.java`

#### 修改方法

1. 为生产 sourceKey 建立常量和 policy：runtime、persona、prompt、current_user、intent、tool_group、long_term_memory、caller_extra、session_memory_summary、session_memory、vehicle_state、time。`session_memory_summary` 是 Memory 生成的历史摘要 Context Data，不是 CURRENT_USER。
2. 每个 policy 至少包含 lifecycle、visibility、trust、priority、默认 required、是否允许预算裁剪。
3. `ContextPolicies.resolve(sourceKey, session, input)` 返回一次性的 `ResolvedContextPolicy`。请求相关条件只在这里解析：
   - CHAT_ONLY 工具 optional 且为空。
   - 明确工具和 allToolsFallback 工具 required。
   - Vehicle 根据 `requiredContextKeys` 决定是否 required / model-visible。
4. Provider 声明稳定 `sourceKey()`；`required()` 默认委托同一个 resolved policy。Provider 构造 Contribution 时直接传入同一个 `ResolvedContextPolicy`，Contribution 的 required、visibility、priority、trust、lifecycle getter 从该对象读取，不再手工复制一套常量。
5. Prompt、CURRENT_USER、required Contribution 和 ToolSpecifications 不可裁剪。SessionMemory 不允许作为普通 optional Contribution 整体删除；它只允许在 Memory 摘要压缩成功后的第二次装配中自然变短。
6. `session_memory_summary` 使用 UNTRUSTED_DATA、MODEL_VISIBLE 和 HIGH priority，进入安全 Context Data envelope；它不得作为 UserMessage 插入 SessionMemory 消息序列，也不得被识别为 CURRENT_USER。
7. 未注册 sourceKey 在生产 Provider 中属于配置错误，prepare/assemble 必须失败；不得默认为 optional 或可裁剪。测试专用 sourceKey 必须通过测试 policy 显式注册。
8. Provider 继续负责业务读取和异常处理，Policy 不读取 Memory、车辆或 ToolRegistry 业务数据。
9. 不建设通用表达式引擎、动态配置系统或远程 Policy；Demo 使用明确 Java 常量即可。

#### 测试要求

逐个验证全部生产 sourceKey 的 lifecycle、required、visibility、priority 和 trim eligibility；同一请求下 Provider.required 与 Contribution.required 必须来自同一个 `ResolvedContextPolicy`。增加未知 sourceKey、CHAT_ONLY、allToolsFallback、required Vehicle 和 session_memory_summary 的测试。

### Task 2.2：把安全裁剪接入真实 ContextMessageAssembler

#### 当前问题

`ContextBudgetManager.makeDecision()` 只在测试中使用，生产 Assembler 只估算 Token。现有 makeDecision 也存在两个问题：它保护“第一条 UserMessage”而不是最后的 CURRENT_USER，并且在修改 List 后继续使用旧索引，可能删除错误消息。

#### 修改位置

- `context/ContextMessageAssembler.java`
- `context/ContextBudgetManager.java`
- `context/ContextBudgetDecision.java`
- `context/ContextBudgetReport.java`
- `context/ContextAssemblyResult.java`
- 新增包内不可变类型 `context/ContextAssemblyDraft.java`
- 新增包内不可变类型 `context/ContextAssemblyAttempt.java`
- 新增 `context/ContextContributionDecision.java`
- `context/ContextAssemblyDebugInfo.java`
- `context/ContextTraceRecorder.java`
- `context/ContextBudgetIntegrationTest.java`
- `context/ContextMessageAssemblerTest.java`

#### 修改方法

1. 明确三层返回协议，禁止使用 `success=true + withinBudget=false` 表达“等待上层恢复”：
   - `ContextAssemblyDraft`：Assembler 从 Contribution 生成的纯结构候选，不可直接发送模型。
   - `ContextAssemblyAttempt`：一次预算评估结果，包含 draft、BudgetDecision、ContributionDecision、compressionRecommended 和失败原因，只在 Context 包内部使用。
   - `ContextAssemblyResult`：ContextOrchestrator 完成全部恢复后返回给 AgentLoop 的最终结果；成功结果必须 `withinBudget=true` 且消息已校验，失败结果不得携带可发送的半成品 messages/tools，但必须保留 BudgetReport 和 attempt debug 信息用于 Trace。
2. Assembler 先建立结构化 draft，分别保存：
   - 唯一 System Contribution。
   - 按 sourceKey 分开的 Context Data Contributions。
   - SessionMemory 历史。
   - 唯一 CURRENT_USER。
   - 原子 ToolSpecifications 集合。
3. 不允许先合并成普通消息后丢失 sourceKey / priority，再依赖消息位置猜来源。
4. 第一次估算使用完整 draft；预算满足时 materialize 为消息、执行 Validator 并返回，不产生 trim action。
5. 超限时只执行下面的固定顺序：
   - 按 LOW -> NORMAL -> HIGH 删除允许裁剪的 optional Context Data。
   - 每删除一个 Contribution，重新生成 Context Data envelope 并重新估算。
   - optional Context Data 全部处理后仍超限，只要 SessionMemory 中存在可压缩的旧完整 turns，就返回 `compressionRecommended=true`，不在第一次 attempt 中删除 SessionMemory turn。
6. System、CURRENT_USER、required Contribution、session_memory_summary、SessionMemory 和 ToolSpecifications 不允许在第一次 attempt 中直接删除。
7. 工具规格作为本轮一个原子集合；不得为了预算只保留部分工具。
8. `TrimAction.beforeTokens/afterTokens` 记录每次动作前后的总估算；sourceKey 和 actionType 必须可定位具体删除项。`beforeTokens/afterTokens` 都是该动作前后的总输入估算，不得记录成“被删 Token 数”。
9. `ContextContributionDecision` 为每个 sourceKey 记录 produced、included、trimmed、trimReason 和 attemptIndex；最终 Trace 只消费最终 attempt 的 included 状态，同时保留第一次 attempt 的裁剪和压缩建议事件。
10. 只有最终预算通过后才 materialize 消息并执行完整消息序列校验；只有通过的 messages 才能进入成功的 `ContextAssemblyResult`。
11. 如果不包含 SessionMemory 时，不可裁剪的 System + CURRENT_USER + required Context + tools 已经超限，直接返回 `CONTEXT_BUDGET_EXCEEDED`，不得触发 Memory 压缩。
12. 第二次 attempt 使用压缩后 SessionMemory 重新建立 draft，再执行相同 optional 裁剪；第二次仍超限直接失败，不进行第三次压缩，也不通过静默删除历史伪造成功。
13. 删除或停止使用现有基于可变消息索引的历史裁剪算法。Phase 2 的历史缩短只由 Memory 压缩完成；ConversationTurn 解析工具继续由 Memory 校验和压缩共享。

#### 测试要求

1. caller extra / time 等 optional 数据按优先级删除。
2. 删除某个来源后 Context Data envelope 结构仍完整。
3. optional 数据删除后仍超限且存在旧历史时，返回 compressionRecommended，不删除 SessionMemory 消息。
4. Prompt、当前用户、SessionMemory、required Vehicle 和工具集合不会被第一次 attempt 删除。
5. 工具集合或不可裁剪基础内容自身超限时明确失败，并且不调用 Memory。
6. 成功 AssemblyResult 永远 withinBudget；失败结果无可发送消息，但保留 BudgetReport/attempt debug。
7. BudgetReport、TrimAction、ContributionDecision、最终 messages 和 Trace 互相一致。

### Task 2.3：实现 Memory 真实摘要压缩和最多一次二次装配

#### 当前问题

当前 `planSessionCompaction()` 调用 `MemoryCompressor.planCompact()`，只是丢弃旧消息并保留最近消息；`executeCompactionPlan()` 直接写回该列表，没有调用摘要模型。这不能作为生产压缩启用。

#### 修改位置

- `context/ContextOrchestrator.java`
- `context/ContextAssemblyRequest.java`
- `context/ContextAssemblyResult.java`
- `context/ContextBudgetDecision.java`
- `memory/ContextMemoryGateway.java`
- `memory/MemoryCompactionPlan.java`
- `memory/MemoryCompactionResult.java`
- `memory/MemoryOrchestrator.java`
- `memory/MemoryCompressor.java`
- `memory/SessionChatMemoryProvider.java`
- `memory/SessionMemoryStore.java`
- `memory/MemorySnapshot.java`
- `core/factory/AgentConfigFactory.java`（只校正 TEXT memory window 注释，不改模型参数）
- `context/ContextCompressionIntegrationTest.java`
- `core/TextAgentLoopOrchestratorTest.java`
- `runtime/ContextTextEndToEndTest.java`

#### 修改方法

1. 禁止直接启用当前无摘要的 `planCompact()` 结果。
2. `MemoryCompactionPlan` 改为描述 sessionId、targetSessionMemoryTokens、原始消息快照指纹、已有摘要、可压缩完整 turns 和必须保留完整 turns，不把无摘要截断列表当成最终结果。
3. 目标 SessionMemory Token 按本次实际输入计算：`maxInputTokens - nonSessionInputTokens`。`maxInputTokens` 已经扣除了 Profile 的 output reserve 和 safety margin，不再重复扣减。目标值必须大于零；如果不含 SessionMemory 的基础输入已经超限，Context 不创建压缩计划。
4. MemoryCompressor 增加接受 targetSessionMemoryTokens 的真实压缩入口：
   - 只总结可压缩的完整旧 turns。
   - 至少保留最近 2 个完整 turns；如果单个受保护 turn 已超过目标，压缩返回无可行结果而不是拆 turn。
   - 已有摘要作为“旧历史摘要数据”参与本次摘要输入，生成一个替换摘要；禁止把多个带前缀摘要逐层嵌套。
   - 调用现有 summaryModel 生成非空摘要。
   - 保留最近完整 turns 和 ToolExchange。
   - 生成“摘要 + 最近历史”的候选结果。
5. 摘要在 Store 中可以继续使用稳定内部标记持久化，但 `MemorySnapshot` 必须把它解析为独立 `summary` 字段并从 `messages` 中移除。`SessionMemoryContextProvider` 输出两个强类型 Contribution：
   - `session_memory_summary`：TextContextContribution，进入 UNTRUSTED_DATA Context Data envelope，明确标注“系统生成的历史摘要，不是当前用户指令”。
   - `session_memory`：只包含最近完整对话 turns 的 MessageContextContribution。
6. 摘要模型异常、摘要为空、Token 没有下降、候选历史非法或候选仍明显高于 target 时，返回失败并保持原存储不变。
7. 压缩期间不持有 session 写锁。写回前进入同一 session 锁，重新读取 Store 并比较 `MemoryCompactionPlan.originalSnapshotFingerprint`：
   - 指纹一致才通过 `replaceMessagesIfUnchanged()` 原子写回。
   - 指纹不一致返回 `STALE_PLAN`，不得覆盖新消息、会话清理或其他更新。
   - 写回后重新读取并执行 Phase 1 历史校验；缓存的 live ChatMemory 必须立即看到新 Store 内容。
8. ContextOrchestrator 管理单次 assemble 的恢复流程：
   - attempt 1 正常装配和安全裁剪。
   - 只有 BudgetDecision 明确 compressionRecommended、超限原因来自可压缩 SessionMemory、且 `compressionAlreadyAttempted=false` 时调用 Memory。
   - 压缩成功后重新执行 iteration-dynamic Provider，使 SessionMemory 从 Store/live memory 重新读取。
   - attempt 2 重新装配；仍超限则失败，不允许第三次尝试。
9. TextAgentLoop 在 `for (iteration...)` 外维护 `compressionAttemptedForRequest`，每轮构造 `ContextAssemblyRequest` 时传入。只要 Memory 摘要调用真正开始，就把状态视为 attempted；压缩成功后的 `ContextAssemblyResult` 把该状态回传，后续工具迭代不得再次压缩。
10. 压缩模型失败、空摘要、无收益、STALE_PLAN 或写回失败统一映射为 `MEMORY_COMPACTION_FAILED`，但在 errorDetail/Trace 中保留稳定子原因；压缩成功但二次装配仍超限返回 `CONTEXT_BUDGET_EXCEEDED`。
11. 当前用户消息在整个恢复完成、最终预算通过且模型前取消检查通过前不写入 ChatMemory。若压缩发生在后续工具迭代，当前用户已经属于 SessionMemory，必须作为最近受保护 turn 的一部分保留。
12. 取消发生在压缩前时不得调用 summaryModel 或写 Store；摘要模型返回后、写回前再次检查取消。压缩已经原子提交后发生取消，可以保留摘要，但不写新的当前用户消息、不调用下一次主模型。
13. Trace 在同一个 context.assemble 下记录 attemptIndex、compressionRecommended、targetSessionMemoryTokens、tokensBefore/After、snapshotMatch、执行状态、reload 和最终结果；摘要正文和摘要模型调用仍由 Memory trace 记录。
14. `ContextAssemblyResult.compressionAttempted` 不再通过 `memoryCompacted || reloadRequired` 推导，改为显式字段；`reloadRequired` 只表达调用方尚需重载时使用。ContextOrchestrator 已经完成动态 Provider 重载并返回最终结果时，应返回 reload 已完成的事实，避免布尔语义冲突。

#### 测试要求

1. 第一次超限 -> 压缩成功 -> 第二次装配通过 -> 主模型只调用一次。
2. 压缩失败、空摘要、无 Token 收益、非法历史均不覆盖 Store。
3. 在 plan 生成后追加消息或清理 session，执行时返回 STALE_PLAN，新增消息不丢失。
4. 同一次请求包含多轮 Tool Calling，所有 iteration 合计最多调用一次摘要模型；二次装配或后续 iteration 仍超限时不再次压缩。
5. 已有摘要再次压缩后 Store 中只有一个摘要，Context 中摘要进入 Context Data 而不是 CURRENT_USER/UserMessage 历史。
6. 最近 2 个完整 turns 和并行 ToolExchange 不被拆分；单个保护 turn 超目标时明确失败。
7. 当前用户消息只提交一次。
8. 压缩前取消、摘要返回后取消、压缩提交后取消、主模型前取消。
9. 写回后的 live ChatMemory、SQLite 快照和动态 Provider 二次读取内容一致。

### Task 2.4：改进 Token 估算并记录与真实 usage 的误差

#### 当前问题

`HeuristicContextTokenEstimator` 主要使用 `ChatMessage.toString()` 和字符除二估算。它速度快，但对中文、JSON、Tool Call 和 schema 的偏差没有数据证据。

#### 修改位置

- `context/HeuristicContextTokenEstimator.java`
- `context/ModelContextWindowProfiles.java`
- `core/TextAgentLoopOrchestrator.java`
- `trace/AgentTraceRecorder.java`
- `trace/TraceAttributeKeys.java`
- `context/HeuristicContextTokenEstimatorTest.java`

#### 修改方法

1. 按消息真实字段估算，不再只依赖 `toString()`：System/User 文本、Ai 文本、Tool Call ID/name/arguments、ToolResult ID/name/text 都要计入。
2. ToolSpecification 继续计入 name、description、parameters schema 和固定结构开销。
3. 把 Context 最终 estimatedInputTokens 传给 `gen_ai.chat` Trace。
4. 模型返回 TokenUsage 时记录 actualInputTokens、estimateDelta、estimateRatio；未返回 usage 时记录 unavailable，不让请求失败。
5. 增加保守估算校准系数，但不做远程配置。先以现有公式作为基线，使用设备实际 usage 校验；如果任一代表性样本低估超过 10%，必须在本 Task 内调整固定安全系数并回归，而不是只记录误差后宣布完成。
6. 当前 `qwenTurboDemo()` 的 32768 / 2048 / 1024 初始值保持不变。只有设备证据证明安全余量不足时才修改，并在文档记录调整前后数据。
7. 不引入精确网络 tokenizer，不增加额外模型调用。

#### 测试要求

覆盖中英文、JSON、Tool Call、ToolResult、长 schema 和 null 字段，确保内容增加时估算单调增加；构造带 TokenUsage 的模型响应验证 Trace 误差字段。设备侧至少采集 10 个代表性 TEXT 请求，包含普通中文、长历史、并行工具和 allToolsFallback；验收时不得存在超过 10% 的未解释低估，且实际输入不得突破 Profile 的模型窗口。

### 5.2 Phase 2 阶段门禁

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.context.*" --rerun-tasks
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.memory.*" --rerun-tasks
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.runtime.ContextTextEndToEndTest" --rerun-tasks
.\gradlew.bat testDebugUnitTest --rerun-tasks
.\gradlew.bat assembleDebug --rerun-tasks
```

### 5.3 Phase 2 完成标准

1. Context Policy 是预算资格和优先级的单一来源。
2. Draft、Attempt 和最终 AssemblyResult 的状态协议明确；AgentLoop 不会收到 `success=true` 但预算失败的半成品。
3. BudgetManager 已进入生产 Assembler，不再只存在于测试。
4. 超预算只先裁剪 optional Context Data，必要时调用 Memory 真实摘要压缩；第一次 attempt 不静默删除 SessionMemory。
5. 单次 Agent 请求跨全部 iteration 最多调用一次压缩，并最多进行一次二次装配。
6. 压缩计划过期时不覆盖新消息；摘要以独立 Context Data 入模，不冒充当前用户消息。
7. required 内容、当前用户、SessionMemory 和工具集合不会被静默裁掉。
8. Token 估算与实际 usage 的误差可从 Trace 查看，并达到约定的低估阈值。
9. Phase 2 门禁全部通过后才进入清理阶段。

---

## 六、Phase 3：清理技术债并完成总体验收

### 6.1 阶段目标

在新链路稳定并有测试保护后，删除 Context 包内第二套事实字段和确认无引用的 Legacy API；随后执行全量 JVM、构建、Lint、设备和 Phoenix 验收，并更新 overview。

### Task 3.1：让 ContextFrame 只保留当前事实模型

#### 当前问题

ContextFrame 同时保存 Contributions 和 memorySummary、vehicleStateSnapshot、timeContext、promptContext、renderedExtraContext、tokenEstimate、debugInfo、sections。后者已无生产读取，但会诱导后续代码重新引入双事实源。

#### 修改位置

- `context/ContextFrame.java`
- `context/ContextFrameBuilder.java`
- `context/ContextOrchestrator.java`
- `context/ContextFrameBuilderTest.java`
- 所有构造 ContextFrame 的测试

#### 修改方法

1. 先用 `git grep` 分别确认每个旧 getter / setter 没有生产调用。
2. 删除 memorySummary、vehicleStateSnapshot、timeContext、promptContext、renderedExtraContext、tokenEstimate、ContextDebugInfo、sections 字段及构造参数。
3. 删除 ContextFrameBuilder 对应字段和链式 setter。
4. Frame 保留请求身份、Intent/ToolGroup 选择结果和不可变 Contributions。
5. Provider 数据只存在于强类型 Contribution；预算只存在于 AssemblyResult/BudgetReport；调试结果只存在于 ProviderOutcome、AssemblyDebugInfo 和 Trace。
6. 不在本 Task 删除类型文件，避免字段删除和文件删除混在一起难以排查；类型文件在 Task 3.2 删除。

#### 测试要求

更新 Frame 构造和不可变性测试，并执行 `git grep` 证明旧 getter/setter 无引用。

### Task 3.2：删除 Context 包内无引用的 Legacy 类型和方法

#### 修改位置

- 删除候选 `context/ContextSection.java`
- 删除候选 `context/ContextSectionType.java`
- 删除候选 `context/ContextDebugInfo.java`
- `context/ContextProvider.java`
- `context/ContextBudgetManager.java`
- `context/ContextTraceRecorder.java`
- `context/ContextOrchestrator.java`
- 对应旧测试

#### 修改方法

1. 删除 ContextSection、ContextSectionType、ContextDebugInfo 及只验证旧类型的测试。
2. 删除旧字符预算 API 和字段：`trim(String,int)`、`estimateTokens(String)`、旧 char limits，以及确认无生产调用的 `generateBudgetReport()`。
3. 保留 Phase 2 已接入的结构化 BudgetDecision API。
4. 删除 ContextTraceRecorder 中无调用的旧 root record、Provider event、assembled attribute/event 兼容方法。
5. 删除 ContextOrchestrator 中已注释掉的 legacy Trace 调用和无效兼容说明。
6. 修复 `ContextProvider` 仍声称 Provider 返回 ContextSection 的过时 Javadoc。
7. 每删除一个类型或方法后使用 `git grep` 确认无引用；不能只根据 IDE 灰色提示判断。

#### 工作边界

不删除仍被 VOICE / Scene 使用的 AgentLoopOrchestrator、PreProcessor、PostProcessor 或 AgentConfigFactory 配置。这些属于非 TEXT 旧业务链路，不是 Context 包内死代码。

### Task 3.3：校正阶段注释和非 TEXT 边界说明

#### 修改位置

- `context/` 包内带 Phase 2/3/4/5/6 或 shadow/hybrid 描述的类
- `memory/ContextMemoryGateway.java`
- `memory/MemoryOrchestrator.java`
- `core/AgentLoopOrchestrator.java`
- `core/preprocessor/MemoryPreProcessor.java`

#### 修改方法

1. 删除“Phase 4 仅影子”“Phase 6 删除”“骨架暂留”等已经失效的时间性注释。
2. 注释改为描述当前职责和保留原因，不记录已经结束的实施阶段。
3. 对旧 `AgentLoopOrchestrator.execute(String, Map)` 明确注明它仍服务于 VOICE / Scene 等非 TEXT 路径，TEXT 不使用。
4. 不把仍被调用的代码标记为“待删除的无用代码”，避免后续误删。
5. 不顺便重构非 TEXT 业务逻辑。

### Task 3.4：补齐最终自动化回归

#### 必须覆盖

1. 简单连续 TEXT 对话和重复文本。
2. Session、User、Persona 隔离语义。
3. 49/50/51/100 条写入后 Store 保留完整历史，以及完整 ConversationTurn/ToolExchange。
4. 尾部中断历史自动修复、中间损坏硬失败和 Store 写入异常传播。
5. optional Context Data 裁剪、required/SessionMemory 保留、工具集合超限。
6. 压缩成功、失败、无收益、过期计划、摘要替换和跨全部 iteration 最多一次重试。
7. CHAT_ONLY、明确工具、allToolsFallback。
8. 工具成功、未注册、参数失败、invoke 失败、veto 和 writeback，且 TEXT 只走结构化 ToolRegistry outcome。
9. PostProcessor 最终写回一致性，包括 Terminator 继续迭代和 veto 后模型回复。
10. Context/LLM/Tool/Memory Trace 父子关系、完整正文、Provider produced 与最终 included 状态一致性。
11. 取消发生在 prepare、assemble、摘要模型前后、压缩写回前后、主模型前后和工具阶段。
12. Token 估算误差字段及保守校准系数回归。

#### 测试边界

如果真实 ToolRegistry 因 Android `Log` 在 JVM 环境不可用，允许在 ToolRegistry 边界使用测试替身，但替身必须实现与生产相同的 `dispatchWithOutcome()` 契约，并且测试必须经过真实 ContextOrchestrator、ContextMessageAssembler、ContextMessageSequenceValidator 和 TextAgentLoopOrchestrator。禁止 Fake Gateway 直接返回手工 ChatMessage 冒充 Context 集成测试。至少保留一项配置接线测试证明 AIAgentService/AgentConfigFactory 会把真实 ToolRegistry 注入 TEXT 链路。

### Task 3.5：设备和 Phoenix 验收

#### 设备测试场景

1. 至少 10 轮连续普通对话，检查回复质量和历史顺序。
2. 构造超过 50 条且边界附近有工具调用的会话，确认 SQLite 中没有因固定窗口丢失旧消息。
3. 构造超预算历史，确认只先裁剪 optional Context Data，必要时摘要压缩一次并继续对话；不得在压缩前静默删除 SessionMemory。
4. 分别执行 CHAT_ONLY、明确工具和 allToolsFallback。
5. 分别执行工具成功、非法参数、工具异常和 Safety veto。
6. 切换 session 后不携带旧短期历史；同 session 切换 user 后保留会话历史但切换长期记忆。
7. 在压缩 plan 生成后模拟新消息写入，确认旧 plan 被拒绝且新消息未丢失。
8. 在压缩前后触发取消，确认无重复响应和当前用户消息污染。
9. 验证摘要显示为系统生成的历史 Context Data，不显示成 CURRENT_USER；再次压缩后只有一个有效摘要。
10. 采集至少 10 个代表性请求的 estimated/actual input token，确认无超过 10% 的未解释低估。

#### Phoenix 验收内容

1. `agent.request -> agent.loop -> agent.iteration -> context/llm/tool` 属于同一 traceId 且父子关系正确。
2. Provider status、Contribution 是否入模、最终 `gen_ai.request.messages` 能互相核对。
3. 长 System Prompt、长 SessionMemory、完整工具 schema、参数和结果能看到尾部标记，没有业务层 truncated。
4. Tool 的 dispatch_success、invoke_success、tool.success 和 outcome 一致。
5. 能看到预算 before/after、TrimAction、压缩 attempt、压缩结果和 Token 估算误差。
6. 如果 Phoenix 或 exporter 自身限制超长 attribute，单独记录平台限制证据，不重新在业务代码中静默截断。
7. Provider span 使用 produced 语义，最终 fragment/message/toolset span 使用 included/trimmed 语义；不得出现已被裁剪的 sourceKey 仍显示 included=true。
8. 多轮工具迭代属于同一个 Agent 请求时，Memory 压缩 span 最多出现一次；STALE_PLAN 能看到 snapshotMatch=false 且无 Store 覆盖。

### Task 3.6：更新总结文档

#### 修改位置

- `docs/overview/context-module-overview.md`
- `README.md` 中 Context、Memory、Trace 和测试状态
- 新增 `docs/act_summary/` 下的本轮完成总结

#### 修改方法

1. 以最终代码、测试数量和设备证据更新完成度，不沿用旧百分比。
2. 删除已经解决的问题，只保留仍能在代码或设备上复现的风险。
3. 明确 TEXT Context 已完成的范围，以及 VOICE / Scene 等非 TEXT 旧链路仍被保留的边界。
4. 不把“接口存在”写成“生产能力已完成”；压缩、裁剪和 Trace 必须有真实执行证据。

### 6.2 Phase 3 阶段门禁

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.context.*" --rerun-tasks
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.memory.*" --rerun-tasks
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.trace.*" --rerun-tasks
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.runtime.ContextTextEndToEndTest" --rerun-tasks
.\gradlew.bat testDebugUnitTest --rerun-tasks
.\gradlew.bat assembleDebug --rerun-tasks
.\gradlew.bat lintDebug --rerun-tasks
```

### 6.3 Phase 3 完成标准

1. ContextFrame 和 Context 包只剩一套事实模型。
2. Context 内无引用 Legacy 已删除，非 TEXT 旧链路被准确标注并保持可运行。
3. 全量单测、assembleDebug 和 lintDebug 通过。
4. 设备长会话、完整历史持久化、预算恢复、过期计划保护、用户/会话隔离和工具场景通过。
5. Phoenix 能完整、准确展示 Context、模型、工具和 Memory 执行过程。
6. overview、README 和工作总结与最终代码状态一致。

---

## 七、最终完成定义

只有同时满足以下条件，才可以宣布本计划完成：

1. TEXT 进入模型的 messages 和 tools 全部来自 ContextAssemblyResult。
2. Memory Store 不再按固定 50 条淘汰 TEXT 历史，SessionMemory 不会截断完整 turn 或 ToolExchange。
3. 尾部中断历史可按固定规则原子修复，中间损坏不会被静默掩盖。
4. Context 根据统一 Policy 只裁剪 optional Context Data，Memory 完成真实摘要压缩。
5. 单次 Agent 请求跨全部 iteration 最多执行一次压缩和一次二次装配；过期压缩计划不覆盖新消息。
6. required 内容、System Prompt、当前用户、SessionMemory 和工具集合不会被静默删除。
7. Tool 执行通过唯一结构化 outcome 驱动 TEXT Trace，不再由错误字符串或固定 true 推断成功。
8. Provider produced 与最终 included/trimmed 分开记录，Tool、LLM、Memory 的 Trace 状态与真实执行一致。
9. FULL_DEBUG 不做 TEXT 应用层内容截断。
10. 用户收到的最终回复与 SessionMemory 中的最终 AiMessage 一致。
11. 压缩摘要作为独立历史 Context Data 入模，不冒充 CURRENT_USER，重复压缩后只保留一个有效摘要。
12. ContextFrame 和 Context 包不再存在第二套 Legacy 事实源。
13. AIDL 和外部 App 接口没有变化。
14. 全量自动化测试、构建、Lint、设备和 Phoenix 验收均完成。

完成后，Context 模块应达到以下状态：

> TEXT 模型输入由 Context 唯一控制；Prompt、Memory、Tool 等模块继续实现各自能力；Memory 完整保存历史并负责原子修复与摘要，Tool 通过结构化 outcome 报告真实结果，Context 统一调用、装配、校验、预算管理和一次性恢复，并通过 Trace 区分候选产出与最终实际入模内容。
