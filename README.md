# AIAgent — AI 智能座舱后台服务引擎

## 1. 项目概述

AIAgent 是运行于 Android 车机系统上的 **AI 语音助手的后台引擎**。它是一个持续运行在后台的**前台 Service**，本身**无任何 UI**，对外通过 AIDL（`IAIAgentAidlInterface`）暴露 LLM 对话能力，供 Launcher 或其他 App 调用。

**核心业务：** 利用大语言模型（LLM）和多模态视觉模型（VLM），为驾驶员提供自然语言对话、车辆控制、场景感知、前向视野问答等智能座舱能力。

**关键特征：**
- 纯后台 Service，无 UI / 无悬浮窗
- 对外暴露 AIDL 接口（`sendMessage` / `sendMessageWithImage` / `requestAI`），通过 AIDL Binder 供 Launcher 调用
- 使用阿里云 DashScope 的 OpenAI 兼容 API（`qwen-turbo` / `qwen-flash` / `qwen-vl-max`）
- 基于 LangChain4j 1.16.3 的 Tool Calling 机制实现"LLM + 车控"联动
- 集中式反射工具调度（ToolRegistry + ToolDispatcher），消除样板代码
- 组件化 Agent 循环（AgentLoopOrchestrator），7 个可插拔接口
- 外部化 Prompt 管理（assets/prompts/ + PromptTemplate），动态切换
- 四层记忆系统（Session / 长期记忆 / 压缩 / 提取）
- 全链路追踪（OpenTelemetry + Phoenix）

### 改造历史

| 阶段 | 说明 |
|------|------|
| **Phase 1** | 16 个 Gradle 模块合并为单一 `app/` 模块，移除 UI 悬浮窗组件 |
| **Phase 2** | 工具系统重构：ToolDispatcher + ToolRegistry，消除 450 行样板代码，修复 5 个 Bug |
| **Phase 3** | Prompt 规范化：9 个 `.txt` 模板文件替代 150 行硬编码，PromptManager + PromptSelector |
| **Phase 4** | Agent 主循环统一：AgentLoopOrchestrator + 7 组件接口 + SafetyGuard |
| **Phase 5** | 记忆系统：四层架构（Session/长期记忆/压缩/提取），3 张 SQLite 表 |
| **Phase 6** | Trace 系统：OpenTelemetry + Phoenix 全链路追踪 |
| **Phase 7** | AgentLoop Bug 修复：状态机重置、长期记忆刷新、onTurnComplete 去重 |

---

## 2. 项目架构与技术栈

### 架构分层

```
┌─────────────────────────────────────────────────────────────┐
│                   调用方（外部应用）                          │
│  ┌───────────┐  ┌──────────┐                               │
│  │ Launcher  │  │ SystemUI │  ...                          │
│  │ (UI 主界面)│  │ (状态栏)  │                               │
│  └─────┬─────┘  └────┬─────┘                               │
│        │ AIDL        │ AIDL                                │
├────────┼─────────────┼─────────────────────────────────────┤
│        ▼             ▼                                      │
│  ┌─────────────────────────────────────────────┐            │
│  │         AIAgentService（前台 Service）        │            │
│  │  IAIAgentAidlInterface.Stub (AIDL Binder)   │            │
│  │  ├─ sendMessage(String)                      │            │
│  │  ├─ sendMessageWithImage(String, String)     │            │
│  │  ├─ requestAI(String)  ← VR 语音协议        │            │
│  │  └─ registerListener / unregisterListener    │            │
│  └──────────────────┬──────────────────────────┘            │
│                     │                                       │
│                     ▼                                       │
│  ┌─────────────────────────────────────────────┐            │
│  │           AgentLoopOrchestrator               │            │
│  │  统一 Agent 循环引擎，7 组件管线装配            │            │
│  │  PreProcessor → ModelCaller → SafetyGuard    │            │
│  │  → ToolExecutor → PostProcessor → Terminator │            │
│  │  → ResultCollector                           │            │
│  ├─────────────────────────────────────────────┤            │
│  │           Tool 调用层（集中式反射调度）         │            │
│  │  ToolRegistry (Map<String, ToolDispatcher>)   │            │
│  │  ToolDispatcher (反射扫描 @Tool 方法)         │            │
│  │  Vehicle*Manager | WeatherUtils | VlManager  │            │
│  ├─────────────────────────────────────────────┤            │
│  │           Prompt 管理                         │            │
│  │  assets/prompts/ (.txt 模板)                  │            │
│  │  PromptManager (加载+缓存+渲染)               │            │
│  │  PromptSelector (动态切换策略)                │            │
│  ├─────────────────────────────────────────────┤            │
│  │           记忆系统（四层架构）                  │            │
│  │  SessionManager → 会话生命周期                │            │
│  │  LongTermMemory → 用户偏好持久化              │            │
│  │  MemoryCompressor → Token 超限自动摘要        │            │
│  │  MemoryExtractor → 对话中提取可记忆信息        │            │
│  ├─────────────────────────────────────────────┤            │
│  │           硬件通信层                           │            │
│  │  SoaService (SOA总线 AIDL) | CameraSdk.jar   │            │
│  │  adapter_vr.jar (VR/TTS)                     │            │
│  └─────────────────────────────────────────────┘            │
└─────────────────────────────────────────────────────────────┘
```

### 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin + Java 混编 |
| AI 框架 | LangChain4j 1.16.3 |
| LLM 模型 | qwen-turbo（对话）、qwen-flash（场景）、qwen-vl-max（视觉问答） |
| LLM API | 阿里云 DashScope（OpenAI 兼容接口） |
| 通信 | AIDL（Launcher ↔ AIAgent、SOA 总线、Camera） |
| UI | **无**（纯后台 Service） |
| 持久化 | SQLite（ChatMemory 持久化 + 长期记忆 + Session 管理） |
| 网络 | OkHttp 4.12 |
| Trace | OpenTelemetry 1.48.0 + Phoenix（开发调试用） |
| VR/TTS | adapter_vr.jar（闭源） |
| 构建 | Gradle 8.11+ / AGP 8.9.1 / Version Catalog |

---

## 3. 项目目录结构

```
AIAgent/
├── app/                                    # 唯一模块
│   ├── src/main/
│   │   ├── AndroidManifest.xml             # Service + Receiver + Launcher Activity
│   │   ├── assets/prompts/                 # Prompt 模板文件（9 个 .txt）
│   │   │   ├── system/                     # 系统提示词
│   │   │   ├── task/                       # 任务提示词（场景识别、视觉问答）
│   │   │   ├── user/                       # 用户消息模板
│   │   │   └── messages/                   # 消息片段
│   │   ├── java/com/hirain/aiagent/
│   │   │   ├── AIAgentService.kt           # 前台 Service，AIDL Binder 实现
│   │   │   ├── AIAgent.java                # Facade 单例（客户端使用，JAR 中）
│   │   │   ├── AIAgentData.java            # Parcelable 数据类
│   │   │   ├── IAIAgentServiceListener.java # 本地回调接口
│   │   │   ├── MainActivity.kt             # Launcher Activity（仅用于调试启动）
│   │   │   ├── BootCompleteReceiver.kt     # 开机广播 → 启动 Service
│   │   │   │
│   │   │   ├── ai/langchain4j/tool/        # 工具调度系统
│   │   │   │   ├── ToolDispatcher.java     # 反射工具执行器
│   │   │   │   └── ToolRegistry.java       # 注册中心
│   │   │   │
│   │   │   ├── core/                       # Agent 循环引擎
│   │   │   │   ├── AgentLoopOrchestrator.java  # 主循环引擎
│   │   │   │   ├── AgentConfig.java            # 人格配置 + Builder
│   │   │   │   ├── AgentLoopState.java         # 状态跟踪
│   │   │   │   ├── AgentLoopContext.java       # 执行上下文
│   │   │   │   ├── AgentResult.java            # 结构化结果
│   │   │   │   ├── SafetyVerdict.java          # 安全审查结果
│   │   │   │   ├── ToolExecutionRecord.java    # 工具执行快照
│   │   │   │   ├── component/                  # 7 个可插拔接口
│   │   │   │   ├── preprocessor/               # PreProcessor 实现
│   │   │   │   ├── model/                      # 模型调用器
│   │   │   │   ├── safety/                     # 安全审查实现
│   │   │   │   ├── postprocessor/              # 后处理器实现
│   │   │   │   ├── terminator/                 # 循环终止器
│   │   │   │   ├── collector/                  # 结果收集器
│   │   │   │   └── factory/                    # 人格工厂
│   │   │   │
│   │   │   ├── memory/                     # 记忆系统
│   │   │   │   ├── MemoryOrchestrator.java  # 协调器
│   │   │   │   ├── SessionManager.java      # Session 生命周期
│   │   │   │   ├── SessionMemoryStore.java  # Session 持久化
│   │   │   │   ├── UserMemoryContext.java   # 用户记忆聚合
│   │   │   │   ├── LongTermMemoryStore.java # 长期记忆
│   │   │   │   ├── MemoryEntry.java         # 记忆条目
│   │   │   │   ├── MemoryCompressor.java    # 自动压缩
│   │   │   │   ├── MemoryExtractor.java     # 记忆提取
│   │   │   │   └── MemoryCandidate.java     # 提取候选项
│   │   │   │
│   │   │   ├── prompt/                      # Prompt 管理
│   │   │   │   ├── PromptManager.java       # 模板加载+渲染
│   │   │   │   ├── PromptConstants.java     # 模板名常量
│   │   │   │   └── PromptSelector.java      # 动态选择接口
│   │   │   │
│   │   │   ├── trace/                       # Trace 系统
│   │   │   │   ├── TraceManager.java        # Facade
│   │   │   │   ├── TraceSession.java        # 单次请求 trace
│   │   │   │   ├── TraceContext.java        # 上下文传递
│   │   │   │   ├── TraceConfig.java         # 配置
│   │   │   │   ├── TraceRedactor.java       # 脱敏
│   │   │   │   └── TracingOkHttpInterceptor.java
│   │   │   │
│   │   │   ├── tools/                       # 工具层（@Tool 声明）
│   │   │   │   ├── external/weather/WeatherUtils.java
│   │   │   │   ├── vehicle/                 # 车控 6 个 Manager
│   │   │   │   └── vision/vl/VlManager.java # 视觉问答
│   │   │   │
│   │   │   └── infra/                       # 基础设施
│   │   │       ├── soa/SoaService.kt
│   │   │       └── geo/GeoUtils.java
│   │   │
│   │   └── libs/
│   │       ├── CameraSdk.jar                # CameraService AIDL
│   │       ├── AIAgentSdk.jar               # 对外 SDK
│   │       └── adapter_vr.jar               # VR/TTS
│   │
│   ├── langchain4j/                         # LangChain4j 适配层
│   │   ├── chat_memory_sqlite/              # SQLite 记忆持久化
│   │   └── http_client_ok/                  # OkHttp HTTP 客户端
│   │
│   ├── build.gradle.kts
│   └── proguard-rules.pro
│
├── gradle/libs.versions.toml                # 版本目录
├── local.properties                         # 本地配置（API Key 等）
├── local.properties.example                 # 配置模板
├── .gitignore / .gitattributes
└── docs/                                    # 文档
    ├── act_summary/                         # 阶段总结文档
    ├── plan/                                # 计划方案
    ├── errors/                              # 编译错误记录
    └── testresult/                          # 测试结果
```

---

## 4. 核心组件

### 4.1 AIAgentService（主控中枢 - AIDL 服务端）

**文件：** `AIAgentService.kt`

前台 Service，职责包括：

- **AIDL Binder 实现**：`sendMessage` / `sendMessageWithImage` / `requestAI` / `registerListener`
- **Camera 接入**：每 1 秒请求一次前向摄像头抓拍
- **场景识别循环**：抓拍 → SceneMatch 识别 → 场景变化 → AgentLoopOrchestrator 主动响应
- **Listener 回调推送**：AIDL 跨进程回调结果
- **15 秒超时保护**

### 4.2 AgentLoopOrchestrator（统一 Agent 循环引擎）

**文件：** `core/AgentLoopOrchestrator.java`

唯一的 Agent 执行入口。通过 `AgentConfig` 配置驱动不同"人格"（chat / scene / vision_qa）：

```
execute(userInput, extraContext)
  → 注入 SystemPrompt（+长期记忆）
  → for i in 0..maxIterations:
      ① PreProcessor 链 → 瞬时上下文（车辆状态、时间、场景、长期记忆）
      ② ModelCaller → LLM 调用（含子 span 追踪）
      ③ LLM 返回 ToolCall → SafetyGuard → ToolExecutor → 回填 → continue
      ④ LLM 返回文本 → PostProcessor → LoopTerminator → ResultCollector → return
  → max iterations → AgentResult.error()
```

**7 个组件接口**全部可插拔替换，`AgentConfigFactory` 提供三个预设人格。

### 4.3 ToolRegistry + ToolDispatcher（集中式工具调度）

**文件：** `ai/langchain4j/tool/`

替代了原 10 个 Manager 中 450 行重复的 `hasTool`/`handleToolRequest` 样板代码：

- `ToolDispatcher`：构造时反射扫描目标对象的所有 `@Tool` 方法，建立 工具名→Method 映射
- `ToolRegistry`：管理多个 Dispatcher，`registerAll()` 注册，`dispatch()` 路由
- 工具名直接来自 `@Tool(name=...)` 注解，不在源码中手写字符串匹配

### 4.4 PromptManager（外部化 Prompt 管理）

**文件：** `prompt/PromptManager.java` + `assets/prompts/`

所有 Prompt 文本外置为 `.txt` 模板文件，使用 LangChain4j `{{variable}}` 语法：
- 9 个模板文件，按 system / task / user / messages 分类
- `PromptManager` 懒加载 + 缓存 + 渲染
- `PromptSelector` 预留动态切换接口

### 4.5 MemoryOrchestrator（四层记忆系统）

**文件：** `memory/`

| 层 | 组件 | 职责 |
|----|------|------|
| Session 管理 | SessionManager + SessionMemoryStore | 程序启闭 = 一次会话，主动指令 = 新会话 |
| 长期记忆 | LongTermMemoryStore + MemoryExtractor | 跨 Session 持久化用户偏好/事实（SQLite） |
| 自动压缩 | MemoryCompressor | Token > 4000 时 LLM 摘要旧消息 |
| 用户隔离 | UserMemoryContext | userId 维度隔离，多用户支持 |

### 4.6 TraceManager（全链路追踪）

**文件：** `trace/`

基于 OpenTelemetry + Phoenix 的追踪系统：
- 一次请求一个完整 Trace，含 `llm.call` + `tool.execute` 子 span
- Span 携带：模型名、输入/输出、Token 用量、HTTP 状态码、工具参数/结果
- 开发环境通过 `adb reverse tcp:6006 tcp:6006` 连接 PC 端 Phoenix
- `TraceConfig.production()` 一键关闭（全局 no-op）

### 4.7 Vehicle*Manager 系列（车控工具）

6 个模块，共约 **40+ 个 @Tool 方法**，全部使用 `ToolRegistry` 统一调度：

| 模块 | 工具数 | 覆盖功能 |
|------|-------|---------|
| VehicleDoorManager | 1 | 车门闭锁/解锁 |
| VehicleWindowManager | 11 | 车窗、天窗、遮阳帘、除霜、后视镜加热 |
| VehicleSeatManager | 11 | 座椅加热、通风、按摩、方向盘加热 |
| VehicleAcManager | 15 | 空调开关、温度、风量、ECO、负离子、内外循环 |
| VehicleChassisManager | 1 | 底盘模式（普通/越野/雪地） |
| VehicleFragManager | 2 | 香氛类型、浓度 |

### 4.8 SoaService（SOA 总线封装）

**文件：** `infra/soa/SoaService.kt`

**所有与硬件通信的方法体均为空**（仅 `Log.d` 日志输出），这是当前最大的功能性断点。

---

## 5. 初始化流程与运行流程

### 5.1 启动入口

```
Launcher 点击图标 / 系统开机广播
    │
    ▼
MainActivity.onCreate()
    → startForegroundService(AIAgentService::class.java)
    → finish()
    │
    ▼
AIAgentService.onCreate()
    ├─ 前台服务通知渠道
    ├─ createWorkThreadHandle()            ← LLM 调用的后台线程
    ├─ Camera.getInstance().init()         ← 连接 CameraService
    ├─ 启动 1 秒定时器：requestCapture()
    ├─ PromptManager(this)                 ← Prompt 模板管理器
    ├─ vl = VlManager(this, promptManager)
    ├─ toolRegistry.registerAll(10 managers) ← 注册所有工具
    ├─ memoryOrchestrator(...)             ← 记忆系统初始化
    ├─ traceManager = TraceManager(...)    ← Trace 系统初始化
    ├─ chatOrchestrator = AgentLoopOrchestrator(...) ← 对话引擎
    ├─ sceneMatcher = SceneMatch(...)
    └─ VRServiceManager.initCallback()     ← VR/TTS 初始化
```

### 5.2 对话流程（sendMessage）

```
Launcher → AIDL sendMessage(text)
    │
    ▼
AIAgentService 创建 TraceSession (root span → makeCurrent)
    │
    ▼
AgentLoopOrchestrator.execute(text, extraContext)
    │
    ├→ ① injectSystemPrompt() → 含长期记忆
    ├→ ② MemoryPreProcessor → 注入【用户记忆参考】
    ├→ ③ VehicleStatusPreProcessor → 车辆状态 JSON
    ├→ ④ TimeContextPreProcessor → 当前时间
    ├→ ⑤ LLM 调用 → llm.call 子 span（makeCurrent → http 属性归此 span）
    │       ├─ LLM 返回 ToolCall → tool.execute 子 span → continue
    │       └─ LLM 返回文本 → PostProcessor → Terminator → ResultCollector
    │
    ▼
AIAgentService session.close() → scope.close() + rootSpan.end()
    OTLP/HTTP → Phoenix (localhost:6006)
```

---

## 6. 功能开发进度评估

| 层次 | 完成度 | 关键瓶颈 |
|------|--------|---------|
| 对外 AIDL 接口 | 100% | 无 |
| Agent 主循环（Orchestrator） | 95% | 组件化管道完整，角色前缀问题需上游兼容 |
| 工具调度（ToolRegistry） | 95% | 反射调度 + 安全审查，SceneServer 已删除 |
| Prompt 管理 | 95% | 9 个模板文件，长期记忆注入 |
| 对话记忆 | 90% | SQLite 持久化，Session 管理，自动压缩 |
| 长期记忆 | 70% | 提取+存储完成，置信度衰减待实现 |
| Trace 追踪 | 90% | OpenTelemetry + Phoenix 完整链路 |
| 车控工具定义（@Tool） | 95% | 40+ 方法，参数描述详尽 |
| 硬件通信（SoaService） | 5% | **所有方法为空——最大断点** |
| 场景识别 | 85% | 真实模型推理，特异性操作为模拟 |
| UI | 0% | 已移除（纯后台服务） |
| 构建 | 100% | 单模块，Version Catalog，AIDL 预存错误 |

**一句话：** LLM 推理链路、工具调度、Prompt/记忆/Trace 等基础设施已完整，硬件通信层（SoaService）为空，无法实际控车。
