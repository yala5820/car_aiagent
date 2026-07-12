# AIAgent — AI 智能座舱后台服务引擎

## 1. 项目概述

AIAgent 是运行于 Android 车机系统上的 **AI 语音助手的后台引擎**。它是一个持续运行在后台的**前台 Service**，本身**无任何 UI**，对外通过 AIDL（`IAIAgentAidlInterface`）暴露 LLM 对话能力，供 Launcher 或其他 App 调用。

**核心业务：** 利用大语言模型（LLM）和多模态视觉模型（VLM），为驾驶员提供自然语言对话、车辆控制、场景感知、前向视野问答等智能座舱能力。

**关键特征：**
- 纯后台 Service，无 UI / 无悬浮窗
- 对外暴露 AIDL 接口（`processAgentRequest(AgentRequest)`），通过 AIDL Binder 供 Launcher 调用
- 使用阿里云 DashScope 的 OpenAI 兼容 API（`qwen-turbo` / `qwen-flash` / `qwen-vl-max`）
- 基于 LangChain4j 1.16.3 的 Tool Calling 机制实现"LLM + 车控"联动
- 集中式反射工具调度（ToolRegistry + ToolDispatcher），消除样板代码
- 组件化 Agent 循环（AgentLoopOrchestrator），7 个可插拔接口
- 外部化 Prompt 管理（assets/prompts/ + PromptTemplate），动态切换
- 四层记忆系统（Session / 长期记忆 / 压缩 / 提取）
- 全链路追踪（OpenTelemetry + Phoenix）
- **虚拟车辆状态机（VehicleStateMachine）**：Demo 阶段车控 tool 的状态托管中心，参数校验 + 状态收敛
- **Context 上下文模块**：统一管理 TEXT 请求的上下文元信息，按需渲染后注入 LLM 输入，同时记入 Trace 观测

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
| **Phase 8** | **统一入口改造**：`sendMessage/sendMessageWithImage/requestAI` → `processAgentRequest(AgentRequest)`，`AgentResponse` 统一回调 |
| **Phase 9** | **虚拟车辆状态机**：VehicleStateMachine + 8 个子系统 State POJO，替换 SoaService 调用，参数校验 |
| **Phase 10** | **Runtime 层**：AgentRuntime + RequestSession + RequestSessionFactory，解耦 Service 与 Orchestrator |
| **Phase 11** | **IntentRouter**：KeywordIntentRouter 关键词+正则意图标签器，11 种 IntentTag，只观测不分流 |
| **Phase 12** | **ToolGroup**：13 个工具组元数据 + DefaultToolGroupSelector，基于 IntentResult 选择候选工具组，只记录不限制 |
| **Phase 13** | **协议扩展**：AgentRequest/AgentResponse 新增 userId/personaId/clientMessageId/status/errorDetail；新增 5 个会话与取消 Parcelable；AIDL 新增 6 个管理接口 |
| **Phase 14** | **会话管理**：ConversationManager 门面，ConversationSessionGateway 可测试抽象，create/list/switch/delete/getActive 全链路；SessionManager 多用户隔离修复；SessionMemoryStore 扩展（元数据、事务创建、级联删除） |
| **Phase 15** | **用户与 Persona**：RequestSessionFactory userId/sessionId 分离；TEXT 三种内置人格（chat/friendly/concise）；AgentConfigFactory.createTextPersona() 统一入口；Trace 记录有效 persona |
| **Phase 16** | **请求取消**：ActiveRequestRegistry + CAS 终态抢占（RUNNING/COMPLETED/CANCELLED/TIMEOUT/FAILED）；cancelAgentRequest AIDL 全链路；RuntimeResult/Response 元信息补齐 |
| **Phase 17** | **Context 上下文模块**：ContextOrchestrator + 9 个 Provider 构建统一 ContextFrame；ContextExtraPreProcessor 将上下文注入 LLM 首轮输入；ContextTraceRecorder 写 Trace；RuntimeCancelChecker 三处取消拦截 |
| **Phase 18** | **Context 最小恢复 + TEXT 唯一输入权**：11 个 Provider 改为 Contribution 输出，删除 type()/ContextSection 依赖；TextAgentLoopOrchestrator 拆分；多工具取消闭合；FALLBACK 状态修复；JvmToolRegistry 支持 JVM 集成测试；270+ tests |

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
│  │  ├─ processAgentRequest(AgentRequest)        │            │
│  │  │   输入类型: TEXT / IMAGE / VOICE / CONTROL│            │
│  │  ├─ createConversation                       │            │
│  │  ├─ listConversations                        │            │
│  │  ├─ deleteConversation                       │            │
│  │  ├─ switchConversation                       │            │
│  │  ├─ getActiveConversation                    │            │
│  │  ├─ cancelAgentRequest                       │            │
│  │  └─ registerListener / unregisterListener    │            │
│  └──────────────────┬──────────────────────────┘            │
│                     │                                       │
│                     ▼                                       │
│  ┌─────────────────────────────────────────────┐            │
│  │              AgentRuntime                    │            │
│  │  运行时协调层（Service 与 Orchestrator 之间） │            │
│  │  IntentRouter → ToolGroupSelector → 观测元信息│          │
│  │  ├─ TEXT → textOrchestrators[personaId]     │            │
│  │  ├─ ActiveRequestRegistry（终态抢占）       │            │
│  │  ├─ RequestSession                          │            │
│  │  └─ ContextOrchestrator → ContextFrame      │            │
│  │      （9 个 Provider 按序采集上下文信息）    │            │
│  └──────────────────┬──────────────────────────┘            │
│                     │ execute(session, contextFrame)         │
│                     ▼                                       │
│  ┌─────────────────────────────────────────────┐            │
│  │           AgentLoopOrchestrator               │            │
│  │  统一 Agent 循环引擎，7 组件管线装配            │            │
│  │  PreProcessor 链：                            │            │
│  │    ContextExtraPreProcessor（首轮注入 Context）│            │
│  │    MemoryPreProcessor → VehicleStatus → Time  │            │
│  │  → ModelCaller → SafetyGuard                  │            │
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
│  │  SessionManager → 会话生命周期（多用户隔离）    │            │
│  │  SessionMemoryStore → SQLite 持久化（含元数据） │            │
│  │  SessionChatMemoryProvider → 按 userId+sessionId+personaId 选择 ChatMemory  │
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
| 通信 | AIDL（Launcher ↔ AIAgent：主对话 + 会话 CRUD + 取消 + Listener、SOA 总线、Camera） |
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
│   │   │   ├── AgentRequest.java           # 统一请求体 Parcelable（含 userId/personaId/clientMessageId）
│   │   │   ├── AgentResponse.java          # 统一响应体 Parcelable（含 userId/personaId/status/errorDetail/clientMessageId）
│   │   │   ├── ConversationRequest.java    # 创建会话请求 Parcelable
│   │   │   ├── ConversationInfo.java       # 会话信息 Parcelable（12 字段）
│   │   │   ├── ConversationListResponse.java # 会话列表响应 Parcelable
│   │   │   ├── ConversationOperationResult.java # 会话操作结果 Parcelable
│   │   │   ├── CancelRequestResult.java    # 取消请求结果 Parcelable
│   │   │   ├── IAIAgentServiceListener.java # 本地回调接口
│   │   │   ├── MainActivity.kt             # Launcher Activity（仅用于调试启动）
│   │   │   ├── BootCompleteReceiver.kt     # 开机广播 → 启动 Service
│   │   │   │
│   │   │   ├── VirtualStateMachine/         # 虚拟车辆状态机
│   │   │   │   ├── VehicleStateMachine.java # 统一状态入口 + 参数校验
│   │   │   │   └── state/                  # 8 个子系统状态 POJO
│   │   │   │       ├── AcState.java
│   │   │   │       ├── DoorState.java
│   │   │   │       ├── WindowState.java
│   │   │   │       ├── SeatState.java
│   │   │   │       ├── SpeedState.java
│   │   │   │       ├── ChassisState.java
│   │   │   │       ├── FragState.java
│   │   │   │       └── DmsState.java
│   │   │   │
│   │   │   ├── runtime/                     # 运行时协调层
│   │   │   │   ├── AgentRuntime.java          # Runtime 主入口（Service ↔ Orchestrator 中间层）
│   │   │   │   ├── AgentExecutor.java         # 执行器接口
│   │   │   │   ├── RequestSession.java        # 单次请求快照（含 intent / toolgroup 选择结果）
│   │   │   │   ├── RequestSessionFactory.java # 请求快照工厂
│   │   │   │   ├── RuntimeResult.java         # 统一执行结果
│   │   │   │   ├── RuntimeResponseMapper.java # RuntimeResult → AgentResponse 映射（含 status/errorDetail 等元信息）
│   │   │   │   ├── ActiveRequest.java            # 运行中请求 + CAS 终态抢占
│   │   │   │   ├── ActiveRequestRegistry.java    # 请求注册 + 取消 + finished 60s 缓存
│   │   │   │   ├── IdGenerator.java / UuidIdGenerator.java
│   │   │   │   └── TimeProvider.java / SystemTimeProvider.java
│   │   │   │
│   │   │   ├── intentrouter/                # 轻量意图标签器
│   │   │   │   ├── IntentRouter.java           # 意图路由接口
│   │   │   │   ├── KeywordIntentRouter.java    # 关键词 + 正则匹配实现
│   │   │   │   ├── IntentTag.java              # 11 种粗粒度意图枚举
│   │   │   │   ├── IntentConfidence.java       # 置信度枚举
│   │   │   │   └── IntentResult.java           # 单次意图识别结果
│   │   │   │
│   │   │   ├── toolgroup/                    # 工具分组与选择
│   │   │   │   ├── ToolGroupId.java             # 13 个工具组枚举
│   │   │   │   ├── ToolGroup.java               # 不可变工具组元数据
│   │   │   │   ├── ToolGroupRegistry.java       # 注册表（defaultRegistry 全量 47 个 toolName 注册）
│   │   │   │   ├── ToolGroupSelector.java       # 选择器接口
│   │   │   │   ├── DefaultToolGroupSelector.java # 基于 IntentResult + 弱车载关键词的选择
│   │   │   │   └── ToolGroupSelectionResult.java # 选择结果
│   │   │   │
│   │   │   ├── conversation/                  # 会话管理门面
│   │   │   │   ├── ConversationManager.java        # 面向 AIDL 的会话 CRUD 门面
│   │   │   │   ├── ConversationConstants.java      # 统一常量
│   │   │   │   ├── ConversationSessionGateway.java # 可测试抽象接口
│   │   │   │   └── MemoryConversationSessionGateway.java # 生产实现（委托 MemoryOrchestrator）
│   │   │   │
│   │   │   ├── context/                      # Context 上下文模块
│   │   │   │   ├── ContextMode.java             # 三种模式（OBSERVE_ONLY/HYBRID_EXTRA_CONTEXT/FULL_CONTEXT）
│   │   │   │   ├── ContextSectionType.java      # 10 种上下文段分类
│   │   │   │   ├── ContextSection.java          # 不可变上下文段
│   │   │   │   ├── ContextFrame.java            # 不可变上下文快照
│   │   │   │   ├── ContextFrameBuilder.java     # 从 RequestSession 构造 Frame
│   │   │   │   ├── ContextBudgetManager.java    # 预算规则
│   │   │   │   ├── ContextDebugInfo.java        # 构建诊断信息
│   │   │   │   ├── ContextBuildInput.java       # Provider 依赖容器
│   │   │   │   ├── ContextBuildResult.java      # 执行结果
│   │   │   │   ├── ContextBuildException.java   # 构建异常
│   │   │   │   ├── ContextProvider.java         # Provider 接口
│   │   │   │   ├── ContextProviderResult.java   # 执行结果（success/fallback/failure）
│   │   │   │   ├── ContextOrchestrator.java     # 构建总入口
│   │   │   │   ├── ContextTraceRecorder.java    # 构建指标写入 Trace
│   │   │   │   ├── VehicleStatusProvider.java   # 车辆状态接口
│   │   │   │   └── provider/                    # 9 个 Provider
│   │   │   │       ├── RuntimeContextProvider.java
│   │   │   │       ├── PersonaContextProvider.java
│   │   │   │       ├── UserInputContextProvider.java
│   │   │   │       ├── IntentContextProvider.java
│   │   │   │       ├── ToolGroupContextProvider.java
│   │   │   │       ├── MemoryContextProvider.java
│   │   │   │       ├── VehicleStateContextProvider.java
│   │   │   │       ├── TimeContextProvider.java
│   │   │   │       └── PromptContextProvider.java
│   │   │   │
│   │   │   ├── runtime/                       # 运行时协调层（续）
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
│   │   │   │   ├── MemoryOrchestrator.java  # 协调器（含会话管理门面）
│   │   │   │   ├── SessionManager.java      # Session 生命周期（多用户隔离）
│   │   │   │   ├── SessionMemoryStore.java  # Session 持久化（含元数据字段）
│   │   │   │   ├── SessionMemoryIds.java    # memoryId 统一生成工具
│   │   │   │   ├── SessionChatMemoryProvider.java # 按 userId+sessionId+personaId 选择 ChatMemory
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

- **AIDL Binder 实现**：完整的对话与会话管理接口集
  - `processAgentRequest(AgentRequest)` — 主对话入口（TEXT → AgentRuntime → textOrchestrators[personaId] / IMAGE → VlManager / VOICE → 旧链路 AI + TTS / CONTROL → StartListen/StopListen）
  - `createConversation(ConversationRequest)` — 创建新对话（不结束旧会话，响应含 sessionId）
  - `listConversations(String userId)` — 列出用户最近 50 条会话
  - `deleteConversation(String userId, String sessionId)` — 删除会话（消息 + 记录）
  - `switchConversation(String userId, String sessionId)` — 切换活跃对话（只改 is_active，不写 ended_at）
  - `getActiveConversation(String userId)` — 查询当前活跃对话
  - `cancelAgentRequest(String requestId, String reason)` — 协作式取消（CAS 终态抢占，抑制 late result）
  - `registerListener(IAIAgentAidlListener)` / `unregisterListener(...)`
- **AgentRuntime 协调层**：TEXT 请求经 startSession → routeIntentSafely → selectToolGroupsSafely → execute 管线
- **TEXT Persona 多实例**：按 personaId（chat/friendly/concise）初始化三个独立 `AgentLoopOrchestrator`，每个有独立 system prompt 和 ChatMemory
- **ActiveRequestRegistry**：运行中请求终态管理，timeout/success/failure/cancel 四路通过 CAS 抢占终态，只允许一方发送 listener 响应
- **Trace 元信息**：Trace 创建前补齐 requestId，userId 使用 `request.userId ?: default_user`，记录有效 personaId（normalize 后）
- **Camera 接入**：每 1 秒请求一次前向摄像头抓拍
- **场景识别循环**：抓拍 → SceneMatch 识别 → 场景变化 → AgentLoopOrchestrator 主动响应
- **Listener 回调推送**：`onAIResponse(AgentResponse)` 统一回调
- **15 秒超时保护**（经 ActiveRequestRegistry 终态抢占）

### 4.2 TextAgentLoopOrchestrator（TEXT 专用循环引擎）

**文件：** `core/TextAgentLoopOrchestrator.java`

TEXT 请求的唯一 Agent 执行入口。构造器只接收 `AgentConfig`、`ContextMemoryGateway` 和 `ContextAssemblyGateway`，不持有 PromptManager 或固定工具规格。消息和工具全部来自 `ContextAssemblyResult`：

```
execute(session, prepareResult)
  → UserMessage 提交推迟到预算和取消检查通过后
  → for i in 0..maxIterations:
      ① ContextOrchestrator.assemble()
           → 动态 Provider + ContextMessageAssembler
           → ContextAssemblyResult(messages, toolSpecifications, budgetReport)
      ② 预算 & 取消检查
      ③ ChatRequest(messages, toolSpecifications) → ModelCaller
      ④ LLM 返回 ToolCall → SafetyGuard → ToolExecutor → continue
      ⑤ LLM 返回文本 → PostProcessor → Terminator → ResultCollector → return
  → max iterations → AgentResult.error()
```

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

**对话隔离增强：** `AgentLoopOrchestrator` 现已集成 `SessionChatMemoryProvider`，每次 `execute()` 按 `userId + sessionId + personaId` 从 `MessageWindowChatMemory` 选择对应 ChatMemory，而非构造期固定实例。create/switch/delete 对话后 LLM 可见的短期历史随之真实切换。

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
- 一次请求一个完整 Trace，含 `gen_ai.chat` + `tool.execute` 子 span
- Span 携带：模型名、输入/输出、Token 用量、HTTP 状态码、工具参数/结果
- 开发环境通过 `adb reverse tcp:6006 tcp:6006` 连接 PC 端 Phoenix
- `TraceConfig.production()` 一键关闭（全局 no-op）

### 4.7 ConversationManager（会话管理门面）

**文件：** `conversation/`

面向 AIDL 的会话 CRUD 门面，通过 `ConversationSessionGateway` 接口解耦测试与生产：

| 组件 | 说明 |
|------|------|
| `ConversationConstants` | 统一常量（DEFAULT_USER_ID/DEFAULT_PERSONA_ID/OP_CREATE/OP_DELETE/OP_SWITCH） |
| `ConversationSessionGateway` | 6 方法接口：createConversationSession / listSessions / getActiveSession / getSession / switchSession / deleteSession |
| `MemoryConversationSessionGateway` | 生产实现，委托 MemoryOrchestrator + SessionManager + SessionMemoryStore |
| `ConversationManager` | CRUD 门面：create/list/delete/switch/getActive，含 toConversationInfo 元数据映射 |

**会话元数据：** `ConversationInfo` 包含 12 个字段（userId/sessionId/personaId/title/active/createdAt/updatedAt/endedAt/messageCount/tokenEstimate/compressionCount），`endedAt=0` 表示对话尚未结束。

**底层支持：**
- `SessionMemoryStore` DB_VERSION 升级到 2，sessions 表新增 title/persona_id/source_app/updated_at 列，`onUpgrade(1→2)` 使用 ALTER TABLE 补列
- `SessionManager` active session 从单个字段改为 `ConcurrentHashMap<userId, ActiveSessionState>`，`createConversationSession` 不结束旧会话（区别于 `startNewSession`）
- `SessionChatMemoryProvider` 按 `userId_sessionId_personaId` 生成 memoryId，对话切换真实影响 LLM ChatMemory

### 4.8 Vehicle*Manager 系列（车控工具）

8 个模块，共约 **40+ 个 @Tool 方法**，全部使用 `ToolRegistry` 统一调度。
每个 Manager 的 `@Tool` 方法**委托给 `VehicleStateMachine`** 执行状态变更和参数校验。

| 模块 | 工具数 | 覆盖功能 |
|------|-------|---------|
| VehicleDoorManager | 1 | 车门闭锁/解锁 |
| VehicleWindowManager | 11 | 车窗、天窗、遮阳帘、除霜、后视镜加热 |
| VehicleSeatManager | 11 | 座椅加热、通风、按摩、方向盘加热 |
| VehicleAcManager | 15 | 空调开关、温度、风量、ECO、负离子、内外循环 |
| VehicleChassisManager | 1 | 底盘模式（普通/越野/雪地） |
| VehicleFragManager | 2 | 香氛类型、浓度 |
| VehicleSpeedManager | 1 | 巡航车速 |
| VehicleDMSManager | 3 | 驾驶员疲劳、分心、情绪 |

### 4.9 ActiveRequestRegistry（请求取消与终态管理）

**文件：** `runtime/ActiveRequest.java` / `runtime/ActiveRequestRegistry.java`

提供协作式请求取消能力，不承诺硬中断 LLM HTTP 调用：

- `ActiveRequest`：不可变请求快照（requestId/sessionId/userId/personaId/clientMessageId） + CAS 终态标记
- `ActiveRequest.TerminalState`：RUNNING → COMPLETED / CANCELLED / TIMEOUT / FAILED（只有 RUNNING→终态一次有效）
- `ActiveRequestRegistry`：`ConcurrentHashMap` 管理活跃请求 + `finishedRequests` 缓存（60s TTL 防 race）
- **四路终态抢占**：cancel AIDL、timeout runnable、worker success、worker exception 通过 `tryComplete` 竞争终态；只有首次抢占成功方允许发送 listener 响应
- **竞态修复**：取消后 Worker 的 `isCancelled` 路径不调用 `finish()`，将请求清理责任留给 cancel handler 独占

### 4.10 VehicleStateMachine（虚拟车辆状态机）

**文件：** `VirtualStateMachine/`

Demo 阶段引入的状态托管中心，替换原有的 `SoaService` 外部调用 + 分散的本地状态：

- 持有 8 个子系统 State POJO（AcState / DoorState / WindowState / SeatState / SpeedState / ChassisState / FragState / DmsState）
- 每个 `@Tool` 方法在 VehicleStateMachine 中有对应实现：**参数校验 + 状态变更**，返回 `String`
- Vehicle*Manager 删除本地状态字段和 `formalfunc` 标志，@Tool 方法直接委托给 VehicleStateMachine
- 状态查询（`getXxxStatus()`）统一从状态机读取，形成整车状态快照

### 4.11 AgentRuntime（运行时协调层）

**文件：** `runtime/`

位于 AIAgentService 与 AgentLoopOrchestrator 之间的薄协调层，职责：

- **RequestSession 创建**：从 AgentRequest + TraceContext 创建规范化快照（含 userId/sessionId/personaId/clientMessageId/intent/toolgroup 选择结果）
- **IntentRouter 调度**：在 `startSession()` 中调用 IntentRouter 生成 `IntentResult`（11 种粗粒度意图标签）
- **ToolGroup 选择**：基于 IntentResult 调用 ToolGroupSelector，生成 `ToolGroupSelectionResult`（候选工具组 + 工具名列表）
- **Trace 写入**：将 intent / toolgroup / clientMessageId 信息写入 Trace root span 的 11 个 attribute
- **异常降级**：Router / Selector 异常不影响执行路径，降级为 `UNKNOWN` / `CHAT_ONLY_GROUP`
- **执行封装**：`execute()` / `timeoutResult()` / `errorResult()` / `cancelledResult()` 统一封装 RuntimeResult（全部含 userId/personaId/clientMessageId 元信息）
- **服务端取消支持**：`cancelledResult(session, reason)` → RuntimeResult.cancelled → Mapper 按状态码 CANCELLED 映射
- **Context 编排**：`execute(session)` 内先调 `contextOrchestrator.build(session)` 构建 ContextFrame，再执行 `chatExecutor.execute(session, contextFrame)`，中间插 `cancelChecker.isCancelled(session)` 检查；8 个构造函数支持 ContextOrchestrator 和 RuntimeCancelChecker 注入

8 个构造函数支持全量依赖注入（生产 / 测试），TEXT 请求执行路径：

```
handleTextRequest() → traceManager.startAgentRequest() → agentRuntime.startSession()
  → routeIntentSafely() → selectToolGroupsSafely()
  → writeIntentToTrace() → writeToolGroupsToTrace() → sessionFactory.create() → writeRequestMetaToTrace()
  → ActiveRequestRegistry.register() → agentRuntime.execute()
     → contextOrchestrator.build(session) → ContextFrame
     → cancelChecker.isCancelled(session) → cancelled_before_agent_loop
     → chatExecutor.execute(session, contextFrame) [旧 execute(String, Map) 兼容]
  → tryComplete(COMPLETED/FAILED) → runtimeResponseMapper.toAgentResponse()
  → notifyAIAgentListeners() → session.close()
```

### 4.12 IntentRouter（轻量意图标签器）

**文件：** `intentrouter/`

非 LLM 的纯关键词 + 正则匹配意图系统，只为观测和后续策略做准备，不做分流：

- `KeywordIntentRouter`：使用 LinkedHashMap + 正则实现，当前约 56 个业务关键词 + 少量正则，空文本返回 UNKNOWN，无关键词返回 CHAT
- 11 种 IntentTag：`CHAT` / 7 个车辆域 / `VISION_QA` / `WEATHER` / `UNKNOWN`
- 输出 `IntentResult` 含 6 个字段：`intentTag` / `confidence` / `matchedKeywords` / `normalizedText` / `sourceInputType` / `debugReason`
- 优先级稳定（LinkedHashMap 保证顺序），不调用 LLM、不引用 tool registry

### 4.13 ToolGroup（工具分组与选择）

**文件：** `toolgroup/`

将当前 47 个 @Tool 方法按功能域分组的纯元数据层，本阶段只记录不限制 LLM 可见工具：

- 13 个 `ToolGroupId`：11 个基础/领域组 + `COMMON_VEHICLE_GROUP`（车辆聚合）+ `ALL_SAFE_DEMO_GROUP`（全量）
- `ToolGroupRegistry.defaultRegistry()`：全量 47 个 toolName 注册，支持按 toolName 反查、多组合并去重
- `DefaultToolGroupSelector`：11 种 IntentTag → ToolGroupId 映射，车辆意图附加 `BASIC_STATUS_GROUP`，UNKNOWN 含弱车载关键词时选 `COMMON_VEHICLE_GROUP`
- 当前默认 `KeywordIntentRouter` 对非空但无关键词命中的文本会返回 `CHAT`；因此弱车载 UNKNOWN fallback 需要结合真实 TEXT/Trace 手动验收继续确认
- `ToolGroupSelectionResult`：记录 `selectedGroupIds` / `selectedToolNames` / `selectionReason` / `confidence` / `fallbackUsed`
- 聚合组 `riskLevel` 遵循最高风险上浮规则（`COMMON_VEHICLE_GROUP` 和 `ALL_SAFE_DEMO_GROUP` 均为 HIGH）
- 不执行 Tool、不修改 ToolRegistry/ToolDispatcher/AgentLoopOrchestrator

### 4.14 ContextOrchestrator（上下文模块）

**文件：** `context/`

在 AgentRuntime.execute() 中、TextAgentLoop 启动前，由 ContextOrchestrator 按序驱动 11 个 Provider 采集更新，生成 `ContextPrepareResult`。TextAgentLoopOrchestrator 每轮调用 `ContextOrchestrator.assemble()`，经 `ContextMessageAssembler` 装配为最终 `ChatRequest.messages()` 和 `toolSpecifications()`。

#### Contribution 输出与 Provider 链

Provider 分为 REQUEST_STATIC（8 个，prepare 执行）和 ITERATION_DYNAMIC（3 个，assemble 执行）：

- **REQUEST_STATIC**：Runtime、Persona、Prompt、UserInput、Intent、ToolGroup、LongTermMemory、CallerExtra
- **ITERATION_DYNAMIC**：SessionMemory、VehicleState、Time

每个 Provider 输出 `List<ContextContribution>`，包含可见性、信任级别、优先级和来源标记。Provider 不再直接操作 `ContextSection`。

#### 消息装配

`ContextMessageAssembler` 根据 iteration 参数决定是否包含 CURRENT_USER：
- **iteration 0**：SystemMessage → Context Data（ContextDataFormatter envelope） → SessionMemory → CurrentUser
- **iteration 1+**：SystemMessage → Context Data → SessionMemory（不含 CURRENT_USER）

#### 三个层次的取消保护

Context 模块在"Context 构建后、AgentLoop 启动前"这个关键窗口插入了取消检查（RuntimeCancelChecker），与 Service 层原有的构建前取消和返回后取消形成三路保护：

```
构建前取消（原有）→ Context 构建 → 构建后取消（新增）→ AgentLoop → 返回后取消（新增）
     worker 入口检查        │             Runtime 内部           │     Service 层检查
                            ▼                                   ▼
                     cancelled_before_agent_loop           suppress late success
```

#### 核心组成

| 组件 | 职责 |
|------|------|
| `ContextOrchestrator` | 构建总入口，`defaultForText()` 装配 9-Provider 标准链，`build(session)` 按序遍历 |
| `ContextMode` | OBSERVE_ONLY（只观测不注入）/ HYBRID_EXTRA_CONTEXT（一期默认）/ FULL_CONTEXT（预留） |
| `ContextFrame` | 不可变快照，含 requestId/sessionId/userId/personaId/intentResult/selectedTools/sections |
| `ContextFrameBuilder` | 从 `RequestSession` 构造 Frame，不重新生成 ID |
| `ContextBudgetManager` | section 800 字符 / memory 500 / tool 1200 / total 3000 的粗估预算 |
| `ContextTraceRecorder` | 将 11 个构建指标（mode、provider_count、selected_tool_count、build_ms 等）写入 Trace |
| `RuntimeCancelChecker` | Context 构建后、AgentLoop 前的只读取消接口 |
| `ContextExtraPreProcessor` | AgentLoop 首轮 PreProcessor，将 renderedExtraContext 转为 UserMessage |

#### 11 个 Provider

| Provider | 生命周期 | required | 输出目标 |
|----------|---------|----------|---------|
| RuntimeContextProvider | REQUEST_STATIC | TEXT 始终 required | POLICY_ONLY 诊断 |
| PersonaContextProvider | REQUEST_STATIC | optional | POLICY_ONLY |
| PromptContextProvider | REQUEST_STATIC | 始终 required | SYSTEM Message |
| UserInputContextProvider | REQUEST_STATIC | 始终 required | CURRENT_USER Message |
| IntentContextProvider | REQUEST_STATIC | optional | POLICY_ONLY |
| ToolGroupContextProvider | REQUEST_STATIC | 非 CHAT_ONLY | ToolContextContribution |
| LongTermMemoryContextProvider | REQUEST_STATIC | optional | CONTEXT_DATA |
| CallerExtraContextProvider | REQUEST_STATIC | optional | CONTEXT_DATA |
| SessionMemoryContextProvider | ITERATION_DYNAMIC | TEXT 始终 required | SESSION_MEMORY Message |
| VehicleStateContextProvider | ITERATION_DYNAMIC | 需 vehicle_status | CONTEXT_DATA |
| TimeContextProvider | ITERATION_DYNAMIC | optional | CONTEXT_DATA |

#### 异常处理策略

- 单个 Provider 异常通过 try/catch 保护，不影响其他 Provider
- Provider 依赖缺失（如 ToolGroupRegistry 为 null）自动 fallback 而非抛 NPE
- 全部 9 个 Provider 均失败时仍返回 ContextFrame（renderedExtraContext 为空），下游 AgentLoop 照常执行
- ContextBuildResult 区分 success（部分降级仍算成功）和 fallback（全部失败）两种结果

### 4.15 SoaService（SOA 总线封装）

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
    ├─ vehicleStateMachine = VehicleStateMachine() ← 虚拟车辆状态机
    ├─ vl = VlManager(this, promptManager)
    ├─ toolRegistry.registerAll(10 managers) ← 注册所有工具（Manager 注入 vehicleStateMachine）
    ├─ memoryOrchestrator(...)             ← 记忆系统初始化
    ├─ traceManager = TraceManager(...)    ← Trace 系统初始化
    ├─ chatOrchestrator = AgentLoopOrchestrator(...) ← 旧版对话引擎（VOICE 链路使用）
    ├─ conversationManager = ConversationManager(...)  ← 会话管理门面
    ├─ textOrchestrator = TextAgentLoopOrchestrator(config, memoryOrch, contextOrch) ← TEXT 专用循环
    ├─ contextOrchestrator = ContextOrchestrator.defaultForText(...) ← Context 模块
    ├─ agentRuntime = AgentRuntime(
    │      AgentExecutor { textOrchestrators[persona].execute() },
    │      contextOrchestrator,
    │      RuntimeCancelChecker { activeRequestRegistry.get(it.requestId())?.isCancelled == true })
    ├─ sceneMatcher = SceneMatch(...)
    └─ VRServiceManager.initCallback()     ← VR/TTS 初始化
```

### 5.2 对话流程（processAgentRequest — TEXT 完整链路）

```
Launcher/AIAgentTestApp → AIDL processAgentRequest(AgentRequest)
    │  inputType=TEXT
    ▼
AIAgentService.handleTextRequest()
    ├─ traceManager.startSession() → root span → makeCurrent()
    └─ agentRuntime.startSession(request, traceContext)
         ├─ routeIntentSafely(request)             ← KeywordIntentRouter
         │      → IntentResult(intentTag, confidence, matchedKeywords, ...)
         ├─ selectToolGroupsSafely(intentResult, request)  ← DefaultToolGroupSelector
         │      → ToolGroupSelectionResult(selectedGroupIds, selectedToolNames, ...)
         ├─ writeIntentToTrace(traceContext, intentResult)      ← 5 个 agent.intent.* 属性
         ├─ writeToolGroupsToTrace(traceContext, toolGroups)    ← 5 个 agent.tool_group.* 属性
         └─ sessionFactory.create(request, persona, traceContext,
                  intentResult, toolGroupSelectionResult)
              └─ RequestSession（不可变，含 intent + toolgroup 元信息）
    │
    ▼
agentRuntime.execute(runtimeSession)
    ├→ ① contextOrchestrator.build(session) → ContextFrame
    │     9 个 Provider 按序采集 → renderedExtraContext(仅 renderable=true 的 section)
    │   → ContextTraceRecorder.record() → 11 个 agent.context.* 属性写入 Trace
    ├→ ② cancelChecker.isCancelled(session) → 取消则返回 CANCELLED，不调 AgentLoop
    └→ ③ chatExecutor.execute(session, contextFrame) [default → old execute(String, Map)]
         └─ AgentLoopOrchestrator.execute(text, extraContext)
              ├→ ① injectSystemPrompt() → 含长期记忆
              ├→ ② ContextExtraPreProcessor → 首轮注入【运行时】【意图】【工具组】上下文
              ├→ ③ MemoryPreProcessor → 注入【用户记忆参考】
              ├→ ④ VehicleStatusPreProcessor → 车辆状态 JSON
              ├→ ⑤ TimeContextPreProcessor → 当前时间
              ├→ ⑥ LLM 调用 → gen_ai.chat 子 span
              │       ├─ LLM 返回 ToolCall → tool.execute 子 span → continue
              │       └─ LLM 返回文本 → PostProcessor → Terminator → ResultCollector
              └─ AgentResult
    │
    ▼
runtimeResponseMapper.toAgentResponse(runtimeResult) → AgentResponse
    │
    ▼
notifyAIAgentListeners(response)
session.close() → scope.close() + rootSpan.end()
    OTLP/HTTP → Phoenix (localhost:6006)
```

---

## 6. 功能开发进度评估

| 层次 | 完成度 | 关键瓶颈 |
|------|--------|---------|
| 对外 AIDL 接口 | 100% | **已扩展为完整 API：** `processAgentRequest` + 6 个会话管理/取消接口 + listener 注册 |
| **TEXT Persona** | **100%** | **3 个内置人格（chat/friendly/concise），独立 system prompt + ChatMemory 隔离** |
| **请求取消** | **100%** | **cancelAgentRequest AIDL + ActiveRequestRegistry CAS 终态抢占 + late result 抑制** |
| **会话管理** | **100%** | **ConversationManager CRUD + SessionManager 多用户隔离 + 短期记忆隔离** |
| **AgentRuntime** | **100%** | TEXT 主链路已接管，IntentRouter + ToolGroupSelector + ActiveRequestRegistry + ContextOrchestrator 串联；VOICE 仍保留旧链路 |
| **Context 模块** | **100%** | **ContextOrchestrator + 9 Provider + ContextFrame + Trace 记录 + RuntimeCancelChecker 三路取消保护；HYBRID_EXTRA_CONTEXT 一期模式** |
| Agent 主循环（Orchestrator） | 95% | 组件化管道完整，角色前缀问题需上游兼容 |
| 工具调度（ToolRegistry） | 100% | 反射调度 + 安全审查，SceneServer 已删除 |
| **IntentRouter** | **100%** | KeywordIntentRouter，11 种 IntentTag，关键词+正则匹配 |
| **ToolGroup** | **100%** | 13 个工具组，DefaultToolGroupSelector，只观测不限制 |
| Prompt 管理 | 95% | 9 个模板文件，长期记忆注入 |
| 对话记忆 | 90% | SQLite 持久化，Session 管理，自动压缩 |
| **Persona Prompt** | **100%** | **新增 assistant_friendly / assistant_concise 两套独立 system prompt** |
| 长期记忆 | 70% | 提取+存储完成，置信度衰减待实现 |
| Trace 追踪 | 95% | OpenTelemetry + Phoenix 完整链路 + intent/tool_group/client_message 属性 |
| **协议扩展** | **100%** | **AgentRequest/Response 新增 userId/personaId/clientMessageId/status/errorDetail；5 个会话管理 Parcelable** |
| 车控工具定义（@Tool） | 100% | 当前 47 个 @Tool 方法，参数描述详尽 |
| **虚拟车辆状态机** | **100%** | **替换 SoaService 调用，8 个子系统状态，参数校验完整** |
| 硬件通信（SoaService） | 5% | **所有方法为空——最大断点**（已被 VehicleStateMachine 替代） |
| 场景识别 | 85% | 真实模型推理，特异性操作为模拟 |
| UI | 0% | 已移除（纯后台服务） |
| 构建 | 100% | 单模块，Version Catalog，JVM 单测覆盖持续补充 |

**一句话：** LLM 推理链路、工具调度、Prompt/记忆/Trace/Context 等基础设施已基本成型。AIDL 接口已扩展为完整协议（`processAgentRequest` + 6 个会话管理/取消接口）。TEXT 主链路集成 Runtime 协调层，串联 IntentRouter + ToolGroupSelector + ActiveRequestRegistry + ContextOrchestrator 实现请求可观测性、上下文管理与取消能力。Context 模块在 AgentLoop 前统一采集 9 类上下文，按需注入 LLM 输入并记入 Trace。会话管理（ConversationManager）支持多用户隔离、短期记忆真实隔离、3 种内置 TEXT 人格。硬件通信层（SoaService）为空，虚拟车辆状态机已接管车控 Demo。JVM 单测已覆盖所有关键模块（44+ 测试），流程性验收需在真实设备上执行手动验收清单。
