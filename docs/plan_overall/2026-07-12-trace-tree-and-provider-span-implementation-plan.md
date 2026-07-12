# Trace 树修复与 Provider 级可观测性实施计划

**日期：** 2026-07-12  
**项目阶段：** Demo  
**目标：** 把 TEXT 主链路的 Trace 从“局部打点”修成一棵真实可追踪的请求树，并把 Context 的观测粒度从“大块字符串”提升到 Provider / 消息片段 / 工具执行阶段级别。

---

## 一、总目标

本计划要解决两个当前已经确认的核心问题：

1. `agent.request`、`agent.loop`、`context.prepare`、`context.assemble`、`gen_ai.chat`、`tool.execute` 目前不保证属于同一棵 Trace 树。
2. Phoenix 中很难直接看出：
   - 本轮 Context 到底拼进了哪些 Provider 内容；
   - 哪些 Provider 只是参与策略判断、没有进入模型；
   - `tool.execute` 是否真正执行、执行到哪一步、结果是否写回对话历史。

本计划完成后，针对一次 TEXT 请求，Phoenix 中必须能直接回答：

1. 这次请求的 root span 是什么，整个链路是否挂在同一 traceId 下。
2. 请求级静态 Provider 和迭代级动态 Provider 分别执行了哪些。
3. 每个 Provider 的内容是否进入模型，进入了哪一类消息槽位。
4. 每轮模型调用前最终暴露给模型的消息结构和工具集合是什么。
5. 工具调用是否真的执行，失败点在安全检查、dispatch 还是写回阶段。

---

## 二、固定边界

### 2.1 本轮要做

1. 只处理 TEXT 主链路的 Trace 修复与可观测性增强。
2. 以 `TraceSession` 为唯一业务 Trace 根，修正 TEXT 请求 span 父子关系。
3. 把 Context 观测从 event + 大字符串，提升为分阶段、分 Provider、分消息槽位的结构化记录。
4. 让 `tool.execute` 至少能稳定出现在同一条请求链路里，并进一步拆出内部关键阶段。
5. 每个 phase 结束后都要有对应测试和 Phoenix 侧验证口径。

### 2.2 本轮不做

1. 不修改 AIDL 接口，不改外部 App 调用方式。
2. 不重做 Prompt 模板体系，不改 Prompt 内容策略。
3. 不改 Memory 算法本身，只允许补充 Trace 记录。
4. 不改 ToolGroupSelector 选择规则，不借这次工作顺手做 ToolGroup 架构调整。
5. 不引入新的第三方观测框架，不替换 OpenTelemetry / Phoenix。
6. 不扩展 IMAGE / VOICE 主链路的 Trace 范围；VOICE 只允许在文档中注明后续对齐点，不纳入本次实施范围。
7. 不在本计划中引入新的脱敏、权限、采样、动态开关策略。

### 2.3 固定设计约束

1. Demo 阶段优先保证“能看清结构和执行过程”，不是先做配置化和抽象化。
2. 每个 phase 必须有明确完成边界，禁止把下一阶段目标提前混进当前阶段。
3. 若某个 phase 暴露出架构断点，先修断点，不允许带着断裂的 Trace 树继续做更细粒度展示。

---

## 三、实施总览

本次改造分为 3 个 phase：

1. **Phase 1：修复 Trace 树主干**
   先把 `agent.request -> agent.loop -> context/llm/tool/memory/response` 真正挂成一棵树，并恢复 `tool.execute` 的基本可见性。
2. **Phase 2：实现 Context Provider 级可观测性**
   把 Context 从“整坨 assembled content”改为“Provider 执行 + 模型输入片段”双层可观测。
3. **Phase 3：完善 Tool 内部阶段 Trace 与最终验收**
   把 `tool.execute` 细化成内部阶段，补齐端到端验证，形成可稳定排障的最终结构。

执行原则：

1. 前一阶段未通过，不进入下一阶段。
2. 任何阶段发现需要扩大边界时，先回到本计划评估，不允许边做边失控。

---

## 四、Phase 1：修复 Trace 树主干

### 4.1 阶段目标

让 TEXT 请求至少具备稳定、正确的骨架层级：

```text
agent.request
  -> agent.loop
       -> context.prepare
       -> agent.iteration[n]
            -> context.assemble
            -> gen_ai.chat
            -> tool.execute
       -> memory.extract / memory.compress
  -> response.dispatch
```

本阶段的核心不是展示所有细节，而是先确保这些 span **真的在同一棵树里**，并且多轮 `tool call -> 再次模型调用` 不会全部混堆在 `agent.loop` 平级上。

### 4.2 本阶段要改的核心文件

- `app/src/main/java/com/hirain/aiagent/trace/TraceSession.java`
- `app/src/main/java/com/hirain/aiagent/runtime/AgentRuntime.java`
- `app/src/main/java/com/hirain/aiagent/core/TextAgentLoopOrchestrator.java`
- `app/src/main/java/com/hirain/aiagent/context/ContextOrchestrator.java`
- `app/src/main/java/com/hirain/aiagent/trace/AgentTraceRecorder.java`
- `app/src/main/java/com/hirain/aiagent/AIAgentService.kt`
- `app/src/main/java/com/hirain/aiagent/trace/TraceResponseDispatcher.java`
- 相关 TEXT Trace 测试文件

### 4.3 具体实施项

#### Task 1.1：统一 `agent.loop` 的创建来源

1. 禁止 `AgentRuntime.execute()` 再直接使用 `GlobalOpenTelemetry.get().getTracer(...)`。
2. `agent.loop` 必须由当前请求的 `TraceSession` 派生。
3. 若当前请求无有效 `TraceSession`，才允许 no-op 降级，但不能混出第二套 Trace 根。

#### Task 1.2：引入显式 `agent.iteration[n]` 容器

1. 每次进入模型调用循环时，都要有一个显式的 `agent.iteration[n]` span 作为本轮容器。
2. `context.assemble`、`gen_ai.chat`、`tool.execute` 必须挂到对应的 `agent.iteration[n]` 之下，而不是全部直接平铺到 `agent.loop`。
3. 多轮工具调用场景下，必须能从 trace 树上直接分辨第几轮调用产生了哪次 assemble、哪次 LLM 调用、哪次工具执行。

#### Task 1.3：统一显式 parent context 传递

1. `context.prepare`、`context.assemble`、`gen_ai.chat`、`tool.execute`、`memory.extract`、`memory.compress` 全部改为从显式 parent context 派生。
2. 不能继续依赖“此时 `Context.current()` 正好正确”这种隐式假设。
3. `agent.loop` 进入 current scope 后，后续子 span 的 parent 仍要尽量显式传入，减少被无关 scope 覆盖的风险。

#### Task 1.4：恢复 TEXT 主链路 `tool.execute` 的 current scope

1. `TextAgentLoopOrchestrator.execute()` 中的 `toolSpan` 必须建立 `makeCurrent()` scope。
2. 工具执行期间发生的安全检查、dispatcher 调用、工具内部 HTTP 或后续子 span，必须有机会挂到当前 `tool.execute` 之下。
3. 工具执行异常路径同样必须正确结束 span，并保留错误状态。

#### Task 1.5：把 `response.dispatch` 接回同一棵请求树

1. 既然本阶段目标里保留 `response.dispatch`，就必须把真实 TEXT 发送路径纳入实施范围。
2. `AIAgentService.kt` 中直接 `notifyAIAgentListeners(...)` 的 TEXT 成功、失败、超时、取消分支，要统一走 `TraceResponseDispatcher` 或等价的同树记录逻辑。
3. 不能继续让 `response.dispatch` 只停留在工具类存在、但 TEXT 主路径实际上没接入的状态。

#### Task 1.6：校正当前文档和测试基线

1. 更新相关测试，使其验证“同一 traceId 下存在 `agent.request`、`agent.loop`、`context.prepare`、`context.assemble`、`gen_ai.chat`、`tool.execute`”。
2. 不再把“拦截器自动创建 HTTP 子 span”作为本阶段预期，因为当前实现并不是这样。
3. 若本阶段已经接入 `TraceResponseDispatcher`，测试基线要把 `response.dispatch` 一并纳入。

### 4.4 本阶段执行边界

#### 允许做

1. 为了修正父子关系，增补 `TraceSession` 的便捷 API。
2. 为了稳定 parent 传递，调整 `ContextOrchestrator` / `TextAgentLoopOrchestrator` / `AgentRuntime` 的 trace 调用方式。
3. 为了让 `response.dispatch` 真正落地，允许调整 `AIAgentService` 的 TEXT 响应分发接线。
4. 为了校验树结构，增加针对 TEXT 主链路的 JVM 测试。

#### 不允许做

1. 不在本阶段引入每个 Provider 单独 span。
2. 不在本阶段拆 `tool.execute` 内部阶段。
3. 不在本阶段删除现有大字符串记录逻辑。
4. 不在本阶段顺手重构 `TracingOkHttpInterceptor`。

### 4.5 本阶段验收标准

#### 代码验收

1. `AgentRuntime.execute()` 不再直接依赖 `GlobalOpenTelemetry` 作为 TEXT 主链路 loop span 来源。
2. `agent.iteration[n]` 已经成为 iteration 级容器，`context.assemble`、`gen_ai.chat`、`tool.execute` 不再直接平铺在 `agent.loop` 下。
3. `TextAgentLoopOrchestrator` 中 `tool.execute` 具备 current scope。
4. TEXT 主链路的 `response.dispatch` 已真正接入，而不是只存在于未使用的工具类中。

#### 测试验收

建议至少覆盖：

1. 一次普通 TEXT 请求无工具调用时，存在完整主干 span。
2. 一次含工具调用的 TEXT 请求中，`tool.execute` 与 `gen_ai.chat` 位于同一根请求树，且能区分所属 iteration。
3. 工具异常路径下，`tool.execute` 仍然结束并写入错误状态。
4. TEXT 成功、失败或取消至少各有一条路径能验证 `response.dispatch` 的接线存在。

#### Phoenix 验收

1. 同一请求只出现一棵业务主树，不再出现“root 在一条树上，loop/child 跑到另一条树上”的现象。
2. 对含工具调用的请求，必须能实际看到 `tool.execute`。
3. 对一次完整 TEXT 请求，必须能实际看到 `response.dispatch`，且它属于同一请求树。

---

## 五、Phase 2：实现 Context Provider 级可观测性

### 5.1 阶段目标

把 Context Trace 从“看一大坨 assembled content”升级为两层结构：

1. **Provider 执行层**
   看谁执行了、成功失败、耗时多少、产出了哪些 contribution。
2. **模型输入层**
   看哪些 contribution 最终真的进入模型，进入的是 `SYSTEM`、`CONTEXT_DATA`、`SESSION_MEMORY` 还是 `CURRENT_USER`。

### 5.2 本阶段要改的核心文件

- `app/src/main/java/com/hirain/aiagent/context/ContextTraceRecorder.java`
- `app/src/main/java/com/hirain/aiagent/context/ContextOrchestrator.java`
- `app/src/main/java/com/hirain/aiagent/context/ContextMessageAssembler.java`
- `app/src/main/java/com/hirain/aiagent/trace/TraceAttributeKeys.java`
- `app/src/main/java/com/hirain/aiagent/trace/TraceMessageFormatter.java`
- Context / Trace 相关测试文件

### 5.3 具体实施项

#### Task 2.1：把 Provider event 升级为 Provider span

1. `ContextTraceRecorder` 新增 Provider span 创建与结束能力。
2. 每次执行一个 Provider，创建一个 `context.provider.<ProviderName>` span。
3. Provider span 至少记录：
   - `provider.name`
   - `provider.lifecycle`
   - `provider.required`
   - `provider.status`
   - `provider.duration_ms`
   - `provider.contribution_count`
   - `provider.error_code`
   - `provider.error_reason`

#### Task 2.2：建立“是否进模型”的结构化记录

1. 对 `TextContextContribution`，按最终落位建立 `context.fragment.<sourceKey>` 级记录。
2. 对 `MessageContextContribution`，按最终消息序列建立 `context.message.<messageSource>` 级记录。
3. 对 `ToolContextContribution`，建立 `context.toolset` 级记录。
4. 每条记录都必须能区分：
   - 来源 Provider
   - sourceKey
   - 是否 `MODEL_VISIBLE`
   - 最终是否进入模型
   - 进入了哪个消息槽位

#### Task 2.3：逐步压缩“大块 assembled 内容”的默认权重

1. 本阶段不要求立刻彻底删除旧字段，但要把新结构化记录变成主路径。
2. `context.assembled_messages`、`context.assembled_tool_specs` 不再作为主要排障入口。
3. 若保留旧字段，必须在文档和测试中明确：它们只是兼容/补充，不是最终主观测模型。

#### Task 2.4：建立内容去重规则

1. 必须明确“哪一层记录完整内容，哪一层只记录结构和摘要”，避免同一份 prompt/context 在多个 span 中重复倾倒。
2. 推荐规则：
   - `context.provider.*` 主要记录执行状态、结构化 contribution 摘要、是否入模，不默认承载完整长文本；
   - `context.fragment.*` / `context.message.*` 作为模型输入层，承载最终真正进入模型的内容；
   - legacy `context.assembled_messages`、`context.assembled_tool_specs` 若保留，只作为兼容补充，不再与新 span 同时承担主内容展示职责。
3. 若某类内容确需双写，必须在文档中说明原因，并限制为短摘要 + 可比对标识，不能无条件整段复制。

#### Task 2.5：把请求级和迭代级 context 分层清楚

1. `context.prepare` 只负责 request-static Provider。
2. `context.assemble` 只负责 iteration-dynamic Provider 和最终装配结果。
3. 不能把“Provider 执行信息”和“最终入模消息信息”继续糊在同一批 event 里。

### 5.4 本阶段执行边界

#### 允许做

1. 调整 `ContextTraceRecorder` 的职责，从 event 写入器升级为 span 记录器。
2. 新增用于格式化 contribution 内容的轻量辅助逻辑。
3. 为 provider 层与模型输入层补充“去重规则”相关测试或断言。
4. 针对 Context provider / fragment / message 结构增加专门测试。

#### 不允许做

1. 不改 Provider 本身的业务语义，不借机重写 `PromptContextProvider`、`ToolGroupContextProvider`、`SessionMemoryContextProvider` 的功能边界。
2. 不在本阶段改变 Context 装配顺序。
3. 不扩展到 VOICE / IMAGE。
4. 不在本阶段下钻到工具 dispatcher 内部。

### 5.5 本阶段验收标准

#### 代码验收

1. `context.prepare` 下能看到 request-static Provider 的独立 span。
2. `context.assemble` 下能看到 iteration-dynamic Provider 的独立 span。
3. 能清楚区分某个 Provider “执行了但没进模型”和“执行了且进了模型”。
4. 同一份 context 内容不会在 provider span、fragment/message span、legacy assembled 字段之间无规则地重复倾倒。

#### 测试验收

建议至少覆盖：

1. `PromptContextProvider` 最终落到 `SYSTEM`。
2. `LongTermMemoryContextProvider` / `TimeContextProvider` 最终落到 `CONTEXT_DATA`。
3. `SessionMemoryContextProvider` 最终落到会话历史消息序列。
4. `RuntimeContextProvider` / `IntentContextProvider` / `PersonaContextProvider` 作为 `POLICY_ONLY` 时不会被误记为入模内容。
5. 至少有一类长文本 contribution 验证“provider 层不重复承载完整正文、模型输入层才承载主内容”。

#### Phoenix 验收

1. 打开一次 TEXT 请求，不看大字符串也能顺着 span 看出 prompt 是谁拼出来的。
2. 能直接判断某个 Provider 是“没执行”、“执行失败”、“执行成功但未入模”还是“执行成功且入模”。
3. 不会因为 provider 层和模型输入层同时倾倒完整内容，导致 Phoenix 视图反而更乱。

---

## 六、Phase 3：完善 Tool 内部阶段 Trace 与最终验收

### 6.1 阶段目标

在 Phase 1 已经恢复 `tool.execute` 可见并建立 `agent.iteration[n]` 容器、Phase 2 已经让 Context 可解释的前提下，再把工具执行细化为可以排障的内部阶段。

目标结构：

```text
tool.execute
  -> tool.safety_check
  -> tool.dispatch
  -> tool.result_writeback
```

### 6.2 本阶段要改的核心文件

- `app/src/main/java/com/hirain/aiagent/core/TextAgentLoopOrchestrator.java`
- `app/src/main/java/com/hirain/aiagent/ai/langchain4j/tool/ToolRegistry.java`
- `app/src/main/java/com/hirain/aiagent/ai/langchain4j/tool/ToolDispatcher.java`
- `app/src/main/java/com/hirain/aiagent/trace/AgentTraceRecorder.java`
- Tool / Trace 相关测试文件

### 6.3 具体实施项

#### Task 3.1：拆出 `tool.safety_check`

1. 在每个工具执行前，为安全检查建立独立 span。
2. 记录逐个 `SafetyGuard` 的判断结果和 veto 原因。
3. 若工具被 veto，必须在 Trace 中看出“工具调用存在，但被安全层拦截”，而不是只看到工具没执行。

#### Task 3.2：拆出 `tool.dispatch`

1. `ToolRegistry.dispatch()` 到 `ToolDispatcher.dispatch()` 的真实执行阶段单独成 span。
2. 记录至少以下信息：
   - toolName
   - dispatcher class
   - target class
   - target method
   - 参数解析是否成功
   - 反射调用耗时

#### Task 3.3：拆出 `tool.result_writeback`

1. `ToolExecutionResultMessage` 写回 `ChatMemory` 的阶段单独成 span。
2. `loopCtx.addToolResult(...)` 的记录也纳入该阶段。
3. 要能明确看出：
   - 工具执行成功但未写回；
   - 工具执行失败但错误已写回；
   - 取消流程下写回了什么占位结果。

#### Task 3.4：形成最终端到端验收测试

1. 一次普通问答请求。
2. 一次含单工具调用请求。
3. 一次多工具调用请求。
4. 一次工具 veto 请求。
5. 一次工具异常请求。
6. 一次工具执行后进入第二轮 `gen_ai.chat` 的请求。

每类场景都必须定义预期 span 树。

### 6.4 本阶段执行边界

#### 允许做

1. 为 `ToolRegistry` / `ToolDispatcher` 补充 Trace 观测点。
2. 为工具异常、取消、veto 分支补测试。
3. 若确有必要，可新增少量 Trace 命名常量和属性常量。

#### 不允许做

1. 不借机改真实工具业务逻辑。
2. 不修改 ToolRegistry 对外行为语义，不做工具框架重构。
3. 不引入额外的异步执行模型。
4. 不再扩大到 HTTP 拦截器重构；HTTP 仍只作为当前活跃 span 的补充属性，不在本阶段单独升级为独立 HTTP span 体系。

### 6.5 本阶段验收标准

#### 代码验收

1. 每个 `tool.execute` 下能区分 `safety_check`、`dispatch`、`result_writeback`。
2. `tool.execute` 的成功、异常、veto、取消都能在 Trace 中直接区分。

#### 测试验收

建议至少覆盖：

1. veto 场景下存在 `tool.execute` 和 `tool.safety_check`，但没有真实 dispatch 成功结果。
2. dispatcher 抛异常时，`tool.dispatch` 标记错误，`tool.execute` 正确结束。
3. 工具结果写回后，下一轮模型调用确实能看到 ToolExchange。

#### Phoenix 验收

1. 对一次真实工具请求，能直接看到“有没有调用工具、卡在哪一步、结果是否回写”。
2. 对一次多轮工具请求，能看出 `agent.iteration[n]` 之下的 `gen_ai.chat -> tool.execute -> gen_ai.chat` 或跨 iteration 的迭代关系，而不是所有 span 平铺混在一起。

---

## 七、推荐执行顺序与阶段切换规则

### 7.1 执行顺序

1. 先做 Phase 1，确保树结构成立。
2. 再做 Phase 2，把 Context 的可解释性做出来。
3. 最后做 Phase 3，把 Tool 的内部诊断能力补齐。

### 7.2 阶段切换规则

1. Phase 1 若未能稳定看到同一树下的 `tool.execute`，禁止进入 Phase 2。
2. Phase 2 若仍只能依赖大字符串排查 Context，禁止进入 Phase 3。
3. Phase 3 结束后，必须形成一个可复用的验收口径文档或测试清单，后续任何 Trace 变更都按这套口径回归。

---

## 八、阶段性风险

### 8.1 Phase 1 风险

1. 当前 TraceSession 和运行时 current scope 的混用可能导致修复时出现重复 span 或空 span。
2. 若 parent context 传递处理不稳，表面上看似“有 span”，实际仍可能挂错父节点。

### 8.2 Phase 2 风险

1. Provider span 数量上升后，若命名和属性不统一，会让 Phoenix 更乱而不是更清晰。
2. 若“Provider 执行层”和“模型输入层”边界没分干净，仍会回到现在这种难排查状态。

### 8.3 Phase 3 风险

1. 工具执行链路里如果直接把 Trace 写进太多底层分支，容易引入样板代码膨胀。
2. 若工具异常和取消路径没有同步补全，最终依旧会出现“成功路径很清楚，失败路径一团糟”。

---

## 九、最终完成定义

当且仅当满足以下条件，才算这份计划完成：

1. TEXT 主链路 Trace 在 Phoenix 中稳定表现为单一请求树。
2. Context 已经可以按 Provider 和消息槽位解释，不再依赖单个巨大 content 字段才能排查问题。
3. `tool.execute` 不只是“存在”，而是能清楚区分安全检查、真实执行和结果写回。
4. 对普通请求、工具请求、异常请求、取消请求都有可复用的测试和 Phoenix 验收口径。

---

## 十、本计划与其他模块的关系

1. 本计划服务于 Trace 可观测性，不等于重写 Context 架构。
2. 本计划只修正 Trace 如何表达 Context / Tool 的真实执行过程，不改变 Context 模块对模型输入的控制权，也不改变 Tool 模块对真实工具执行的实现所有权。
3. 若后续需要扩展到 VOICE / IMAGE，应以本计划完成后的 TEXT 主链路为模板单独开新 phase，而不是在本轮混做。
