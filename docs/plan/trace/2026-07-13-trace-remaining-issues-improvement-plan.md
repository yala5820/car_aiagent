# 2026-07-13 Trace 模块残留问题改进计划

## 1. 复查结论

基于当前代码复查，`11.3` 中列出的 6 个问题仍然存在，其中一部分属于“结构已搭好，但真实执行事实没有贯通”，因此不能判定为完成。

| 问题 | 结论 | 关键证据 |
|------|------|----------|
| `gen_ai.request.tool_specs` / `context.toolset` 看不到完整 schema | 仍存在 | `app/src/main/java/com/hirain/aiagent/trace/AgentTraceRecorder.java:154-163` 只写 `name` 和 `desc`；`app/src/main/java/com/hirain/aiagent/context/ContextTraceRecorder.java:296-305` 只写工具名 |
| Provider span 没有包裹真实 Provider 调用 | 仍存在 | `app/src/main/java/com/hirain/aiagent/context/ContextOrchestrator.java:109-124`、`255-270` 先 `provider.provide()`，后 `startProviderSpan()` |
| `recordMessage()` 仍主动截断消息内容 | 仍存在 | `app/src/main/java/com/hirain/aiagent/context/ContextTraceRecorder.java:276-281` 固定截到前 200 字符 |
| Trace 配置与当前 Demo 决策不一致 | 仍存在 | `app/src/main/java/com/hirain/aiagent/AIAgentService.kt:367` 使用 `TraceConfig.development()`；`app/src/main/java/com/hirain/aiagent/trace/TraceConfig.java:52-63` 仍是 `REDACTED`；`app/src/main/java/com/hirain/aiagent/trace/TraceAttributeWriter.java:7-9`、`46-56` 仍有统一截断 |
| Tool dispatch 阶段没有读取真实 `DispatchDiagnostics` | 仍存在 | `app/src/main/java/com/hirain/aiagent/core/TextAgentLoopOrchestrator.java:266-277` 直接硬编码 `true/true` 或 `false/false`；`app/src/main/java/com/hirain/aiagent/ai/langchain4j/tool/ToolDispatcher.java:64-90` 的真实诊断对象未被接入 |
| writeback span 永远记录成功 | 仍存在 | `app/src/main/java/com/hirain/aiagent/core/TextAgentLoopOrchestrator.java:284-296` 在 `finally` 中固定 `finishToolWriteback(writebackSpan, true)` |

## 2. 现状判断

当前 Trace 的主要问题已经不是“完全没有树结构”，而是“很多子 span 的字段不代表真实运行事实”。这会直接影响 Phoenix 排障价值，尤其体现在：

- 看到了 `tool.dispatch`，但它不一定反映真实参数解析/反射调用阶段的成败。
- 看到了 `context.provider.*`，但 span 生命周期不是 Provider 的真实执行生命周期。
- 看到了 `context.message.*`，但关键正文仍被单独截断，和“最终请求正文完整可见”的目标不一致。
- 看到了 `tool.result_writeback`，但失败场景仍会被记成成功。

因此，后续整改不应再按“补字段”推进，而应按“让 span 与真实执行事实对齐”推进。

## 3. 改进目标

本轮后续整改目标收敛为 3 件事：

1. 让 Context/Provider 相关 span 反映真实构建过程，而不是事后补记。
2. 让 Tool 相关 span 反映真实调度与写回结果，而不是硬编码状态。
3. 让内容采集策略在 Demo 阶段保持统一，不再出现“同一条 Trace 中有的字段完整、有的字段脱敏/截断”的混乱状态。

---

## 4. 整改包 A：Context 生命周期与 Tool Schema 可见性收敛

### 目标

把 Provider / Message / Toolset / LLM Request 的 trace 从“结构上存在”提升为“能还原真实 Context 拼装过程和真实入模 schema”。

### 涉及文件

- `app/src/main/java/com/hirain/aiagent/context/ContextOrchestrator.java`
- `app/src/main/java/com/hirain/aiagent/context/ContextTraceRecorder.java`
- `app/src/main/java/com/hirain/aiagent/trace/AgentTraceRecorder.java`
- `app/src/main/java/com/hirain/aiagent/trace/TraceMessageFormatter.java`
- 如序列化 `ToolSpecification.parameters()` 需要抽公共逻辑，可允许新增一个仅服务 Trace 的轻量 helper，位置放在 `trace/` 或 `context/` 下即可，但不要扩散到业务模块

### 修改方式

- 在 `ContextOrchestrator.prepare()` 中改写 request-static Provider 循环：
  - 当前位置是先 `provider.provide()`，再 `startProviderSpan()`。
  - 要改成进入每个 Provider 前先创建 `provSpan`，并 `makeCurrent()`。
  - `provider.provide()`、结果判定、异常记录都在这条 span 的作用域内完成。
  - span 结束统一放到 `finally`，不要再保留“异常后补建一条 errProvSpan”的分支。
- 在 `ContextOrchestrator.assemble()` 中用同样方式改写 iteration-dynamic Provider 循环：
  - 处理方式与 `prepare()` 保持对称。
  - 目标是让 `context.provider.*` 的 span duration、异常状态、后续子 span 挂载点都基于真实执行生命周期。
- 调整 `ContextTraceRecorder.finishProviderSpan()`：
  - 保留现有 provider 统计字段；
  - 但不要再假设 span 是“事后补记 span”，它应只负责收口属性和结束；
  - 如果需要记录异常，统一由调用方在 catch 中把 error/status 写到同一条 `provSpan` 上，而不是新建 span。
- 调整 `ContextTraceRecorder.recordMessage()`：
  - 当前只写首条消息前 200 字符摘要，这个策略不满足排障目标。
  - 改成记录完整消息内容，建议格式为按消息顺序拼接：`[index][role] content`。
  - 仍保留 `message.count`、`message.roles` 这类摘要字段，但它们只能作为辅助字段，不能代替正文。
  - 如果担心重复，可只在 `context.message.*` span 中承载 contribution 级正文，不再额外扩散到其他 context 字段。
- 调整 `ContextTraceRecorder.recordToolset()`：
  - 当前只写工具名列表。
  - 改成至少补齐 `name / description / parameters` 三部分。
  - 不要求做复杂 JSON 美化，但必须能从 Trace 还原模型当轮看到的 schema。
- 调整 `AgentTraceRecorder.recordLlmRequest()`：
  - 当前 `gen_ai.request.tool_specs` 只输出 `name` 和 `desc`。
  - 这里必须补齐 `parameters`，并与 `context.toolset` 的序列化格式保持一致。
  - 如果 `TraceMessageFormatter.formatToolSpecifications()` 仍只返回工具名，就必须同步扩展，避免 prompt trace 和 request trace 两边格式不一致。

### 修改边界

- 本包不修改 Tool 实际执行逻辑，不触碰 `ToolRegistry` 的注册规则。
- 本包不新增更多 span 层级，重点是修正现有 `context.provider.*`、`context.message.*`、`context.toolset`、`gen_ai.request.tool_specs` 的真实性和可读性。
- 本包不做“按 token 长度动态裁剪 schema”之类的优化，先保证正确和一致。

### 子 agent 交付标准

- `context.provider.*` 的 span duration 与 Provider 真实执行时间一致。
- Provider 抛异常时，不再出现“异常后补建 span”的情况。
- `context.message.*` 可以单独看清 SessionMemory / CurrentUser 等 contribution 的完整正文。
- `context.toolset` 可以直接还原模型本轮实际看到的工具 schema。
- `gen_ai.request.tool_specs` 与 `context.toolset` 都能还原完整 schema，格式允许不完全相同，但字段完整度必须一致。

---

## 5. 整改包 B：Tool 阶段真实诊断与终态语义收敛

### 目标

把 Tool trace 从“阶段名齐全”提升为“阶段结果可信”，重点修复 dispatch 与 writeback 的失真问题。

### 涉及文件

- `app/src/main/java/com/hirain/aiagent/core/TextAgentLoopOrchestrator.java`
- `app/src/main/java/com/hirain/aiagent/ai/langchain4j/tool/ToolRegistry.java`
- `app/src/main/java/com/hirain/aiagent/ai/langchain4j/tool/ToolDispatcher.java`
- `app/src/main/java/com/hirain/aiagent/trace/AgentTraceRecorder.java`
- `app/src/main/java/com/hirain/aiagent/trace/TraceAttributeKeys.java`（仅当需要补更细粒度 writeback 属性时修改）

### 修改方式

- 在 `TextAgentLoopOrchestrator` 中接入 `ToolDispatcher.DispatchDiagnostics` 真值：
  - 通过 `ToolRegistry.dispatcherFor(toolName)` 取得真实 dispatcher；
  - 使用 `dispatcher.dispatch(request, diagnostics)` 执行；
  - `tool.argument_parse_success`、`tool.invoke_success` 只写 diagnostics 真实结果；
  - `tool.dispatch_success` 与“是否真正完成业务调用”保持一致，不能再简单绑定是否抛异常。
- 具体落点是 `TextAgentLoopOrchestrator` 当前工具执行主循环里的 Stage 2：
  - 现在调用的是 `config.toolExecutor().execute(req)`，trace 侧又额外查 `targetClass/targetMethod` 并硬编码成功布尔值。
  - 子 agent 需要把“真实执行工具”和“拿到真实 dispatch 诊断”统一到同一条路径上，避免工具实际上走一条执行路径、trace 记录走另一套猜测逻辑。
  - 如果现有 `toolExecutor` 封装挡住了 diagnostics，就在不破坏现有接口使用面的前提下，把 diagnostics 透传出来；优先局部扩展，不要重写整套执行器抽象。
- 明确“业务失败文本”和“dispatch 失败”的边界：
  - 参数解析失败、反射调用失败，属于 dispatch 失败；
  - 工具业务返回错误文本但调用链已执行完成，属于 dispatch 成功、tool 结果失败。
- 如 `ToolDispatcher.dispatch()` 当前把多种失败都压成 `"工具执行失败: xxx"` 字符串，子 agent 需要先梳理哪一类失败属于 parse/invoke 阶段，哪一类只是业务返回失败，再决定 trace 如何判定，不能继续仅凭“字符串里像失败”去猜。
- 重构 writeback 阶段收口：
  - 区分 `chatMemory.add(...)` 成功、`loopCtx.addToolResult(...)` 成功、整体 writeback 成功；
  - `finishToolWriteback()` 只能写入真实结果，不能在 `finally` 中固定写 `true`。
- `TextAgentLoopOrchestrator` 的具体改法建议：
  - 在 writeback 前定义局部布尔值，例如 `memoryWriteSuccess`、`loopCtxWriteSuccess`、`writebackSuccess`；
  - 每一步完成后分别置位；
  - `finally` 中只负责结束 span，不负责伪造成功结果；
  - 如果要补属性，优先增加少量高价值字段，不要再把 writeback 扩成一棵新树。
- `AgentTraceRecorder.finishToolWriteback()`：
  - 至少保证最终布尔值语义准确；
  - 如果新增细分属性，也在这里统一写入，避免调用方到处散写 `span.setAttribute(...)`。

### 修改边界

- 本包只处理 Tool trace 真实性，不改 ToolRegistry 的业务注册模型。
- 不在本包内引入新的 Tool 安全策略或新的工具选择逻辑。
- 仅修正 Trace 对执行事实的表达，不改变既有 Tool 返回文案格式。

### 子 agent 交付标准

- `tool.dispatch` 的 parse / invoke / success 字段与真实 dispatcher 执行结果一致。
- “工具返回失败文本但未抛异常”场景，不再被误标为 dispatch 全成功。
- `tool.result_writeback` 在任一写回子步骤失败时，不再记录成功。

---

## 6. 整改包 C：Trace 内容采集策略统一化与回归闭环

### 目标

统一当前 Demo 阶段的内容采集策略，消除“部分字段完整、部分字段脱敏/截断”的混合状态，并把测试压缩成最少的联合验证。

### 涉及文件

- `app/src/main/java/com/hirain/aiagent/AIAgentService.kt`
- `app/src/main/java/com/hirain/aiagent/trace/TraceConfig.java`
- `app/src/main/java/com/hirain/aiagent/trace/TraceAttributeWriter.java`
- `app/src/test/java/com/hirain/aiagent/trace/ContextProviderTraceTest.java`
- `app/src/test/java/com/hirain/aiagent/trace/ToolPhaseTraceTest.java`
- 仅当现有测试难以承载组合验证时，允许新增最多 1 个新的 trace 回归测试类

### 修改方式

- 明确 Demo 阶段 Trace 策略：
  - 如果当前决策是设备侧本地调试优先，则 `TraceConfig.development()` 需要显式切到与目标一致的模式；
  - 若保留脱敏模式，则所有主排障字段必须统一走同一套 writer 规则，不能有的字段绕过 writer 直写完整内容。
- 子 agent 先不要争论“最终应该 REDACTED 还是 FULL_DEBUG”，而是先做一件事：
  - 把当前代码里“配置声明”和“实际字段采集行为”统一起来。
  - 也就是说，最终要么统一完整、要么统一脱敏/截断，但不能同时存在两套标准。
- 收敛 `TraceAttributeWriter` 与直接 `span.setAttribute(...)` 的职责边界：
  - 所有正文/参数/schema 类字段统一走一套入口；
  - 统一长度、脱敏、截断策略。
- 测试不要拆碎，按“联合回归”收敛成两组即可：
  - 第一组：`ContextProviderTraceTest`
    - 合并验证 provider span 生命周期真实包裹；
    - 验证 `context.message.*` 正文完整；
    - 验证 `context.toolset` 具备完整 schema；
    - 验证 `gen_ai.request.tool_specs` 具备完整 schema。
  - 第二组：`ToolPhaseTraceTest`
    - 合并验证真实 `DispatchDiagnostics` 接入；
    - 验证“业务失败文本”和“dispatch 失败”边界；
    - 验证 writeback 失败时 span 状态准确。
- 如果 `ContextProviderTraceTest` 无法自然覆盖 `gen_ai.request.tool_specs`，允许新增 1 个轻量测试类专门打通 `recordLlmRequest()`，但不要再拆出更多测试文件。
- 设备侧验收不要求本轮立即改 Phoenix 代码，只要求补一份最小证据：
  - 截图、导出属性、或日志化的 span 字段都可以；
  - 重点证明 schema、provider duration、dispatch diagnostics、writeback 结果这四类信息能在真实链路看到。

### 修改边界

- 本包以统一策略和回归闭环为主，不再新增新的 trace 树层级。
- 不处理生产环境采样、上报成本优化等后续议题。
- Phoenix 侧验收只要求证明字段可见且语义正确，不要求做平台定制开发。

### 子 agent 交付标准

- 同一类正文/参数/schema 字段不再出现采集策略不一致。
- 测试总量控制在 `2` 个主测试类，最多允许补 `1` 个轻量补充类。
- 至少有一份本次代码提交后的 Phoenix 或等价导出证据，证明最终 Trace 可用。

---

## 7. 建议执行顺序

建议按 `A -> B -> C` 执行，原因如下：

- `A` 先修 Context 生命周期与 schema 可见性，能先把 Phoenix 的主观察面修正。
- `B` 再修 Tool 诊断真值，避免后续仍然出现“看起来有 span，实际不能判障”的问题。
- `C` 最后统一策略和补回归，防止前两包修完后又被旧采集规则冲掉。

## 8. 子 agent 执行约束

为了控制改动面和 token 消耗，本计划附加以下执行约束：

- 不允许把问题拆成很多零散 phase，本次就按 `A/B/C` 三个整改包推进。
- 不允许为了测试方便再额外复制一套 trace 代码或引入新的测试基础设施，优先复用现有 `ContextProviderTraceTest` 与 `ToolPhaseTraceTest`。
- 除非现有测试类实在无法承载，否则不要新增超过 `1` 个测试类。
- 每个整改包完成后只做一次局部验证；全部完成后再做一次联合回归，不要每改一个小点就跑一轮完整测试。

## 9. 复查后的完成度判断

本次复查后，建议将完成度判断更新为：

- Trace 树结构：约 `90%`
- Context Provider / 输入来源可见性：约 `75%`
- 最终消息正文可见性：约 `80%`
- 完整工具 schema 与真实 Tool 阶段诊断：约 `50%-60%`
- Phoenix 设备侧最终验收：`仍缺正式证据`

这里的下调重点不在“有没有 span”，而在“span 是否真的可信、是否足以支持排障”。
