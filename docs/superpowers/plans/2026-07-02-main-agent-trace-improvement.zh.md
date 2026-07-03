# 主 Agent Trace 完善实施计划

> **给执行 agent 的要求：** 实施本计划时必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans`，逐项执行并用 checkbox（`- [ ]`）跟踪进度。

**目标：** 为基于 text 输入的主 Agent 路径建立更安全、更标准、更完整的 trace 系统，覆盖 AgentLoop、Prompt 组装、Memory 操作、LLM 调用、Tool 调用和最终响应。

**架构：** 保留现有 OpenTelemetry Java SDK 以及 `TraceManager` / `TraceSession` 门面层，但新增一层很薄的业务 trace 封装，用它统一管理 span 名称、attribute key、脱敏和消息格式化。text 请求入口创建一个 root trace；`AgentLoopOrchestrator` 不再到处手写 `span.setAttribute()`，而是把 prompt、LLM、tool、memory 的 trace 写入委托给统一 recorder。

**技术栈：** Android app 模块、Kotlin/Java、LangChain4j、OpenTelemetry Java SDK 1.48.0、JUnit 单元测试、Gradle `:app:testDebugUnitTest`。

---

## 范围

包含：

- text 输入路径：`processAgentRequest(AgentRequest)` 和 `handleTextRequest()`。
- 主 `AgentLoopOrchestrator`。
- Prompt 渲染以及最终组装出的 `ChatRequest` 消息。
- LLM 请求/响应、token 用量、tool-call 决策。
- Tool 请求、参数、结果、安全 veto。
- Memory 提取和压缩操作。
- Trace 安全问题：trace context 缺失、脱敏未接入、错误状态、span 关闭安全。

本阶段不包含：

- `IMAGE` / VLM 直接图像问答路径。
- VR/TTS 生命周期 trace。
- Scene Agent 和 Camera 主动场景服务 trace。
- Android UI/RUM trace、ANR、Activity 生命周期、慢帧指标。

## 文件结构

新增：

- `app/src/main/java/com/hirain/aiagent/trace/TraceAttributeKeys.java`
  - 统一存放 request、agent、prompt、LLM、tool、memory、error 相关 attribute key。
- `app/src/main/java/com/hirain/aiagent/trace/TraceSpanNames.java`
  - 统一存放 span 名称。
- `app/src/main/java/com/hirain/aiagent/trace/TraceAttributeWriter.java`
  - 带脱敏能力的 attribute 写入器，所有 trace 代码通过它写 attribute。
- `app/src/main/java/com/hirain/aiagent/trace/TraceMessageFormatter.java`
  - 将 LangChain4j message 和 tool spec 转换为长度受控的 trace 字符串。
- `app/src/main/java/com/hirain/aiagent/trace/AgentTraceRecorder.java`
  - 主 AgentLoop trace span 的高级 API。
- `app/src/test/java/com/hirain/aiagent/trace/TraceRedactorTest.java`
  - 测试文本、手机号、base64 和截断脱敏。
- `app/src/test/java/com/hirain/aiagent/trace/TraceMessageFormatterTest.java`
  - 测试消息格式化和截断。

修改：

- `app/src/main/java/com/hirain/aiagent/trace/TraceConfig.java`
  - 增加内容采集策略，并让脱敏默认安全。
- `app/src/main/java/com/hirain/aiagent/trace/TraceRedactor.java`
  - 修复手机号替换逻辑，保持截断行为稳定。
- `app/src/main/java/com/hirain/aiagent/trace/TraceManager.java`
  - 使用 `TraceAttributeWriter`，并在 root span 写入 request 元数据。
- `app/src/main/java/com/hirain/aiagent/trace/TraceSession.java`
  - 增加安全的子 span helper、异常记录、root attribute 写入能力。
- `app/src/main/java/com/hirain/aiagent/trace/TracingOkHttpInterceptor.java`
  - 使用更规范的 HTTP attribute，并在失败时设置 error status。
- `app/src/main/java/com/hirain/aiagent/AIAgentService.kt`
  - text 请求 root trace 带上 `AgentRequest` 元数据并安全关闭。
- `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java`
  - 移除分散的底层 span 写入，改用 `AgentTraceRecorder`。
- `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java`
  - 接收可选 trace recorder/session，用于 memory 操作 span。
- `app/src/main/java/com/hirain/aiagent/memory/MemoryExtractor.java`
  - trace 提取 prompt、结果、候选数量和错误。
- `app/src/main/java/com/hirain/aiagent/memory/MemoryCompressor.java`
  - trace 压缩决策、prompt、结果和错误。
- `docs/act_summary/trace-system-evaluation-report.md`
  - 实施后更新实际修复状态。

---

## 阶段 1：安全 Bug 与 Trace Policy

### Task 1：新增 Trace 常量

**文件：**

- 新增：`app/src/main/java/com/hirain/aiagent/trace/TraceSpanNames.java`
- 新增：`app/src/main/java/com/hirain/aiagent/trace/TraceAttributeKeys.java`

- [ ] **Step 1：创建 span 名称常量**

创建 `TraceSpanNames.java`，包含：

- `agent.request`
- `agent.loop`
- `prompt.assembly`
- `gen_ai.chat`
- `tool.execute`
- `memory.extract`
- `memory.compress`
- `response.dispatch`

- [ ] **Step 2：创建 attribute key 常量**

创建 `TraceAttributeKeys.java`，覆盖以下分组：

- request：`request.id`、`session.id`、`source.app`、`input.type`、`user.input`、`user.input.length`
- agent：`agent.persona`、`agent.iteration`、`agent.max_iterations`
- prompt：system prompt、transient messages、chat messages、message count、tool specs
- gen_ai：provider、model、token usage、输出、tool calls
- tool：name、arguments、output、success、safety veto
- memory：operation、input chars、output chars、candidate count、compressed
- response：success、error type、text length
- error：error type、error message

- [ ] **Step 3：运行 Java 编译检查**

```powershell
.\gradlew.bat :app:compileDebugJavaWithJavac
```

期望：编译成功，或只出现已有的、与本次改动无关的错误。

### Task 2：增加带脱敏的 Attribute Writer

**文件：**

- 修改：`app/src/main/java/com/hirain/aiagent/trace/TraceConfig.java`
- 修改：`app/src/main/java/com/hirain/aiagent/trace/TraceRedactor.java`
- 新增：`app/src/main/java/com/hirain/aiagent/trace/TraceAttributeWriter.java`
- 测试：`app/src/test/java/com/hirain/aiagent/trace/TraceRedactorTest.java`

- [ ] **Step 1：修复手机号脱敏替换**

将 `TraceRedactor.redactPhone()` 替换为保留前三位和后四位的实现：

```java
private String redactPhone(String text) {
    return text.replaceAll(
            "((?:\\+86)?1[3-9]\\d)\\d{4}(\\d{4})",
            "$1****$2");
}
```

这个修改同时修复当前替换逻辑引用了不存在 capture group 的问题。

- [ ] **Step 2：扩展 `TraceConfig` 内容采集策略**

增加 `ContentCaptureMode`：

- `OFF`
- `REDACTED`
- `FULL_DEBUG`

默认：

- development：`REDACTED`
- production：`OFF`
- `redactSensitive` 默认开启

- [ ] **Step 3：创建 `TraceAttributeWriter`**

职责：

- `putText()`：写用户输入、prompt、消息内容，按策略脱敏或截断。
- `putArgument()`：写 tool 参数，识别 base64 并截断。
- `putResult()`：写 tool / LLM / memory 结果，默认截断。
- `putString()` / `putLong()` / `putBoolean()`：写非敏感结构化属性。

- [ ] **Step 4：增加脱敏单元测试**

测试点：

- 手机号不应原文出现在 trace 内容中。
- 超长用户输入会截断。
- base64 参数会截断。

- [ ] **Step 5：运行测试**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.hirain.aiagent.trace.TraceRedactorTest"
```

期望：PASS。

### Task 3：修复 Trace Context 可选性安全问题

**文件：**

- 修改：`app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java`

- [ ] **Step 1：增加安全提取 helper**

新增 `extractTraceSession(Map<String, Object> extraContext)`：

- `extraContext == null` 时返回 null。
- 不存在 `_trace_context` 时返回 null。
- 类型不是 `TraceContext` 时返回 null。
- trace 不 active 时返回 null。

- [ ] **Step 2：替换直接强转**

将当前直接：

```java
((TraceContext) extraContext.get(TraceContext.TRACE_CONTEXT_KEY)).session()
```

替换为安全 helper。

- [ ] **Step 3：编译验证**

```powershell
.\gradlew.bat :app:compileDebugJavaWithJavac
```

期望：没有新增 Java 编译错误。

---

## 阶段 2：Text 请求 Root Trace 标准化

### Task 4：Root Span 写入 AgentRequest 元数据

**文件：**

- 修改：`app/src/main/java/com/hirain/aiagent/trace/TraceManager.java`
- 修改：`app/src/main/java/com/hirain/aiagent/trace/TraceSession.java`
- 修改：`app/src/main/java/com/hirain/aiagent/AIAgentService.kt`

- [ ] **Step 1：增加 request-aware session 创建方法**

在 `TraceManager` 中增加 `startAgentRequest(...)`，参数包含：

- personaId
- userId
- requestId
- sessionId
- sourceApp
- inputType
- userInput

并写入 root span：

- `request.id`
- `session.id`
- `source.app`
- `input.type`
- `user.input.length`

- [ ] **Step 2：让 `TraceSession` 暴露 tracer，并让 root attribute 写入 null-safe**

增加：

```java
public Tracer tracer() {
    return tracer;
}
```

`setAttribute(String, String)` 遇到 null 时直接忽略。

- [ ] **Step 3：在 `handleTextRequest()` 使用 request-aware session**

将当前 `startSession("chat", ...)` 替换为 `startAgentRequest(...)`。

- [ ] **Step 4：响应回调前记录 `response.dispatch` span 和 root summary attribute**

在 `notifyAIAgentListeners(...)` 前创建 `response.dispatch` span，记录：

- `response.success`
- `response.text.length`
- `response.error_type`

同时在 root span 上保留概要属性，方便检索。

- [ ] **Step 5：编译**

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

期望：Kotlin 编译成功，或只出现已有的、与本次无关的错误。

---

## 阶段 3：AgentLoop / Prompt / LLM / Tool Trace Recorder

### Task 5：新增 Message Formatter

**文件：**

- 新增：`app/src/main/java/com/hirain/aiagent/trace/TraceMessageFormatter.java`
- 测试：`app/src/test/java/com/hirain/aiagent/trace/TraceMessageFormatterTest.java`

- [ ] **Step 1：创建 formatter**

能力：

- 将 `SystemMessage` 格式化为 role=`system`。
- 将 `UserMessage` 格式化为 role=`user`。
- 将 `AiMessage` 格式化为 role=`assistant` 或 `assistant_tool_calls`。
- 将 `ToolExecutionResultMessage` 格式化为 role=`tool:<toolName>`。
- 将 tool spec 格式化为 tool 名称列表。
- 总长度超过上限时截断并标记 `truncated`。

- [ ] **Step 2：增加 formatter 测试**

测试点：

- system/user 消息能正确输出。
- 超长消息会截断。

- [ ] **Step 3：运行测试**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.hirain.aiagent.trace.TraceMessageFormatterTest"
```

期望：PASS。

### Task 6：新增 AgentTraceRecorder

**文件：**

- 新增：`app/src/main/java/com/hirain/aiagent/trace/AgentTraceRecorder.java`
- 修改：`app/src/main/java/com/hirain/aiagent/trace/TraceSession.java`

- [ ] **Step 1：`TraceSession` 暴露 writer 和 tracer**

`TraceSession` 持有：

- `TraceAttributeWriter writer`
- `Tracer tracer`

并提供：

- `writer()`
- `tracer()`

`TraceManager` 创建 session 时传入 writer。

- [ ] **Step 2：创建 recorder**

`AgentTraceRecorder` 提供：

- `startPromptAssembly(...)`
- `startLlmCall(...)`
- `enrichLlmResponse(...)`
- `startTool(...)`
- `finishTool(...)`
- `recordException(...)`

所有内容类 attribute 都通过 `TraceAttributeWriter` 写入，避免业务代码直接写敏感内容。

- [ ] **Step 3：编译**

```powershell
.\gradlew.bat :app:compileDebugJavaWithJavac
```

期望：没有新增 Java 编译错误。

### Task 7：用 Recorder 替换 AgentLoop 中分散的 Span 代码

**文件：**

- 修改：`app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java`

- [ ] **Step 1：创建 recorder**

在 trace session 提取后创建：

```java
AgentTraceRecorder trace = new AgentTraceRecorder(traceSession);
```

- [ ] **Step 2：包裹 prompt assembly**

在 transient messages 准备完成、`ChatRequest` build 之前创建 `prompt.assembly` span。

记录：

- persona
- iteration
- transient messages
- chat memory messages
- message count
- tool spec count
- tool specs

- [ ] **Step 3：替换 LLM span 创建**

用：

```java
trace.startLlmCall(config.modelName(), i, allMessages.size())
```

替换当前 `traceSession.startLlmSpan("qwen", ...)`。

- [ ] **Step 4：替换 LLM 响应 enrichment**

用：

```java
trace.enrichLlmResponse(llmSpan, response)
```

记录：

- 模型输出
- tool call 决策
- input/output/total token

- [ ] **Step 5：记录模型异常**

LLM 调用 catch 中调用：

```java
trace.recordException(llmSpan, e)
```

然后继续抛出异常给现有错误处理。

- [ ] **Step 6：替换 tool span 创建与结束**

使用：

- `trace.startTool(toolReq, i)`
- `trace.finishTool(...)`

记录：

- tool name
- tool arguments
- output
- success
- safety veto
- veto reason

- [ ] **Step 7：删除旧 `enrichLlmSpan()` 方法**

确认无调用后删除旧方法。

- [ ] **Step 8：编译**

```powershell
.\gradlew.bat :app:compileDebugJavaWithJavac
```

期望：没有新增 Java 编译错误。

### Task 8：为 AgentConfig 增加 Model Name

**文件：**

- 修改：`app/src/main/java/com/hirain/aiagent/core/AgentConfig.java`
- 修改：`app/src/main/java/com/hirain/aiagent/core/factory/AgentConfigFactory.java`

- [ ] **Step 1：`AgentConfig` 增加 `modelName` 字段和 getter**

新增：

- `private final String modelName`
- `public String modelName()`
- Builder 字段 `modelName = "unknown"`
- Builder 方法 `modelName(String modelName)`

- [ ] **Step 2：在 chat persona 设置模型名**

在 chat persona builder 中设置：

```java
.modelName("qwen-turbo")
```

本阶段不扩展 scene 或 vision trace。如果编译或 lint 要求所有 persona 显式设置 model name，再补充 scene/vision 的值，但这不代表把它们纳入本阶段 trace 范围。

- [ ] **Step 3：编译**

```powershell
.\gradlew.bat :app:compileDebugJavaWithJavac
```

期望：没有新增 Java 编译错误。

---

## 阶段 4：Memory Trace 覆盖

### Task 9：将 Trace Recorder 传入 MemoryOrchestrator

**文件：**

- 修改：`app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java`
- 修改：`app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java`
- 修改：`app/src/main/java/com/hirain/aiagent/memory/UserMemoryContext.java`

- [ ] **Step 1：`MemoryOrchestrator` 增加 overload**

新增带 `AgentTraceRecorder trace` 的 `onTurnComplete(...)`。

保留旧方法，并让旧方法委托给新方法且传入 null。

- [ ] **Step 2：`UserMemoryContext` 增加 overload**

新增：

- `extractAndStore(..., AgentTraceRecorder trace)`
- `compressIfNeeded(..., AgentTraceRecorder trace)`

保留旧方法并委托。

- [ ] **Step 3：AgentLoop 中传入 recorder**

将 `memoryOrchestrator.onTurnComplete(...)` 替换为带 trace recorder 的 overload。

- [ ] **Step 4：编译**

```powershell
.\gradlew.bat :app:compileDebugJavaWithJavac
```

期望：没有新增 Java 编译错误。

### Task 10：Trace Memory Extraction

**文件：**

- 修改：`app/src/main/java/com/hirain/aiagent/memory/MemoryExtractor.java`
- 修改：`app/src/main/java/com/hirain/aiagent/trace/AgentTraceRecorder.java`

- [ ] **Step 1：Recorder 增加 memory span helper**

新增：

- `startMemory("extract", inputChars)`
- `finishMemoryExtract(span, prompt, output, candidateCount)`

- [ ] **Step 2：`MemoryExtractor` 增加带 trace 的 overload**

新增：

```java
extract(String userMessage, String aiResponse, AgentTraceRecorder trace)
```

记录：

- extraction prompt
- LLM output
- candidate count
- 异常信息

旧方法保留并委托给新方法。

- [ ] **Step 3：编译**

```powershell
.\gradlew.bat :app:compileDebugJavaWithJavac
```

期望：没有新增 Java 编译错误。

### Task 11：Trace Memory Compression

**文件：**

- 修改：`app/src/main/java/com/hirain/aiagent/memory/MemoryCompressor.java`
- 修改：`app/src/main/java/com/hirain/aiagent/trace/AgentTraceRecorder.java`

- [ ] **Step 1：Recorder 增加 compression finish helper**

新增：

- `finishMemoryCompress(span, compressed, prompt, summary)`

记录：

- 是否压缩
- compression prompt
- summary
- output length

- [ ] **Step 2：`MemoryCompressor.compress()` 增加带 trace 的 overload**

新增：

```java
compress(List<ChatMessage> messages, int currentTokens, AgentTraceRecorder trace)
```

旧方法保留并委托。

- [ ] **Step 3：在 summarize 中记录 prompt 和 summary**

压缩发生时记录：

- prompt
- summary
- compressed=true

未触发压缩时记录：

- compressed=false

- [ ] **Step 4：编译**

```powershell
.\gradlew.bat :app:compileDebugJavaWithJavac
```

期望：没有新增 Java 编译错误。

---

## 阶段 5：HTTP 与错误规范化

### Task 12：规范化 HTTP Trace Attribute

**文件：**

- 修改：`app/src/main/java/com/hirain/aiagent/trace/TracingOkHttpInterceptor.java`

- [ ] **Step 1：替换 HTTP attribute 名称**

改为记录：

- `http.response.status_code`
- `http.request.method`
- `url.full`
- `server.address`
- `http.duration_ms`

- [ ] **Step 2：IOException 设置 error status**

网络异常时：

- `recordException(e)`
- `setStatus(ERROR, e.getMessage())`
- 写入 `error.type`

- [ ] **Step 3：4xx/5xx 设置 error status**

HTTP status >= 400 时：

- span status = ERROR
- `error.type` = status code

- [ ] **Step 4：编译**

```powershell
.\gradlew.bat :app:compileDebugJavaWithJavac
```

期望：没有新增 Java 编译错误。

---

## 阶段 6：验证与文档

### Task 13：聚焦验证

**文件：**

- 无源码修改。

- [ ] **Step 1：运行 trace 单元测试**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.hirain.aiagent.trace.*"
```

期望：所有 trace 单元测试通过。

- [ ] **Step 2：运行 Java/Kotlin 编译**

```powershell
.\gradlew.bat :app:compileDebugJavaWithJavac :app:compileDebugKotlin
```

期望：编译成功；若失败，必须确认并记录是否为已有无关问题。

- [ ] **Step 3：Phoenix 手动冒烟测试**

设备上运行 app，并使用：

```powershell
adb reverse tcp:6006 tcp:6006
```

通过现有测试 App 或调用方发送一个 `TEXT` `AgentRequest`。

期望 Phoenix trace 结构：

```text
agent.request
├── prompt.assembly
├── gen_ai.chat
├── tool.execute          仅当 LLM 调用 tool 时出现
├── gen_ai.chat           仅当 tool 结果回填后再次调用 LLM 时出现
├── memory.extract
└── response.dispatch
```

期望 root attribute：

- `request.id`
- `session.id`
- `source.app`
- `input.type = TEXT`
- `response.success`
- `response.text.length`

期望 redacted 模式下可见的内容类 attribute：

- `user.input`
- `prompt.chat_messages`
- `gen_ai.output`
- `tool.arguments`
- `tool.output`
- `memory.prompt`
- `memory.output`

### Task 14：更新 Trace 文档

**文件：**

- 修改：`docs/act_summary/trace-system-evaluation-report.md`
- 新增：`docs/act_summary/main-agent-trace-improvement-summary.md`

- [ ] **Step 1：更新评价报告状态**

增加 implementation follow-up，说明 text 主 Agent trace 已标准化为：

- `agent.request`
- `prompt.assembly`
- `gen_ai.chat`
- `tool.execute`
- `memory.extract`
- `memory.compress`

并明确 `IMAGE`、scene Agent、Camera、VR/TTS 仍是本 demo 阶段的 intentional out of scope。

- [ ] **Step 2：创建实施总结**

创建 `main-agent-trace-improvement-summary.md`，包含：

- Scope
- Trace Shape
- Safety Decisions
- Verification

- [ ] **Step 3：检查 git diff**

```powershell
git diff -- app/src/main/java/com/hirain/aiagent/trace app/src/main/java/com/hirain/aiagent/core app/src/main/java/com/hirain/aiagent/memory docs/act_summary
```

期望：只包含 trace 相关实现和文档变更。

---

## Commit 计划

使用小提交：

1. `fix(trace): make trace context optional and safe`
2. `feat(trace): add standardized trace attributes and redaction writer`
3. `feat(trace): record prompt llm and tool spans through recorder`
4. `feat(trace): add memory extraction and compression spans`
5. `docs(trace): summarize main agent trace coverage`

不要混入无关的 README / AGENTS / 虚拟状态机变更，除非用户明确要求。

## 验收标准

- Text 请求总是创建一个 root `agent.request` trace。
- 缺失 trace context 不会让 `AgentLoopOrchestrator` 崩溃。
- Prompt 组装可见：system/user/context messages 和 tool specs 都以长度受控的方式记录。
- LLM 调用记录模型名、provider、输出、tool calls、token usage、HTTP status 和错误状态。
- Tool 调用记录 name、arguments、safety veto、success 和 output。
- Memory extraction 和 compression LLM 调用可见，并归属于同一个 request trace。
- 敏感内容默认脱敏/截断。
- Trace 代码集中在 `trace/` helper 类中，而不是散落在业务代码里。
- 单元测试覆盖脱敏和消息格式化。
- Gradle 编译和聚焦 trace 测试通过；若存在无关历史失败，必须记录清楚。
