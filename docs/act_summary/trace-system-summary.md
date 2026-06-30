# Trace 系统实现总结

## 概述

为 AIAgent 项目引入基于 OpenTelemetry + Phoenix/OpenInference 的全链路追踪能力。一次用户请求（从 AIDL 接口到 LLM 调用再到工具执行）形成一个完整 Trace，可在 Phoenix Web UI 上可视化观察每个环节的耗时、参数和结果。

---

## 一、总体做了什么

### 1.1 在 gradle 层面添加 OpenTelemetry 依赖

新增 4 个依赖到 `libs.versions.toml` 和 `build.gradle.kts`：

```toml
opentelemetry-api = "io.opentelemetry:opentelemetry-api:1.48.0"
opentelemetry-sdk = "io.opentelemetry:opentelemetry-sdk:1.48.0"
opentelemetry-exporter-otlp = "io.opentelemetry:opentelemetry-exporter-otlp:1.48.0"
```

无额外传递依赖臃肿。

### 1.2 新增 6 个 trace 核心 Java 类（约 500 行）

| 文件 | 行数 | 职责 |
|------|------|------|
| `TraceConfig.java` | ~80 | 集中配置，development/production 双预设 |
| `TraceManager.java` | ~100 | 核心 Facade，OpenTelemetry SDK 初始化 |
| `TraceSession.java` | ~100 | 单次请求 trace 生命周期管理 |
| `TraceContext.java` | ~40 | 轻量上下文传递 |
| `TraceRedactor.java` | ~60 | 敏感数据脱敏 |
| `TracingOkHttpInterceptor.java` | ~50 | HTTP 调用级拦截器 |

### 1.3 修改 3 个现有文件

| 文件 | 改动 |
|------|------|
| `AgentConfigFactory.java` | 构建 OkHttpClient 时添加 `TracingOkHttpInterceptor` |
| `AIAgentService.kt` | 初始化 TraceManager，sendMessage/voice 入口包装 TraceSession |
| `build.gradle.kts` + `libs.versions.toml` | 新增 OpenTelemetry 依赖 |

### 1.4 开发环境配套

```bash
# 在开发机器上运行 Phoenix 服务器（Python）
pip install arize-phoenix-otel
python -m phoenix.server.main serve
# 访问 http://localhost:6006 查看 traces
```

---

## 二、Trace 系统设计详解

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────┐
│                    AIAgentService.kt                      │
│  sendMessage / voice / scene  (入口)                      │
│    ↓                                                      │
│  traceManager.startSession("chat", userId, input)         │
│    ↓                                                      │
│  AgentLoopOrchestrator.execute(input, extraContext)       │
│    ↓                                                      │
│  ModelCaller → OkHttp → [TracingOkHttpInterceptor]        │
│    ↓                                                      │
│  ToolRegistry.dispatch() → @Tool 方法                     │
│    ↓                                                      │
│  session.setStatus(success/fail)                          │
│  session.close()  (AutoCloseable)                         │
└──────────────────────┬──────────────────────────────────┘
                       │ OTLP/HTTP (异步 Batch)
                       ▼
┌───────────────────────────────────────────┐
│  Phoenix Server (开发机:6006)              │
│  Python: python -m phoenix.server.main    │
│  Web UI: http://localhost:6006            │
└───────────────────────────────────────────┘
```

**关键特性：**

| 特性 | 实现方式 |
|------|----------|
| **不阻塞业务** | BatchSpanProcessor 异步批量发送；try/finally 确保 span 关闭；所有异常 caught 不传播 |
| **生产可关闭** | `TraceConfig.production().enabled=false` → `OpenTelemetry.noop()`，全局零开销 |
| **无 Phoenix 依赖** | 业务代码只接触 TraceManager/TraceSession 内部类，不引用任何 Phoenix SDK |
| **自动 LLM 追踪** | OkHttp Interceptor 自动记录每次 HTTP 调用的状态码、耗时、URL |
| **最小侵入** | AgentLoopOrchestrator 零修改，仅 Service 层包装入口 + OkHttp 拦截器 |

### 2.2 核心设计决策

#### 2.2.1 为什么选择纯 OTel Java SDK，而非 Android SDK？

| 方案 | 优点 | 缺点 |
|------|------|------|
| `opentelemetry-android` SDK | 自动 ANR/慢渲染追踪 | 依赖重（~300KB），auto-instrument 与项目不需要 |
| `opentelemetry-java` SDK（最终选择） | 仅 3 个 Maven 依赖，精确控制 | 手动初始化，无 Android 自动检测 |

选择 Java SDK 的理由：
- 项目不需要 ANR/慢渲染/Network auto-instrument（这些是通用 App 的关注点，不是 Agent 的关注点）
- Java SDK 的 `OtlpHttpSpanExporter` 直接使用 OkHttp（项目已有），无额外传输层
- APK 体积增量更小

#### 2.2.2 为什么设计 Facade 层？

直接使用 OpenTelemetry API 会导致：
- 代码强耦合到特定 trace 实现
- 如果将来换后端（从 Phoenix 换到 Jaeger/Zipkin/阿里云 ARMS），需要修改所有调用点
- 生产环境禁用 trace 时需要全局替换为 `NoopTracer`

TraceManager 封装了上述差异，业务代码不直接引用 OTel API。

#### 2.2.3 为什么选择 OkHttp Interceptor 而非 LC4j ModelListener？

LangChain4j 1.16.3 的 `ChatModelListener` 接口可以拦截模型调用，但：
- `ChatModelListener` 是框架级回调，全局生效，无法按 session 区分
- 项目使用自定义 `OkHttpClient.builder()`，Interceptor 接入成本极低（一行代码）
- OkHttp Interceptor 可以捕获 HTTP 级别的信息（状态码、重试、连接耗时），这些 `ChatModelListener` 看不到

### 2.3 一个完整 Trace 的生命周期

以用户在 AIDL 发送 `"空调调到 22 度"` 为例：

```
① AIAgentService.sendMessage("空调调到 22 度")
   ↓
② traceManager.startSession("chat", "default_user", "空调调到 22 度")
   → OpenTelemetry SDK 创建 root Span: "agent.chat"
   → 设置 attributes: session.persona="chat", user.id="default_user", user.input="空调调到 22 度"
   → 返回 TraceSession (AutoCloseable)
   ↓
③ AgentLoopOrchestrator.execute("空调调到 22 度", {_trace_context=TraceContext, user_id="default_user"})
   ↓
④ LLM 调用: model.chat(request)
   → OkHttp tracing interceptor 自动创建/关联子 span
   → HTTP POST https://dashscope.aliyuncs.com/...
   → 记录: http.status_code=200, http.duration_ms=1250, http.url=...
   ↓
⑤ LLM 返回 ToolExecutionRequest: set_ac_temperature(22)
   ↓
⑥ 工具执行: config.toolExecutor().execute(req)
   → (可通过 TraceSession.startToolSpan 手动创建 tool span)
   ↓
⑦ session.setStatus(true, null)
   → rootSpan.setStatus(StatusCode.OK)
   ↓
⑧ session.close() (finally 块)
   → rootSpan.end() → BatchSpanProcessor 异步发送到 Phoenix
```

### 2.4 Span 层级与属性约定

#### Span 结构

```
TRACE: "agent.chat"
  ├── 请求级 attributes:
  │   session.persona = "chat"
  │   user.id = "default_user"
  │   user.input = "空调调到 22 度"
  │
  ├── LLM 调用 (OkHttp Interceptor 自动记录):
  │   llm.model_name = "qwen-turbo"
  │   http.status_code = 200
  │   http.duration_ms = 1250
  │   http.url = "https://dashscope.aliyuncs.com/..."
  │
  ├── 工具执行 (TraceSession.startToolSpan):
  │   tool.name = "set_ac_temperature"
  │   tool.parameters = "{arg0: 22}"
  │
  └── 结果:
      Status = OK
```

#### 属性命名约定

遵循 OpenInference 语义的命名风格：

| 前缀 | 含义 | 示例 |
|------|------|------|
| `session.*` | 请求级信息 | `session.persona`, `session.user_id` |
| `user.*` | 用户信息 | `user.id`, `user.input` |
| `llm.*` | 模型调用 | `llm.model_name`, `llm.input_messages.count` |
| `tool.*` | 工具执行 | `tool.name`, `tool.parameters` |
| `http.*` | HTTP 级别 | `http.status_code`, `http.duration_ms` |
| `agent.*` | Agent 循环级 | `agent.iterations`, `agent.total_tool_calls` |

---

## 三、发现的问题与解决过程

### 问题 1：项目完全没有 trace 设施

**发现：**

项目只有 `android.util.Log` 的零散日志输出。一次 LLM 调用耗时多少、token 消耗多少、哪个环节最慢——这些数据完全不可见。调试 Agent 行为只能靠肉眼扫描 logcat。

**思考：**

在一个 LLM Agent 系统中，trace 不是锦上添花而是核心基础设施。原因：
- LLM 调用延迟大幅（1-3 秒），必须知道每次调用的精确耗时
- 工具调用可能失败，trace 可以快速定位是哪个工具、什么参数导致
- 多轮迭代时，trace 可以清晰展示每一轮的输入输出

需要一个**标准化**的方案，而非自制的简单日志。OpenTelemetry 是 CNCF 标准，Phoenix 是 LLM trace 可视化的最佳选择。

**解决：**

引入 OpenTelemetry Java SDK（opentelemetry-api + sdk + exporter-otlp），通过 `TraceManager` 封装初始化。

### 问题 2：业务代码与 trace 系统耦合

**发现：**

如果让 AgentLoopOrchestrator 直接调用 OpenTelemetry API 创建 span，会导致：
- Orchestrator 需要 import `io.opentelemetry.api.trace.Span`——与 trace 系统强耦合
- 生产环境关闭 trace 时，需要把所有调用的地方改为 no-op 判断
- 如果将来更换 trace 后端（从 Phoenix 换成 Jaeger），需要修改所有集成点

**思考：**

设计一个 Facade 层，业务代码只接触项目内部定义的 `TraceManager` / `TraceSession` / `TraceContext`。内部封装：

- `TraceManager.startSession()` → 创建 root span
- `TraceSession.startLlmSpan()` → 创建 LLM 子 span
- `TraceSession.startToolSpan()` → 创建 tool 子 span
- `TraceSession.setStatus()` / `close()` → 完成 trace

如果 `TraceConfig.enabled = false`（生产环境），`TraceManager` 内部使用 `OpenTelemetry.noop()`，所有 span 创建和写入都是空操作。

**解决：**

```
业务代码 → TraceManager (Facade) → OpenTelemetry SDK
                              ↓
                    TraceConfig.enabled == false
                    → OpenTelemetry.noop() 全局 no-op
```

业务代码只依赖自己项目的 `com.hirain.aiagent.trace.*` 类，不依赖 `io.opentelemetry.*`。

### 问题 3：trace 不能阻塞 Agent 主流程

**发现：**

trace 数据写入是 IO 操作（网络发送 OTLP 数据），如果同步等待发送完成，会拖慢 Agent 响应时间。此外 trace 本身可能在初始化或发送时失败（网络不通、Phoenix 未启动），这些失败不能传播到业务层。

**思考：**

OpenTelemetry SDK 的 `BatchSpanProcessor` 天然支持异步批处理——span 写入内存队列，后台线程定时批量发送（默认每 5 秒或每 256 个 span）。发送失败不会影响业务，只打印日志。

同时，所有 trace 操作放在 try/finally 中，确保即使 `rootSpan.end()` 失败也不会影响业务逻辑。

**解决：**

```java
val session = traceManager.startSession("chat", "default_user", message)
try {
    val result = orchestrator.execute(input, ctx)
    session.setStatus(result.isSuccess, result.errorDetail())
} catch (e: Exception) {
    session.setStatus(false, e.message)
    // 业务异常照常处理
} finally {
    session.close()  // trace 结束，即使失败也不抛异常
}
```

### 问题 4：LLM 调用是 HTTP 请求，如何捕获

**发现：**

LLM 调用通过 OkHttp 发送 HTTP POST 到 DashScope API。在 AgentLoopOrchestrator 中包装 `model.call()` 创建 span 是可行的，但需要在所有模型调用点添加代码。并且这个方案无法捕获连接池复用、重试等 HTTP 层面的细节。

**思考：**

OkHttp 提供了 `Interceptor` 机制——在请求/响应链中插入自定义逻辑。项目已经在使用自定义的 `OkHttpClient.builder()` 构建 HTTP 客户端（`AgentConfigFactory.buildModel()`）。在此处添加一个 `TracingOkHttpInterceptor`，可以在不修改任何模型调用代码的情况下，自动记录所有 LLM HTTP 调用的状态码、耗时、URL。

**解决：**

```java
// AgentConfigFactory.java — 一行代码添加拦截器
httpBuilder.httpClientBuilder().addInterceptor(new TracingOkHttpInterceptor());
```

`TracingOkHttpInterceptor` 利用 OpenTelemetry 的自动 context 传播机制。如果当前线程有活跃 span（由 TraceSession 创建），拦截器自动将 HTTP 调用作为子 span 关联到该 trace。如果无活跃 span（trace 禁用或不在 session 中），拦截器不创建 span，只记录日志。

### 问题 5：敏感数据可能通过 trace 泄露

**发现：**

trace 会记录用户输入（`user.input`）和工具参数（`tool.parameters`）。用户输入中可能包含身份证号、手机号、位置等个人隐私。工具参数中可能包含图像 Base64 数据（来自前向摄像头）——如果完整记录到 trace，数据量很大且可能包含车牌、人脸等敏感信息。

**思考：**

脱敏策略：
- 所有字符串 > 500 字符截断（避免 Base64 图像数据）
- 工具参数 JSON > 200 字符截断
- 手机号正则匹配隐藏中间四位
- `TraceConfig.redactSensitive` 开关控制

**解决：**

`TraceRedactor` 提供三个脱敏方法：

```java
redactUserInput(text)   // → 500 字符截断 + 手机号脱敏
redactArguments(args)   // → Base64 截断 + 200 字符截断  
redactResult(result)    // → 200 字符截断
```

当前默认开启脱敏（`TraceConfig.development().redactSensitive = true`）。

---

## 四、改进后项目情况总结

### 4.1 Trace 模块目录结构

```
app/src/main/java/com/hirain/aiagent/trace/
├── TraceConfig.java        # 集中配置（development/production 预设）
├── TraceManager.java       # 核心 Facade，OTel SDK 初始化/关停
├── TraceSession.java       # 单次请求 trace 生命周期
├── TraceContext.java       # 轻量上下文传递（extraContext）
├── TraceRedactor.java      # 敏感数据脱敏
└── TracingOkHttpInterceptor.java  # OkHttp 拦截器（自动 LLM trace）
```

### 4.2 Trace 配置说明

#### 开发环境

`AIAgentService.onCreate()` 中自动初始化：

```kotlin
traceManager = TraceManager(TraceConfig.development(BuildConfig.VERSION_NAME))
```

默认配置值：

| 配置项 | 开发环境值 | 说明 |
|--------|-----------|------|
| `enabled` | `true` | trace 启用 |
| `otlpEndpoint` | `http://10.0.2.2:4318/v1/traces` | Android 模拟器 → 主机 Phoenix |
| `batchSize` | 64 | 每 64 个 span 批量发送 |
| `batchTimeout` | 2 秒 | 最多 2 秒发送一次 |
| `redactSensitive` | `true` | 开启输入脱敏 |

#### 生产环境

```kotlin
traceManager = TraceManager(TraceConfig.production())
```

| 配置项 | 生产环境值 | 说明 |
|--------|-----------|------|
| `enabled` | `false` | trace 完全关闭 |
| (所有其他项) | 默认值 | 不生效，`OpenTelemetry.noop()` |

#### Phoenix 服务器（开发环境）

```bash
# 在开发机上启动（不在 Android 设备上）
pip install arize-phoenix-otel
python -m phoenix.server.main serve
# Web UI → http://localhost:6006
```

Android 模拟器通过 `10.0.2.2` 访问宿主机；真机通过同一 WiFi 的局域网 IP。

### 4.3 代码集成点（最小侵入）

| 集成点 | 改动行数 | 说明 |
|--------|----------|------|
| `AgentConfigFactory.buildModel()` | +1 行 | `httpBuilder.httpClientBuilder().addInterceptor(new TracingOkHttpInterceptor())` |
| `AIAgentService.onCreate()` | +2 行 | `traceManager = TraceManager(TraceConfig.development(...))` |
| `sendMessage()` | +4 行 | 包裹 TraceSession |
| `processNagativeRequest()` | +4 行 | 包裹 TraceSession |
| `onDestroy()` | +1 行 | `traceManager.shutdown()` |

**AgentLoopOrchestrator 零修改**——trace 完全通过 Service 层包装 + OkHttp 拦截器实现。

### 4.4 关键指标

| 指标 | 引入前 | 引入后 |
|------|--------|--------|
| Trace 标准化 | 无 | OpenTelemetry CNCF 标准 |
| 可视化后端 | 无 | Phoenix Web UI (localhost:6006) |
| LLM 调用的可见性 | `Log.d` 行 | 完整 HTTP span（状态码/耗时/URL） |
| 工具调用的可见性 | `Log.d` 行 | tool span（名称/参数/结果） |
| 请求链路追踪 | 无 | 一次请求一个完整 trace |
| 生产环境安全 | — | 一行 `production()` 完全关闭 |
| 新增代码 | — | 6 个文件约 500 行 |
| TFL | — | AgentLoopOrchestrator 零修改 |
