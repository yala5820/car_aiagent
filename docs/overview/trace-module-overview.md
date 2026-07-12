# AIAgent Trace 模块重构方案

**编制日期：** 2026-07-12  
**文档性质：** 现状核对 + 重构设计，不代表当前代码已经实现  
**核对依据：**
- `app/src/main/java/com/hirain/aiagent/AIAgentService.kt`
- `app/src/main/java/com/hirain/aiagent/runtime/AgentRuntime.java`
- `app/src/main/java/com/hirain/aiagent/core/TextAgentLoopOrchestrator.java`
- `app/src/main/java/com/hirain/aiagent/context/ContextOrchestrator.java`
- `app/src/main/java/com/hirain/aiagent/context/ContextTraceRecorder.java`
- `app/src/main/java/com/hirain/aiagent/context/ContextMessageAssembler.java`
- `app/src/main/java/com/hirain/aiagent/trace/TraceManager.java`
- `app/src/main/java/com/hirain/aiagent/trace/TraceSession.java`
- `app/src/main/java/com/hirain/aiagent/trace/TracingOkHttpInterceptor.java`

---

## 一、结论

当前 trace 系统的核心问题不是“展示不友好”，而是 **TEXT 主链路的 span 父子关系本身就不可靠**，因此现在看到的 Phoenix 结果不能真实反映一次请求里的 `context -> llm -> tool -> memory -> response` 完整链路。

本次建议的重构方向只有两条主线：

1. **先把 trace 上下文接成一棵树**
   `agent.request`、`agent.loop`、`context.prepare`、`context.assemble`、`gen_ai.chat`、`tool.execute` 必须全部从同一个 `TraceSession` 派生，不能混用 `TraceManager` 私有 SDK 和 `GlobalOpenTelemetry`。
2. **把 context 从“大串文本”改成“Provider/消息片段可观测”**
   不再默认输出一大坨 `context.assembled_messages`，而是把每个 Provider 的产物、是否进模型、进入哪个消息槽位、最终被拼成了哪条消息，拆成独立 span。

---

## 二、当前实现的真实状态

### 2.1 `agent.request` 与后续 span 不一定在同一棵树上

当前 TEXT 请求的 root span 由 `TraceManager.startAgentRequest()` 创建，Service 层也会在执行前调用 `session.makeCurrent()`，这一段是正常的。

但 `AgentRuntime.execute()` 又单独使用：

```java
GlobalOpenTelemetry.get().getTracer("agent").spanBuilder("agent.loop")
```

来创建 `agent.loop`。这和 `TraceManager` 内部持有的 `OpenTelemetrySdk` 不是同一套来源。结果是：

- `agent.loop` 可能是 no-op span
- `Context.current()` 可能被这个 no-op loop scope 覆盖
- 后续 `context.prepare`、`context.assemble`、`gen_ai.chat`、`tool.execute` 若继续拿 `Context.current()` 当 parent，就可能从 `agent.request` 主 trace 上脱落

这也是目前 trace 结果看起来“缺胳膊少腿”的第一根因。

### 2.2 Context 现在记录的是 event 和大字符串，不是 Provider 级 span

当前 `ContextTraceRecorder` 做了三件事：

1. 给 `context.prepare` / `context.assemble` 打一些聚合 attribute
2. 用 `context.provider.output` event 记录 Provider 结果
3. 用 `context.assembled_messages` 和 `context.message` event 记录最终消息

问题在于：

- Provider 只有 event，没有独立 span，持续时间、失败点、父子层级都不清楚
- 最终消息仍然偏向“拼完再倒出来”，而不是“逐个来源展示”
- `context.assembled_messages` 会把所有消息揉成一个大属性，阅读和排错都很差

### 2.3 `tool.execute` 在 TEXT 主链路里没有正确成为当前 span

`TextAgentLoopOrchestrator.execute()` 中确实有：

- `trace.startTool(...)`
- `trace.finishTool(...)`
- `toolSpan.end()`

但它 **没有像旧版 `AgentLoopOrchestrator.execute(String, Map)` 那样对 `toolSpan.makeCurrent()` 建立 scope**。这会带来两个直接后果：

1. 工具内部如果再发 HTTP、再打子 span，当前上下文里没有活跃的 `tool.execute`
2. 即使 `tool.execute` 自己创建成功，也很容易因为父上下文已经断裂而不挂在你正在看的那条 `agent.request` 下

这就是“测试发现完全看不到 `tool.execute` 在记录工作”的第二根因。

### 2.4 `TracingOkHttpInterceptor` 不是 HTTP 子 span，而是给当前 span 补属性

当前拦截器的行为是：

- 读取 `Span.current()`
- 在当前 span 上补 `http.duration_ms`、`url.full`、`http.response.status_code` 等属性
- 不单独创建 HTTP span

所以当前系统并不存在文档里写的“HTTP child span via interceptor”这一层。  
如果父 span 没 current 成功，HTTP 诊断也会一起失效。

---

## 三、重构目标

新的 trace 方案必须同时满足下面四个目标：

1. **一棵树**
   一次 TEXT 请求内所有业务 span 都属于同一个 `agent.request` trace。
2. **Context 可解释**
   能直接看出是哪些 Provider 参与了拼接，哪些进入模型，哪些被跳过。
3. **Tool 可追踪**
   能直接看出工具有没有执行、执行前是否被安全否决、调用了哪个 dispatcher/manager、结果是否写回 memory。
4. **默认不再输出巨大 blob**
   默认只保留分段后的 provider/message 级内容；完整大字符串仅在 `FULL_DEBUG` 下作为附加能力保留。

---

## 四、建议的新 Span 树

建议把 TEXT 主链路统一为下面这棵树：

```text
agent.request
├── agent.runtime
│   ├── intent.route
│   └── toolgroup.select
├── agent.loop
│   ├── context.prepare
│   │   ├── context.provider.RuntimeContextProvider
│   │   ├── context.provider.PersonaContextProvider
│   │   ├── context.provider.PromptContextProvider
│   │   ├── context.provider.UserInputContextProvider
│   │   ├── context.provider.IntentContextProvider
│   │   ├── context.provider.ToolGroupContextProvider
│   │   ├── context.provider.LongTermMemoryContextProvider
│   │   └── context.provider.CallerExtraContextProvider
│   ├── agent.iteration[0]
│   │   ├── context.assemble
│   │   │   ├── context.provider.SessionMemoryContextProvider
│   │   │   ├── context.provider.VehicleStateContextProvider
│   │   │   ├── context.provider.TimeContextProvider
│   │   │   ├── context.fragment.prompt
│   │   │   ├── context.fragment.long_term_memory
│   │   │   ├── context.fragment.caller_extra
│   │   │   ├── context.message.session_memory[*]
│   │   │   ├── context.message.current_user
│   │   │   └── context.toolset
│   │   ├── gen_ai.chat
│   │   └── tool.execute[*]
│   │       ├── tool.safety_check
│   │       ├── tool.dispatch
│   │       └── tool.result_writeback
│   ├── agent.iteration[1]
│   │   └── ...
│   ├── memory.extract
│   └── memory.compress
└── response.dispatch
```

这个结构的关键点是：

- `agent.loop` 不再自己找 `GlobalOpenTelemetry`，而是必须从当前 `TraceSession` 派生
- 每轮迭代显式建立 `agent.iteration[n]`
- `context.prepare` 只看请求级静态 Provider
- `context.assemble` 只看本轮动态 Provider 和“最终进入模型的消息片段”
- `tool.execute` 下至少拆出 `safety_check`、`dispatch`、`result_writeback`

---

## 五、Context 的新观测模型

### 5.1 总原则

Context 观测拆成两层，不再混为一谈：

1. **Provider 执行层**
   谁被执行了、执行是否成功、产出了多少 contribution、耗时多少。
2. **模型输入层**
   Provider 的产物最终有没有进入模型；如果进入，是落在 `SystemMessage`、`Context Data UserMessage`、`SessionMemory 消息序列` 还是 `Current UserMessage`。

也就是说，后续 trace 里必须同时能回答两种问题：

- “这个 Provider 跑没跑？”
- “这个 Provider 的内容到底有没有进模型？”

### 5.2 Provider 到消息槽位的映射

根据当前 `ContextOrchestrator.defaultForText()` 和 `ContextMessageAssembler.assemble()`，真实的 Provider 语义如下：

| Provider | 生命周期 | 当前贡献类型 | 当前是否模型可见 | 新方案中的可视化方式 |
|---|---|---|---|---|
| `RuntimeContextProvider` | `REQUEST_STATIC` | `TextContextContribution` | 否，`POLICY_ONLY` | 保留 `context.provider.RuntimeContextProvider`，标记 `included_in_model=false` |
| `PersonaContextProvider` | `REQUEST_STATIC` | `TextContextContribution` | 否，`POLICY_ONLY` | 同上，只做策略/诊断 span |
| `PromptContextProvider` | `REQUEST_STATIC` | `TextContextContribution` | 是，进入 `SYSTEM` | `context.provider.*` + `context.fragment.prompt` |
| `UserInputContextProvider` | `REQUEST_STATIC` | `MessageContextContribution` | 是，`CURRENT_USER` | `context.provider.*` + `context.message.current_user` |
| `IntentContextProvider` | `REQUEST_STATIC` | `TextContextContribution` | 否，`POLICY_ONLY` | 只做 provider span |
| `ToolGroupContextProvider` | `REQUEST_STATIC` | `ToolContextContribution` | 是，工具规格 | `context.provider.*` + `context.toolset` |
| `LongTermMemoryContextProvider` | `REQUEST_STATIC` | `TextContextContribution` | 是，进入 `CONTEXT_DATA` | `context.provider.*` + `context.fragment.long_term_memory` |
| `CallerExtraContextProvider` | `REQUEST_STATIC` | `TextContextContribution` | 是，进入 `CONTEXT_DATA` | `context.provider.*` + `context.fragment.caller_extra` |
| `SessionMemoryContextProvider` | `ITERATION_DYNAMIC` | `MessageContextContribution` | 是，进入会话历史 | `context.provider.*` + `context.message.session_memory[*]` |
| `VehicleStateContextProvider` | `ITERATION_DYNAMIC` | `TextContextContribution` | 视工具组而定 | `context.provider.*`，若进入模型再补 `context.fragment.vehicle_state` |
| `TimeContextProvider` | `ITERATION_DYNAMIC` | `TextContextContribution` | 是，进入 `CONTEXT_DATA` | `context.provider.*` + `context.fragment.time` |

### 5.3 新增 span 类型定义

建议新增四类 context span：

1. `context.provider.<ProviderName>`
   表示一次 Provider 执行。
2. `context.fragment.<sourceKey>`
   表示某个 `TextContextContribution` 最终被拼进模型消息。
3. `context.message.<messageSource>`
   表示某个 `MessageContextContribution` 最终形成了一条或一组消息。
4. `context.toolset`
   表示本轮模型调用可见的 `ToolSpecification` 集合。

### 5.4 每类 span 应记录的属性

#### `context.provider.*`

- `provider.name`
- `provider.lifecycle`
- `provider.required`
- `provider.status`
- `provider.visibility`
- `provider.trust_level`
- `provider.contribution_count`
- `provider.model_visible_count`
- `provider.error_code`
- `provider.error_reason`
- `provider.duration_ms`

#### `context.fragment.*`

- `source_key`
- `provider.name`
- `target_area`
- `message.slot`
- `assembled.iteration`
- `assembled.index`
- `content.chars`
- `content.preview`
- `content.sha1`
- `content.capture_mode`

#### `context.message.*`

- `source_key`
- `provider.name`
- `message.role`
- `message.index`
- `message.count`
- `content.chars`
- `content.preview`
- `content.sha1`

#### `context.toolset`

- `selection_mode`
- `selected_group_count`
- `selected_tool_count`
- `tool.names`
- `selection_reason`

### 5.5 取消“大字符串总倾倒”

默认模式下建议移除或停用下面两类字段：

- `context.assembled_messages`
- `gen_ai.request.messages`

原因不是“不能看内容”，而是这两种记录方式的颗粒度完全不适合排障：

- 一旦 context 很长，Phoenix 上就是一整坨
- 你只能看到“最终拼完长什么样”，却看不到“是谁拼进去的”
- 很难定位某个 Provider 到底没有执行、执行了没入模、还是入模后被后续顺序覆盖

新的默认策略应为：

- `REDACTED`：按 provider/message span 单独记录预览和 hash
- `FULL_DEBUG`：在上面基础上，允许附加完整内容
- `OFF`：仅保留结构性指标

---

## 六、Tool Trace 的新方案

### 6.1 先修正当前缺口

`tool.execute` 先要解决两个硬问题：

1. 它必须从 `TraceSession` 当前树上派生，而不是依赖已经断裂的 `Context.current()`
2. 在 TEXT 主链路里，`toolSpan` 必须 `makeCurrent()`，否则工具内部所有子操作都挂不上来

### 6.2 建议的工具 span 分层

每个工具调用建议固定拆成下面三段：

```text
tool.execute
├── tool.safety_check
├── tool.dispatch
└── tool.result_writeback
```

含义如下：

- `tool.safety_check`
  记录逐个 `SafetyGuard` 的判断和 veto 原因
- `tool.dispatch`
  记录 `ToolRegistry.dispatch()` 到具体 `ToolDispatcher.dispatch()` 的真实执行阶段
- `tool.result_writeback`
  记录 `ToolExecutionResultMessage` 写回 `ChatMemory` 和 `loopCtx.addToolResult()` 的阶段

### 6.3 `tool.execute` 应记录的关键属性

- `tool.name`
- `tool.call_id`
- `agent.iteration`
- `tool.arguments.preview`
- `tool.arguments.sha1`
- `tool.dispatcher.class`
- `tool.target.class`
- `tool.target.method`
- `tool.success`
- `tool.safety_veto`
- `tool.safety_veto_reason`
- `tool.output.preview`
- `tool.output.sha1`
- `error.type`
- `error.message`

### 6.4 为什么这样拆

这样拆开以后，trace 可以直接回答下面几类问题：

- LLM 有没有发出 tool call
- 是不是被安全规则拦住了
- 有没有真正进入 `ToolRegistry.dispatch()`
- 真正执行的是哪个 manager / 哪个方法
- 结果有没有回填进记忆，供下一轮 LLM 使用

这比当前只在外层包一个 `tool.execute` 再塞一段 result 文本，排障价值高很多。

---

## 七、建议的代码改造点

### 7.1 Trace 上下文统一

必须做：

1. `AgentRuntime.execute()` 不再直接使用 `GlobalOpenTelemetry`
2. `agent.loop`、`agent.iteration` 都通过 `TraceSession.startChildSpan(...)` 创建
3. `ContextOrchestrator.prepare()`、`assemble()` 不再盲信 `Context.current()`，优先显式传入 parent context

建议新增：

- `TraceSession.startLoopSpan(Context parent)`
- `TraceSession.startIterationSpan(int iteration, Context parent)`

### 7.2 ContextTraceRecorder 重做

当前 `ContextTraceRecorder` 需要从“事件记录器”升级为“结构化 span 记录器”：

- 保留聚合指标
- 删除默认大字符串输出
- 新增 provider span 创建 API
- 新增 fragment/message span 创建 API

建议接口形态：

```java
Span startProviderSpan(String providerName, Context parent);
void finishProviderSpan(Span span, ContextProviderResult result, ...);

Span startFragmentSpan(ContextContribution contribution, int iteration, Context parent);
void finishFragmentSpan(Span span, ...);
```

### 7.3 TextAgentLoopOrchestrator 修正

必须做：

1. `toolSpan.makeCurrent()`
2. `tool.execute` 下补 `tool.safety_check`、`tool.dispatch`、`tool.result_writeback`
3. `gen_ai.chat`、`tool.execute`、`memory.extract` 全部挂到显式的 `agent.iteration[n]` 下

### 7.4 ToolDispatcher 增加可观测点

建议在 `ToolRegistry.dispatch()` / `ToolDispatcher.dispatch()` 之间增加细粒度 trace 采样点，至少把下面信息打出来：

- 选中的 dispatcher
- 目标类和方法
- 参数解析成功/失败
- 反射调用耗时
- 结果序列化耗时

否则就算外层有 `tool.execute`，你仍然只能看到“调用了工具”，看不到“工具内部到底卡在哪里”。

---

## 八、推荐实施顺序

### Phase 1：先修树，不加新花样

目标：

- 让 `agent.request -> agent.loop -> context/llm/tool/memory/response` 真正挂成一棵树
- 修复 TEXT 主链路 `toolSpan.makeCurrent()`

验收：

- Phoenix 中一次 TEXT 请求可以看到同一 traceId 下的 `tool.execute`

### Phase 2：Context Provider Span 化

目标：

- `context.provider.output` event 改成独立 provider span
- 移除默认 `context.assembled_messages` 大字符串

验收：

- 能直接看出哪几个 Provider 执行了、哪些进入模型、哪些没进入

### Phase 3：消息片段可视化

目标：

- 为最终进入模型的 `SYSTEM`、`CONTEXT_DATA`、`SESSION_MEMORY`、`CURRENT_USER` 建立 fragment/message span

验收：

- 不展开大文本，也能顺着 span 树看清本轮 prompt 的拼接来源

### Phase 4：Tool 内部分层

目标：

- 增加 `tool.safety_check`、`tool.dispatch`、`tool.result_writeback`

验收：

- 能区分“LLM 发出了 tool call”和“工具真的执行到了哪一步”

---

## 九、这份方案要解决的核心体验问题

新的 trace 不是为了“多打点”，而是为了让 Phoenix 直接回答下面三个问题：

1. **这一轮 prompt 是谁拼出来的？**
   不是一大坨 `context`，而是逐个 Provider、逐个消息片段可见。
2. **为什么某个上下文没生效？**
   是 Provider 没跑、跑了失败、跑了但 `POLICY_ONLY`、还是进模型后被预算裁掉。
3. **工具到底干没干活？**
   是 LLM 根本没发 tool call、被 safety veto、dispatch 失败、还是执行成功但结果没写回。

如果做不到这三点，trace 再多也只是日志换皮，不是可观测性。

---

## 十、当前文档结论

当前代码离这个目标还有明显距离，尤其是两项阻塞问题必须先修：

1. **Trace 上下文来源不统一**
2. **TEXT 主链路的 `tool.execute` 没有 current scope**

在这两个问题修正前，继续围绕当前 Phoenix 结果讨论“链路是否完整”，参考价值都很有限。
