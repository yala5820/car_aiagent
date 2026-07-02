# Trace 系统实现总结

## 概述

为 AIAgent 项目引入基于 OpenTelemetry + Phoenix/OpenInference 的全链路追踪能力。一次用户请求（从 AIDL 接口到 LLM 调用再到工具执行）形成一个完整 Trace，可在 Phoenix Web UI 上可视化观察 Agent 的思考过程和工具调用链路。

---

## 一、总体做了什么

### 1.1 基础 trace 层搭建

**依赖层**：在 `libs.versions.toml` 和 `build.gradle.kts` 中新增 3 个 OpenTelemetry 依赖：

```toml
opentelemetry-api = "io.opentelemetry:opentelemetry-api:1.48.0"
opentelemetry-sdk = "io.opentelemetry:opentelemetry-sdk:1.48.0"
opentelemetry-exporter-otlp = "io.opentelemetry:opentelemetry-exporter-otlp:1.48.0"
```

**核心类层**：新建 `trace/` 包，6 个 Java 类（约 500 行）：

| 文件 | 职责 |
|------|------|
| `TraceConfig.java` | 集中配置，development/production 双预设 |
| `TraceManager.java` | Facade，OpenTelemetry SDK 初始化/关停 |
| `TraceSession.java` | 单次请求 trace 生命周期，子 span 创建 |
| `TraceContext.java` | 轻量上下文传递（traceId + sessionId） |
| `TraceRedactor.java` | 敏感数据脱敏（截断 Base64、手机号） |
| `TracingOkHttpInterceptor.java` | OkHttp 拦截器，记录 HTTP 状态码/耗时 |

**集成层**：修改 3 个现有文件：

| 文件 | 改动 |
|------|------|
| `AgentConfigFactory.java` | OkHttpClient 添加 TracingOkHttpInterceptor |
| `AIAgentService.kt` | 初始化 TraceManager，sendMessage/voice 入口包裹 TraceSession |
| `AgentLoopOrchestrator.java` | LLM 调用和工具执行处创建子 span（startLlmSpan/startToolSpan） |

### 1.2 多轮问题修复

**Round 1**：子 span 无 `makeCurrent()`，http 属性挂到根 span
- 根因：`startLlmSpan` 创建子 span 后未调用 `makeCurrent()`，`Span.current()` 始终返回根 span
- 修复：LLM 和 tool 子 span 创建后立即 `makeCurrent()`，结束时先 `scope.close()` 再 `span.end()`

**Round 2**：span 属性过于简陋
- 根因：LLM span 只记录了 `model_name` 和 `input_messages.count`，无输出/无 token 用量
- 修复：在 LLM 调用返回后、span.end() 前，从 `ChatResponse` 中提取 output text、tool_calls、token_count 写入 span；工具执行后提取 tool.output 写入 tool span

**Round 3**：tracing 只能用在 sendMessage / voice，缺少 sendMessageWithImage
- 根因：初始只在两个入口包裹了 TraceSession
- 修复：sendMessageWithImage 入口同样包裹 TraceSession

---

## 二、Trace 系统设计详解

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────┐
│                    AIAgentService.kt                      │
│  sendMessage / voice  (入口)                              │
│    ↓                                                      │
│  traceManager.startSession("chat", userId, input)         │
│    ↓ rootSpan.makeCurrent()                               │
│  AgentLoopOrchestrator.execute(input, extraContext)        │
│    │                                                       │
│    ├── llmSpan = session.startLlmSpan("qwen", count)      │
│    │   llmSpan.makeCurrent() ← 子 span 设为当前           │
│    │   ↓                                                   │
│    │   ModelCaller → OkHttp → TracingOkHttpInterceptor    │
│    │   │   → Span.current() = llmSpan ✅                  │
│    │   │   → http.* 属性写入 llmSpan                      │
│    │   ↓                                                   │
│    │   补充属性: llm.output_messages.content              │
│    │   补充属性: llm.token_count.*                        │
│    │   scope.close() + span.end()                         │
│    │                                                       │
│    ├── toolSpan = session.startToolSpan(name, args)       │
│    │   toolSpan.makeCurrent() ← 子 span 改为当前          │
│    │   ↓                                                   │
│    │   ToolExecutor → @Tool 方法                           │
│    │   ↓                                                   │
│    │   补充属性: tool.output                               │
│    │   scope.close() + span.end()                          │
│    │                                                       │
│    └── session.setStatus(success/fail)                     │
│        session.close() → scope.close() + rootSpan.end()   │
└──────────────────────┬──────────────────────────────────┘
                       │ OTLP/HTTP 异步 Batch 发送
                       ▼
┌───────────────────────────────────────────┐
│  Phoenix Server (开发机:6006)              │
│  Python: python -m phoenix.server.main    │
│  Web UI: http://localhost:6006            │
└───────────────────────────────────────────┘
```

### 2.2 Span 层级结构

一次完整的工具调用流程产生的 trace：

```
Trace: "agent.chat"
│
├── 根 span 属性:
│   ├── session.persona = "chat"
│   ├── user.id = "default_user"
│   └── user.input = "[角色:助理] 今天的天气怎么样？"
│
├── ====== 第 1 次迭代 ======
│
├── llm.call (#1)
│   ├── llm.model_name = "qwen-turbo"
│   ├── llm.input_messages.count = 12
│   ├── llm.output_messages.tool_calls = "get_weather_forecast"  ← LLM 决定调工具
│   ├── llm.token_count.prompt = 1250
│   ├── llm.token_count.completion = 80
│   ├── llm.token_count.total = 1330
│   ├── http.url = "https://dashscope.aliyuncs.com/..."
│   ├── http.method = "POST"
│   ├── http.duration_ms = 1407
│   └── http.status_code = 200
│
├── tool.execute
│   ├── tool.name = "get_weather_forecast"
│   ├── tool.parameters = "{city: '北京'}"
│   └── tool.output = "今天多云，气温22-28℃"
│
├── ====== 第 2 次迭代（工具结果回填后）=====
│
├── llm.call (#2)
│   ├── llm.input_messages.count = 18            ← 多了工具结果
│   ├── llm.output_messages.content = "北京今天多云..."  ← 最终回复
│   ├── llm.token_count.total = 480
│   ├── http.duration_ms = 612
│   └── http.status_code = 200
│
└── status: OK
```

### 2.3 Span 属性字典

**LLM 调用 span（`llm.call`）**：

| 属性 | 来源 | 示例 |
|------|------|------|
| `llm.model_name` | 代码传入 | `"qwen-turbo"` |
| `llm.input_messages.count` | 请求消息数 | `12` |
| `llm.output_messages.content` | `response.aiMessage().text()`（前 500 字） | `"北京今天多云..."` |
| `llm.output_messages.tool_calls` | `toolExecutionRequests().name()` 拼接 | `"get_weather_forecast"` |
| `llm.token_count.prompt` | `response.tokenUsage().inputTokenCount()` | `1250` |
| `llm.token_count.completion` | `response.tokenUsage().outputTokenCount()` | `80` |
| `llm.token_count.total` | `response.tokenUsage().totalTokenCount()` | `1330` |
| `http.status_code` | OkHttp 拦截器 | `200` |
| `http.duration_ms` | OkHttp 拦截器 | `1407` |
| `http.url` | OkHttp 拦截器 | `https://dashscope...` |
| `http.method` | OkHttp 拦截器 | `POST` |

**工具执行 span（`tool.execute`）**：

| 属性 | 来源 | 示例 |
|------|------|------|
| `tool.name` | `request.name()` | `"set_ac_temperature"` |
| `tool.parameters` | `request.arguments()`（前 200 字） | `"{arg0: 25}"` |
| `tool.output` | 工具方法返回值（前 300 字） | `"空调温度已设置为 25 度"` |

### 2.4 关键设计决策

#### SDK 选型：纯 OTel Java SDK vs Android SDK

| 方案 | 优点 | 缺点 |
|------|------|------|
| `opentelemetry-android` SDK | 自动 ANR/慢渲染追踪 | 依赖重（~300KB），auto-instrument 与项目不需要 |
| `opentelemetry-java` SDK（最终选择） | 仅 3 个依赖，精确控制 | 手动初始化 |

#### Facade 封装：为什么业务代码不直接调 OpenTelemetry？

- 如果将来换后端（Phoenix → Jaeger → 阿里云 ARMS），只需改 TraceManager 内部实现
- 生产环境关闭 trace 时，`TraceConfig.production().enabled=false` 全局 no-op
- AgentLoopOrchestrator 只依赖 `TraceSession` 的 `startLlmSpan/startToolSpan` 两个方法

#### Span 父子关系：为什么用 makeCurrent 而非显式 setParent？

第一次实现用 `setParent(SpanContext)` 但 OpenTelemetry 1.48 API 要求 `Context` 参数。改用 `makeCurrent()` 后：

- 子 span 创建时自动继承当前线程的活跃 span 作为父 span
- `TraceSession` 构造时 `rootSpan.makeCurrent()` → 根 span 成为默认 current
- 每个 `llmSpan`/`toolSpan` 创建后 `makeCurrent()` → LLM 调用期间当前 span 切换为子 span
- 子操作结束后 `scope.close()` → 当前 span 恢复为根 span

#### Span 生命周期（展示修复后的正确流程）

```
TraceSession 构造 → rootSpan.makeCurrent() → scope_根 为 current

┌─ 迭代 0 ──────────────────────────────────────────────────┐
│ llmSpan 创建 → llmSpan.makeCurrent() → scope_llm 为 current │
│   → LLM 调用 → Span.current() = llmSpan ✅ (OkHttp 拦截器) │
│   → 补充 output/token 属性                                │
│ scope_llm.close() → 恢复 scope_根                          │
│ llmSpan.end()                                              │
│                                                            │
│ LLM 返回 ToolCall → 进入工具执行                           │
│                                                            │
│ toolSpan 创建 → toolSpan.makeCurrent() → scope_tool current│
│   → 工具执行 → 补充 tool.output 属性                      │
│ scope_tool.close() → 恢复 scope_根                         │
│ toolSpan.end()                                             │
└────────────────────────────────────────────────────────────┘

┌─ 迭代 1 ──────────────────────────────────────────────────┐
│ llmSpan 创建 → makeCurrent → LLM 调用 → 补充属性 → end    │
│ LLM 返回文本 → PostProcessor → LoopTerminator → return    │
└────────────────────────────────────────────────────────────┘

session.close() → scope_根.close() + rootSpan.end()
```

### 2.5 关键特性

| 特性 | 实现方式 |
|------|----------|
| **不阻塞业务** | BatchSpanProcessor 异步批量发送 + try-finally 保证 span 关闭 |
| **生产可关闭** | `TraceConfig.production()` → `OpenTelemetry.noop()` 全局零开销 |
| **无 Phoenix 依赖** | 业务代码只接触 `TraceManager/TraceSession` 内部类 |
| **LLM 全链路** | LLM span 携带输入/输出/token/http 所有关键信息 |
| **工具全链路** | Tool span 携带工具名/参数/返回结果 |
| **最小侵入** | AgentLoopOrchestrator 只增加约 20 行 span 包裹代码 |
