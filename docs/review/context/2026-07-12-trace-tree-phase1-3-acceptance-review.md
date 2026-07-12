# Trace 树与 Provider Span 方案 Phase 1-3 验收审查

> 本文基于正确计划文档  
> `D:\code\android\AndroidStudioProjects\AIAgent\docs\plan_overall\2026-07-12-trace-tree-and-provider-span-implementation-plan.md`  
> 进行审查。  
> 它应视为对前一份误用旧计划文档的审查结果的替代。

## 结论

当前实现**不建议通过 Phase 1-3 验收**。

原因不是主干完全没做，而是：

1. 主干树、Provider span、Tool 三阶段的主体代码已经接上了。
2. 但仍存在若干关键缺口，会直接导致 Phoenix 上看到的结论失真，尤其是：
   - 取消分支缺少真实 `response.dispatch`
   - `tool.execute` 异常路径会被错误记成成功
   - Provider / 模型输入层的“是否真的入模”判断仍不准确
   - 新旧 Context 记录方式仍在重复倾倒内容

---

## 主要问题

### P0：`tool.execute` 异常路径被错误标记为成功，Phase 3 的核心判定语义失真

计划要求：

- `tool.execute` 的成功、异常、veto、取消都能在 Trace 中直接区分
  - 见计划 `6.5 代码验收`
- dispatcher 异常时，`tool.dispatch` 标记错误，`tool.execute` 正确结束
  - 见计划 `6.5 测试验收`

当前代码中：

- [AgentTraceRecorder.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/trace/AgentTraceRecorder.java:183) 到 [AgentTraceRecorder.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/trace/AgentTraceRecorder.java:192) 的 `finishTool(...)` 用 `verdict != null && verdict.isVetoed()` 来推导 `tool.success`
- 也就是说，只要不是 veto，就会写 `tool.success=true`
- [TextAgentLoopOrchestrator.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/core/TextAgentLoopOrchestrator.java:291) 到 [TextAgentLoopOrchestrator.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/core/TextAgentLoopOrchestrator.java:297) 的异常分支，在工具执行异常时调用的是：
  - `trace.finishTool(toolSpan, "error", null);`

这会造成：

1. `tool.dispatch` 可能显示 `dispatch_success=false`
2. 但父级 `tool.execute` 仍会显示 `tool.success=true`

这会直接误导排障人员，让 Phoenix 上看到“子阶段失败，但总工具调用成功”这种自相矛盾的状态。

现有测试没有拦住这个问题：

- [ToolPhaseTraceTest.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/test/java/com/hirain/aiagent/trace/ToolPhaseTraceTest.java:133) 到 [ToolPhaseTraceTest.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/test/java/com/hirain/aiagent/trace/ToolPhaseTraceTest.java:168) 只校验了 `tool.dispatch_success=false`
- 没有断言异常路径下 `tool.execute` 的 `tool.success` 或 span status

### P1：TEXT 取消分支仍绕过 `TraceResponseDispatcher`，`response.dispatch` 没有真正覆盖真实主链路

计划要求：

- TEXT 成功、失败、超时、取消分支要统一走 `TraceResponseDispatcher` 或等价同树逻辑
  - 见计划 `4.3 / Task 1.5`
- `response.dispatch` 必须真正接入 TEXT 主路径
  - 见计划 `4.5 代码验收`

当前代码中：

- 成功 / 超时 / 失败路径已经走了 `TraceResponseDispatcher`
  - 见 [AIAgentService.kt](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/AIAgentService.kt:612)
  - 见 [AIAgentService.kt](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/AIAgentService.kt:651)
  - 见 [AIAgentService.kt](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/AIAgentService.kt:662)
- 但取消分支仍然直接 `notifyAIAgentListeners(cancelledResponse)`
  - 见 [AIAgentService.kt](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/AIAgentService.kt:523) 到 [AIAgentService.kt](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/AIAgentService.kt:544)

这意味着：

1. Phase 1 里最强调的真实 TEXT 取消路径，仍没有 `response.dispatch`
2. Phoenix 上仍然无法保证“任何终态都能在同一请求树末端看到 response.dispatch”

测试也没有真正覆盖服务层取消接线：

- [AIAgentServiceTraceWiringTest.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/test/java/com/hirain/aiagent/trace/AIAgentServiceTraceWiringTest.java:24) 到 [AIAgentServiceTraceWiringTest.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/test/java/com/hirain/aiagent/trace/AIAgentServiceTraceWiringTest.java:87) 验证的是 `TraceResponseDispatcher` 工具类本身
- 不是 `AIAgentService.cancelAgentRequest(...)` 这条真实分支

### P1：Phase 2 的“是否真的进入模型”仍然记录不准，把“可入模”误当成“已入模”

计划要求：

- 要能区分：
  - 执行了但没进模型
  - 执行了且进了模型
  - 见计划 `5.5 代码验收`
- `fragment/message/toolset` 记录应反映“最终真的进入模型”的内容和槽位
  - 见计划 `5.3 / Task 2.2`

当前代码里：

- [ContextTraceRecorder.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextTraceRecorder.java:161) 到 [ContextTraceRecorder.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextTraceRecorder.java:164) 的 `provider.included_in_model` 只是检查“是否存在 `MODEL_VISIBLE` contribution”
- [ContextOrchestrator.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextOrchestrator.java:367) 到 [ContextOrchestrator.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextOrchestrator.java:379) 也是对所有 `MODEL_VISIBLE` contribution 直接记录 fragment/message/toolset span
- 但真正进入消息序列时，[ContextMessageAssembler.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextMessageAssembler.java:122) 到 [ContextMessageAssembler.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextMessageAssembler.java:133) 只会把非空 `CONTEXT_DATA` 文本拼进去

因此会出现这种失真场景：

1. 某个 Provider 产出了 `MODEL_VISIBLE` contribution
2. 但内容为空，最终没有进入任何实际 `ChatMessage`
3. Trace 里仍然会显示：
   - provider `included_in_model=true`
   - 甚至还能看到对应的 fragment/message span

这不满足 Phase 2 对“最终是否入模”的要求，当前更像是在记录“有资格入模”，不是“已经入模”。

### P1：新 Context 结构化观测仍未摆脱对 legacy 大字段的依赖，而且存在重复倾倒

计划要求：

- 结构化记录要成为主路径
  - 见计划 `5.3 / Task 2.3`
- 必须建立去重规则，不能让 provider 层、fragment/message 层、legacy 字段同时整段复制
  - 见计划 `5.3 / Task 2.4`

当前代码：

- [ContextOrchestrator.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextOrchestrator.java:359) 到 [ContextOrchestrator.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextOrchestrator.java:363) 仍然写：
  - `context.assembled_messages`
  - `context.assembled_tool_specs`
  - `context.message` legacy events
- 同时 [ContextOrchestrator.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextOrchestrator.java:365) 到 [ContextOrchestrator.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextOrchestrator.java:379) 又写 fragment / message / toolset span

更关键的是：

- [ContextTraceRecorder.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextTraceRecorder.java:248) 到 [ContextTraceRecorder.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextTraceRecorder.java:258) 的 `recordMessage(...)` 只记录 source/provider/count
- 并没有记录 `MessageContextContribution` 实际进入模型的正文

结果就是：

1. `SYSTEM` / `CONTEXT_DATA` 文本可以从 fragment span 看
2. 但 `SESSION_MEMORY` / `CURRENT_USER` 的具体内容在新 message span 里看不到
3. 仍然要回退到 legacy `context.assembled_messages` 或旧 event 才能知道模型到底看到了什么

这说明当前 Phase 2 的结构化 span 还不是自足的主排障入口，仍然依赖旧的大字符串兜底，不符合本轮计划目标。

### P2：Provider 异常耗时统计仍不可靠

计划要求：

- Provider span 要能反映执行耗时
  - 见计划 `5.3 / Task 2.1`

当前代码中：

- [ContextOrchestrator.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextOrchestrator.java:135) 到 [ContextOrchestrator.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextOrchestrator.java:150) 在 `prepare()` 的异常分支里，`provider.duration_ms` 使用的是整个 `prepare` 的起点 `startMs`
- [ContextOrchestrator.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextOrchestrator.java:281) 到 [ContextOrchestrator.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextOrchestrator.java:295) 在 `assemble()` 的异常分支里，`provider.duration_ms` 被直接写成 `0`

这会导致 Provider 异常时：

- 要么耗时虚高
- 要么耗时恒为 0

对于 Phoenix 侧的性能排障价值不够。

### P2：`tool.dispatch` 诊断信息仍未达到 Phase 3 计划要求

计划要求 `tool.dispatch` 至少能看出：

- dispatcher class
- target class
- target method
- 参数解析是否成功
- 反射调用耗时
  - 见计划 `6.3 / Task 3.2`

当前实现里：

- [ToolRegistry.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/ai/langchain4j/tool/ToolRegistry.java:138) 到 [ToolRegistry.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/ai/langchain4j/tool/ToolRegistry.java:140) 只暴露了一个拼好的 `dispatchTargetInfo`
- [ToolDispatcher.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/ai/langchain4j/tool/ToolDispatcher.java:106) 到 [ToolDispatcher.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/ai/langchain4j/tool/ToolDispatcher.java:109) 也只返回 `"TargetClass.methodName"`
- [AgentTraceRecorder.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/trace/AgentTraceRecorder.java:276) 到 [AgentTraceRecorder.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/trace/AgentTraceRecorder.java:284) 只记录了：
  - `tool.dispatch_success`
  - `tool.dispatch_target`
  - `tool.dispatch_duration_ms`

当前看不到：

1. dispatcher 本身是谁
2. 参数是在 JSON 解析阶段失败还是反射调用阶段失败
3. `target class` / `target method` 的独立字段

这说明 Phase 3 的 `tool.dispatch` 还没达到计划里定义的诊断粒度。

### P2：`tool.result_writeback` 在写回阶段抛异常时可能无法正确收口

当前代码：

- [TextAgentLoopOrchestrator.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/core/TextAgentLoopOrchestrator.java:280) 到 [TextAgentLoopOrchestrator.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/core/TextAgentLoopOrchestrator.java:290) 启动并结束 `tool.result_writeback`
- 但一旦 `chatMemory.add(...)` 或 `loopCtx.addToolResult(...)` 在 writeback 阶段抛异常，就会走到 [TextAgentLoopOrchestrator.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/core/TextAgentLoopOrchestrator.java:291) 到 [TextAgentLoopOrchestrator.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/core/TextAgentLoopOrchestrator.java:304)
- 这个异常分支没有对 `writebackSpan` 做任何兜底结束

所以 writeback 真正失败时，Trace 结构存在不完整风险。

---

## 高效率整改计划

前一版整改计划拆得过细，执行上会出现两个问题：

1. 下属会在很多小点之间频繁切换，改动上下文来回丢失。
2. 同一个代码区域会被重复打开多次，测试也会重复跑，效率低。

这轮更合理的做法，不是继续拆 8 个小项，而是**按代码边界和问题耦合度收敛成 3 个整改包**。每个整改包内部一次性改完、一轮测试打透。

### 整改包 1：请求终态与工具终态闭环

**目标**

先把会直接误导 Phoenix 结论的错误修掉，保证一条 TEXT 请求在终态上是可信的。

**这个包一起解决的问题**

1. `tool.execute` 异常路径被错误标记为成功
2. TEXT 取消分支没有真实 `response.dispatch`
3. `tool.result_writeback` 异常时可能不闭合

**为什么这三个问题必须合并处理**

因为它们本质上都是一个问题：**请求终态和工具终态没有形成一致闭环**。

- `tool.execute` 成功/失败语义错了，会误导工具诊断
- `response.dispatch` 少了一条取消路径，会误导请求终态诊断
- `writeback` 不闭合，会让工具终态链路断掉

这三个点分开改没有意义，应该一次性把“请求结束”和“工具结束”的语义统一起来。

**主要改动文件**

- [AIAgentService.kt](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/AIAgentService.kt)
- [TextAgentLoopOrchestrator.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/core/TextAgentLoopOrchestrator.java)
- [AgentTraceRecorder.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/trace/AgentTraceRecorder.java)
- [TraceResponseDispatcher.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/trace/TraceResponseDispatcher.java)

**建议改法**

1. 引入统一的工具终态表达，不再让 `finishTool(...)` 只靠 `SafetyVerdict` 推导成功与否。
2. `tool.execute`、`tool.dispatch`、`tool.result_writeback` 三层状态统一收敛到一个最终工具结果：
   - `SUCCESS`
   - `VETOED`
   - `FAILED`
   - `CANCELLED`
3. Service 层为 TEXT 请求保存 `requestId -> TraceResponseDispatcher` 或等价句柄，让取消分支复用原请求 dispatcher，而不是绕开 trace。
4. `writebackSpan` 改成独立 `try/finally` 生命周期，确保写回失败也能结束 span。

**这个包完成后必须看到的结果**

1. `tool.dispatch` 失败时，父级 `tool.execute` 不再显示成功。
2. TEXT 成功、失败、超时、取消四类终态都能看到 `response.dispatch`。
3. `tool.result_writeback` 无论成功失败都能闭合。

**这个包完成后的最小回归**

- `ToolPhaseTraceTest`
- `TextAgentLoopOrchestratorTest`
- `AIAgentServiceTraceWiringTest`
- 需要补一条真实取消路径测试

### 整改包 2：Context 入模事实与观测收敛

**目标**

把 Phase 2 当前最大的结构性问题一次性解决：现在系统记录的是“看起来可入模”，不是“最终真的入模”；同时新旧观测路径仍然重复。

**这个包一起解决的问题**

1. `included_in_model` 把 `MODEL_VISIBLE` 资格误记成真实入模
2. `context.message.*` 信息不足，仍不能独立承担排障
3. legacy `assembled_messages` / event / 新 span 同时倾倒内容
4. Provider 异常耗时统计不准

**为什么这四个问题必须合并处理**

因为它们都属于同一个根因：**Context 观测口径还没有统一成“最终装配事实”**。

- 入模判断不准，是因为判断点太早
- `message.*` 不够用，是因为还没把最终消息层当成主观测层
- legacy 重复倾倒，是因为主观测层还没立住
- Provider 异常耗时不准，会让 Provider 层本身也不可信

如果不把这四个点一起收敛，Phase 2 永远会停留在“新结构已经有了，但大家还是得回去看旧大字符串”。

**主要改动文件**

- [ContextOrchestrator.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextOrchestrator.java)
- [ContextTraceRecorder.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextTraceRecorder.java)
- [ContextMessageAssembler.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/context/ContextMessageAssembler.java)
- [TraceAttributeKeys.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/trace/TraceAttributeKeys.java)

**建议改法**

1. 把“是否真的入模”的判断后移到 `ContextMessageAssembler.assemble(...)` 之后，再把结果回填给 Provider / fragment / message span。
2. 给 `ContextAssemblyResult` 或 `ContextAssemblyDebugInfo` 增加一份最终装配映射：
   - 哪些 provider 真进模型
   - 哪些 sourceKey 落到哪个槽位
   - 哪些空 contribution 最终被丢弃
3. 强化 `context.message.*`，至少补足：
   - slot
   - roles
   - 红acted 内容摘要
4. 明确主观测归属：
   - Provider span 只保留结构、状态、计数、入模结果
   - fragment/message span 承担最终主内容
   - legacy 大字段降级为兼容兜底，不再默认双写全部内容
5. 同时修掉 Provider 异常分支 duration 统计：
   - `prepare()` 不再用全局 `startMs`
   - `assemble()` 不再写死 `0`

**这个包完成后必须看到的结果**

1. `included_in_model=true` 只代表“最终真的进入了模型消息”。
2. 只看 `context.fragment.*` + `context.message.*`，就能理解模型最终看到了什么。
3. Phoenix 主视图不再同时出现多套完整重复内容。
4. Provider 异常时的耗时有真实诊断价值。

**这个包完成后的最小回归**

- `ContextProviderTraceTest`
- `ContextProductionTraceHierarchyTest`
- 必要的 `ContextTextEndToEndTest`
- 需要补：
  - `MODEL_VISIBLE` 但空内容未入模的 case
  - `context.message.current_user` / `context.message.session_memory` 内容断言

### 整改包 3：Tool Dispatch 诊断增强与最终回归收口

**目标**

在前两个整改包已经把“终态正确”和“Context 事实正确”打稳后，再把 Tool 内部诊断补到计划要求的粒度，并顺手把回归测试收口。

**这个包一起解决的问题**

1. `tool.dispatch` 诊断字段不够，当前只能看到一个拼接后的 target 字符串
2. 参数解析失败和反射调用失败还分不清
3. Phase 3 的最终验收测试还没真正覆盖到这些细粒度语义

**为什么这个包要放最后**

因为它不是“树断了”或“语义错了”的问题，而是**诊断粒度还不够深**。  
先把前两个整改包完成，才能避免在错误语义上继续堆更细的字段。

**主要改动文件**

- [ToolRegistry.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/ai/langchain4j/tool/ToolRegistry.java)
- [ToolDispatcher.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/ai/langchain4j/tool/ToolDispatcher.java)
- [AgentTraceRecorder.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/trace/AgentTraceRecorder.java)
- [TraceAttributeKeys.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/trace/TraceAttributeKeys.java)

**建议改法**

1. 不再只暴露 `TargetClass.methodName` 这种拼接字符串。
2. 补一个轻量结构化 `ToolDispatchInfo`，至少包含：
   - `dispatcherClass`
   - `targetClass`
   - `targetMethod`
3. 在 `ToolDispatcher.dispatch(...)` 内把 dispatch 过程拆成两个稳定可观测阶段：
   - 参数解析
   - 反射调用
4. `tool.dispatch` span 至少补这些属性：
   - `tool.dispatcher_class`
   - `tool.target_class`
   - `tool.target_method`
   - `tool.argument_parse_success`
   - `tool.invoke_success`
5. 同一轮把 Phase 3 相关测试补齐，避免未来再回头返工。

**这个包完成后必须看到的结果**

1. 看 `tool.dispatch` 就能知道问题卡在参数解析还是反射调用。
2. dispatcher / target / method 的定位不再依赖字符串拆读。
3. Phase 3 的主要工具诊断口径能用测试稳定锁住。

**这个包完成后的最小回归**

- `ToolPhaseTraceTest`
- `TextAgentLoopOrchestratorTest`
- 若新增 dispatcher 级 case，则补对应 unit test

### 推荐执行顺序

按高效率执行，不要再拆成很多零散提交，建议就是这 3 次：

1. **第一次提交：整改包 1**
   - 解决所有“终态会误导人”的问题
2. **第二次提交：整改包 2**
   - 解决所有“Context 观测事实不准/重复”的问题
3. **第三次提交：整改包 3**
   - 补齐 dispatcher 深度诊断，并完成最终回归收口

### 每次提交后的验收方式

1. 每个整改包提交后，只跑对应最小回归，不做无关全量。
2. 三个整改包都通过后，再统一跑一次：
   - `ContextProductionTraceHierarchyTest`
   - `ContextProviderTraceTest`
   - `ToolPhaseTraceTest`
   - `AIAgentServiceTraceWiringTest`
   - `ContextTextEndToEndTest`
   - `TextAgentLoopOrchestratorTest`
   - `AgentRuntimeContextTest`

---

## 测试情况

本轮实际执行并通过：

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.trace.ContextProductionTraceHierarchyTest" --tests "com.hirain.aiagent.trace.ContextProviderTraceTest" --tests "com.hirain.aiagent.trace.ToolPhaseTraceTest" --tests "com.hirain.aiagent.trace.AIAgentServiceTraceWiringTest"
```

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.runtime.ContextTextEndToEndTest" --tests "com.hirain.aiagent.core.TextAgentLoopOrchestratorTest" --tests "com.hirain.aiagent.runtime.AgentRuntimeContextTest"
```

结果：均为 `BUILD SUCCESSFUL`

但这些测试仍有明显覆盖缺口：

1. 没有拦住“工具异常时 `tool.execute` 被记成成功”
2. 没有覆盖 `AIAgentService.cancelAgentRequest(...)` 的真实 `response.dispatch` 接线
3. 没有验证 `context.message.*` 是否承载足够内容，能否真正替代 legacy 大字段

---

## 最终判定

当前提交已经完成了相当一部分 Phase 1-3 主体工作，但**还没有达到可以放心验收通过的状态**。

如果要进入下一轮，建议优先按这个顺序修：

1. 修正 `tool.execute` 异常路径的成功/失败判定。
2. 把 TEXT 取消路径接回 `TraceResponseDispatcher`。
3. 把“是否进模型”从 `MODEL_VISIBLE` 资格判断，改成基于最终装配结果的真实判断。
4. 让 `context.message.*` 成为真正可用的主观测层，减少对 legacy assembled 大字段的依赖。
5. 补齐 `tool.dispatch` 需要的诊断字段和 writeback 异常收口。
