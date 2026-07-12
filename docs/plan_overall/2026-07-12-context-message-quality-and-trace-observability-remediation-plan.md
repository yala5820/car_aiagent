# Context 消息质量与完整 Trace 修复计划

**日期：** 2026-07-12  
**项目阶段：** Demo  
**目标：** 修复模型输入缺失和 Context Data 混淆，并把 Agent 执行过程中的所有关键内容完整写入 Trace。

---

## 一、固定边界

本项目当前是 Demo，Trace 以清晰、完整、便于排查问题为唯一目标。

本轮固定决策：

1. 不设计 Trace 内容采集模式。
2. 不区分 OFF、REDACTED、FULL_DEBUG。
3. 不增加运行时 Trace 开关。
4. 不考虑 Trace 内容隐私和脱敏。
5. 不对消息、Provider 内容、工具 schema、参数和模型响应做应用层截断。
6. 所有详细内容直接写入 Trace。
7. 不修改 AIDL 和外部 App 接口。
8. 不修改模型参数、Prompt、Memory 算法和 ToolGroupSelector。

如果 OpenTelemetry SDK、OTLP exporter 或 Phoenix 自身存在长度上限，本轮不在业务代码中提前截断；应先发送完整内容，再根据实际平台表现单独处理。

---

## 二、Task 1：恢复当前用户消息

### 当前状态

已完成并通过验收。

### 已实现行为

1. `ContextMessageAssembler` 同时识别 `SOURCE_SESSION_MEMORY` 和 `SOURCE_CURRENT_USER`。
2. iteration 0 的消息顺序为：

```text
SystemMessage
-> 可选 Context Data UserMessage
-> SessionMemory 历史
-> 当前 UserMessage
```

3. iteration 0 必须存在且只存在一个 CURRENT_USER Contribution，其中必须只有一个 UserMessage。
4. iteration 1 及以后不重复追加静态 CURRENT_USER，使用 SessionMemory 中已经提交的当前用户和 ToolExchange。
5. 当前 UserMessage 只在 assemble 成功、预算通过、模型调用前取消检查通过后写入 SessionMemory。
6. 超预算、assemble 失败和模型调用前取消不污染 SessionMemory。

### 验收遗留

补充一个真实端到端测试：使用同一个 Runtime 和 sessionId 连续发送两次相同文本，第二次 ChatRequest 中必须同时保留第一轮历史和第二轮当前消息，最后一条是第二轮 CURRENT_USER。禁止使用两个独立 Frame 代替真实会话。

---

## 三、Task 2：隔离 Context Data

### 当前状态

已完成并通过验收。

### 已实现行为

1. 长期记忆、车辆状态、时间和 caller extra 使用统一 Context Data envelope。
2. 每个 Contribution 显示 source 和 trust。
3. Context Data 明确声明为背景事实，不是用户指令。
4. 外部内容中的 Context marker 会被转义。
5. 空 Context Data 不产生空白 UserMessage。
6. CHAT_ONLY 不向模型注入车辆状态 JSON。
7. 明确车控仍注入车辆状态。
8. 当前 UserMessage 保持为 iteration 0 最后一条消息。

### 验收遗留

新增 `VehicleStateContextProviderTest`：

- CHAT_ONLY 输出 `POLICY_ONLY`。
- 需要 `vehicle_status` 的车控请求输出 `MODEL_VISIBLE`。
- required 场景下车辆状态读取失败时正确阻断。

---

## 四、Task 3：完整 Trace

## 4.1 工作目标

在 Phoenix 中打开一次请求后，必须能够直接回答：

1. 用户本轮说了什么。
2. Runtime 解析出的 userId、sessionId、personaId、intent 和 ToolGroup 是什么。
3. 每个 Provider 是否执行、返回什么状态、提供了什么完整内容。
4. Context 最终按什么顺序拼出了哪些 ChatMessage。
5. 最终向模型暴露了哪些完整 ToolSpecification。
6. 模型实际收到的请求是什么。
7. 模型返回了什么文本或 Tool Call。
8. SafetyGuard 做出了什么判断。
9. 每个工具收到什么参数、返回什么结果。
10. Memory 写入、提取和压缩执行了什么。
11. 请求最终成功、失败、取消或超时在什么位置发生。

## 4.2 修改文件

- `app/src/main/java/com/hirain/aiagent/context/ContextTraceRecorder.java`
- `app/src/main/java/com/hirain/aiagent/context/ContextOrchestrator.java`
- `app/src/main/java/com/hirain/aiagent/core/TextAgentLoopOrchestrator.java`
- `app/src/main/java/com/hirain/aiagent/trace/AgentTraceRecorder.java`
- `app/src/main/java/com/hirain/aiagent/trace/TraceAttributeKeys.java`
- `app/src/main/java/com/hirain/aiagent/trace/TraceMessageFormatter.java`
- `app/src/test/java/com/hirain/aiagent/context/ContextTraceRecorderTest.java`
- `app/src/test/java/com/hirain/aiagent/trace/ContextProductionTraceHierarchyTest.java`
- `app/src/test/java/com/hirain/aiagent/core/AgentLoopOrchestratorTraceTest.java` 或新增 TEXT Trace 测试
- `app/src/test/java/com/hirain/aiagent/runtime/ContextTextEndToEndTest.java`
- 新增 `app/src/test/java/com/hirain/aiagent/context/provider/VehicleStateContextProviderTest.java`

不需要修改：

- `TraceConfig.java`
- `TraceAttributeWriter.java`
- `AIAgentService.kt` 的 Trace 模式装配
- AIDL 文件

本 Task 不再建设配置、脱敏、CapturedContent、截断策略或复杂 Trace formatter 层。

## 4.3 Trace 层级

保持以下层级：

```text
agent.request
  -> agent.loop
       -> context.prepare
       -> context.assemble
       -> gen_ai.chat
       -> tool.execute
       -> memory.extract / memory.compress
  -> response.dispatch
```

要求：

1. `context.prepare`、`context.assemble`、`gen_ai.chat` 和 `tool.execute` 必须是同一个 `agent.loop` 的子 span。
2. 不为每个 Provider 创建 span，Provider 使用 event，避免 Trace 树过深。
3. 每个 span 必须正常结束；失败时设置 ERROR 和错误信息。

## 4.4 agent.request 根 Span

完整记录：

```text
request.id
session.id
user.id
persona.id
client_message_id
source_app
input_type
user.input
extra_context
intent.tag
intent.confidence
intent.matched_keywords
intent.debug_reason
tool_group.selected_group_ids
tool_group.selected_tool_names
tool_group.selection_reason
tool_group.fallback_used
tool_group.all_tools_fallback
request.started_at
request.finished_at
request.duration_ms
request.result
request.error_type
request.error_detail
```

`user.input` 和 `extra_context` 记录完整原文，不截断。

## 4.5 context.prepare Span

### 汇总属性

```text
context.phase=prepare
context.result=success|failed|cancelled
context.provider_count
context.provider_success_count
context.provider_fallback_count
context.provider_failed_count
context.contribution_count
context.current_user.contribution_count
context.duration_ms
context.error_code
context.error_detail
context.error_provider
```

### Provider Event

每执行一个 request-static Provider，添加一个 `context.provider.output` event：

```text
provider.name
provider.lifecycle
provider.required
provider.status
provider.duration_ms
provider.error_code
provider.error_reason
provider.contribution_count
provider.source_keys
provider.output
```

`provider.output` 完整记录：

- Text Contribution：sourceKey、targetArea、visibility、trust、priority、required、完整 content、完整 metadata。
- Message Contribution：sourceKey、messageSource、完整消息数量以及每条消息的 role 和完整内容。
- Tool Contribution：sourceKey、mode、完整 ToolSpecification，包括 name、description 和 parameters schema。

Provider 抛异常时也必须写 event，然后再按 required/optional 规则处理。

## 4.6 context.assemble Span

### 汇总属性

```text
context.phase=assemble
context.iteration
context.result=success|failed|cancelled|budget_exceeded
context.message_count
context.tool_count
context.provider_outcome_count
context.current_user.included
context.current_user.count
context.current_user.is_last
context.estimated_tokens
context.max_input_tokens
context.within_budget
context.sequence_valid
context.error_code
context.error_detail
context.assembled_messages
context.assembled_tool_specs
```

`context.assembled_messages` 必须完整记录最终 `ContextAssemblyResult.messages()`，包括消息索引、角色和完整内容。

示例：

```text
[0][system] 完整 System Prompt
[1][user][context_data] 完整 Context Data envelope
[2][user][session_memory] 历史用户消息
[3][assistant] 历史模型回复
[4][user][current_user] 本轮用户消息
```

`context.assembled_tool_specs` 必须完整记录每个工具的：

```text
name
description
parameters schema
```

### 逐消息 Event

每条最终消息增加一个 `context.message` event：

```text
message.index
message.role
message.source
message.content
message.has_tool_calls
message.tool_call_ids
message.tool_call_names
message.tool_call_arguments
```

所有正文和参数完整记录，不截断。

### 动态 Provider

SessionMemory、Vehicle、Time Provider 同样使用 `context.provider.output` event，字段与 prepare 完全一致。

### 失败路径

1. required Provider 失败：记录已经执行的 Provider event、错误 Provider、错误码和错误详情。
2. 消息序列失败：记录错误码与 debugInfo，不把不存在的半成品标记为成功消息。
3. 超预算：记录完整 attempted messages、完整工具 schema、估算 token 和 `within_budget=false`；后续不得出现 `gen_ai.chat`。
4. 取消：记录发生取消的检查点。

## 4.7 gen_ai.chat Span

`TextAgentLoopOrchestrator` 已持有最终：

```java
List<ChatMessage> requestMessages;
List<ToolSpecification> requestTools;
ChatRequest request;
```

Trace 必须直接记录这三个真实对象，不允许从 Frame、Memory 或 Provider 重新拼装。

新增或扩展 `AgentTraceRecorder.recordLlmRequest(...)`，记录：

```text
gen_ai.request.iteration
gen_ai.request.model
gen_ai.request.message_count
gen_ai.request.messages
gen_ai.request.tool_count
gen_ai.request.tool_specs
```

其中 messages 和 tool specs 全量记录，不截断。

模型响应记录：

```text
gen_ai.response.text
gen_ai.response.has_tool_calls
gen_ai.response.tool_call_count
gen_ai.response.tool_calls
gen_ai.response.finish_reason
gen_ai.usage.input_tokens
gen_ai.usage.output_tokens
gen_ai.usage.total_tokens
gen_ai.duration_ms
gen_ai.error
```

每个 Tool Call 完整记录 id、name 和 arguments。

## 4.8 tool.execute Span

每次工具执行完整记录：

```text
tool.id
tool.name
tool.arguments
tool.safety.allowed
tool.safety.reason
tool.result
tool.duration_ms
tool.cancelled
tool.error
```

SafetyGuard 在 ToolExecutor 前执行，因此 Safety 结果必须在同一个工具 span 中可见。被 veto 的工具也必须记录完整 arguments、veto 原因和最终回填给模型的 ToolResult。

多工具取消时，每个未执行工具的 cancelled ToolResult 也必须能从 Trace 中看到。

## 4.9 Memory Trace

保留现有 Memory span，并确保完整记录：

```text
memory.operation
memory.user_id
memory.session_id
memory.input_messages
memory.prompt
memory.output
memory.candidate_count
memory.compressed
memory.error
memory.duration_ms
```

如果本次请求没有执行压缩，Trace 中不伪造 memory.compress span。Context 预算超限时只记录未调用 compaction 的事实。

## 4.10 TraceAttributeKeys

在 `TraceAttributeKeys` 集中增加上述稳定 key。禁止在多个类中重复散落相同字符串。

Provider 使用 event 区分实例，不生成 `provider.1.xxx` 之类动态 attribute key。

## 4.11 格式化要求

扩展现有 `TraceMessageFormatter`，移除它对本 Task 新增完整格式方法的 1000 字符截断。

至少提供：

```text
formatMessagesFull(messages)
formatMessageFull(message)
formatToolSpecificationsFull(specs)
formatToolCallsFull(requests)
formatContributionFull(contribution)
```

格式化器必须是纯函数，不访问数据库、Provider、ToolRegistry、车辆或网络。

不需要新增复杂的 capture 对象、模式判断或脱敏封装。

## 4.12 异常边界

Trace 代码不能影响 Agent 运行：

1. formatter 异常时记录 `trace.format.error`，继续业务流程。
2. span/event 写入异常时忽略 Trace 写入，继续业务流程。
3. exporter/Phoenix 不可用时不能让 Context、模型或工具失败。
4. 禁止在 Trace 中重新调用任何业务模块。

这不是隐私保护设计，只是保证观测系统不能破坏业务系统。

## 4.13 必须完成的测试

1. 同 Session 连续发送两次相同文本，两次均正确进入模型请求。
2. Vehicle Provider 的 POLICY_ONLY、MODEL_VISIBLE 和 required failure。
3. prepare span 存在全部 request-static Provider events。
4. assemble span 存在全部 dynamic Provider events。
5. Provider event 包含完整 Contribution content 和 metadata。
6. iteration 0 的最后一条 message event 是当前用户完整文本。
7. iteration 1 不重复静态 CURRENT_USER。
8. assembled messages 包含完整 System Prompt、Context Data、历史和当前用户。
9. CHAT_ONLY 的完整 tool specs 为空。
10. 明确车控记录选中工具的完整 description 和 parameter schema。
11. allToolsFallback 记录全部启用工具完整 schema。
12. gen_ai.chat 请求内容与 context.assemble 内容一致。
13. 模型文本响应完整记录。
14. 模型 Tool Call 的 id、name、arguments 完整记录。
15. Safety allow/veto 和工具结果完整记录。
16. required Provider 失败仍记录失败 event 和错误详情。
17. 超预算记录完整 attempted messages，但不存在 gen_ai.chat span。
18. 取消路径记录取消检查点和 cancelled ToolResult。
19. Trace formatter/writer 抛异常不改变 RuntimeResult。
20. parentSpanId 满足 `agent.loop -> context.prepare/context.assemble/gen_ai.chat/tool.execute`。
21. 构造超过 10 KB 的消息和工具 schema，断言应用代码写入的 Trace 内容没有 `truncated` 标记且包含尾部测试标识。

## 4.14 实施顺序

1. 补齐 Task 1-2 两个遗留测试。
2. 扩展 `TraceAttributeKeys`。
3. 扩展 `TraceMessageFormatter` 的完整格式方法。
4. 在 `ContextTraceRecorder` 增加 Provider event 和 assemble result 记录方法。
5. 在 `ContextOrchestrator.prepare()` 接入静态 Provider 和结果 Trace。
6. 在 `ContextOrchestrator.assemble()` 接入动态 Provider、最终消息、工具、预算和失败 Trace。
7. 在 `AgentTraceRecorder/TextAgentLoopOrchestrator` 接入完整 LLM 请求和响应 Trace。
8. 补齐 Tool/Safety/Memory 现有 Trace 缺失字段。
9. 运行目标测试和完整 Gradle 门禁。
10. 安装 Debug APK，在 Phoenix 核对简单聊天、连续聊天、明确车控和 allToolsFallback。

---

## 五、测试门禁

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.context.*" --rerun-tasks
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.trace.*" --rerun-tasks
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.runtime.ContextTextEndToEndTest" --rerun-tasks
.\gradlew.bat testDebugUnitTest --rerun-tasks
.\gradlew.bat assembleDebug --rerun-tasks
.\gradlew.bat lintDebug --rerun-tasks
```

所有命令必须成功，禁止通过删除测试、放宽消息内容断言或恢复旧消息链路让门禁通过。

---

## 六、设备验收

在手机或车机上执行：

1. 发送“你好”，确认 Trace 中最终一条用户消息就是完整“你好”。
2. 连续发送两个问题，确认第二轮包含历史和当前问题。
3. 连续两次发送相同文本，确认两次请求都没有被去重。
4. CHAT_ONLY 确认没有车辆 JSON、没有工具 schema。
5. 明确空调控制确认车辆状态、工具 schema、模型 Tool Call、Safety、工具参数和结果全部可见。
6. 模糊指令确认 allToolsFallback 的完整工具集合可见。
7. 对照 `context.assemble` 和 `gen_ai.chat`，确认消息与工具完全一致。
8. 确认长消息和长 schema 在应用 Trace 中没有被截断。

---

## 七、完成定义

只有满足以下条件才能宣布完成：

- Task 1-2 的两个遗留测试补齐。
- 每个 Provider 的状态和完整输出均可见。
- 最终 assembled messages 和完整工具 schema 可见。
- LLM 实际请求和响应可见。
- Safety、工具参数、工具结果和 Memory 操作可见。
- 消息和 schema 不做应用层截断。
- Trace 失败不影响 Agent。
- 完整测试、assembleDebug、lintDebug 和设备 Phoenix 验收通过。

