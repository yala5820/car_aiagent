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
│  │  └─ RequestSession（userId/sessionId/personaId/clientMsgId/intent/toolgroup）│
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

## 4. Working Rules

本准则规定了在当前代码仓库中的执行规范。

### Think Before Coding
- 任务需求模糊时，切勿直接修改文件。
- 先查阅相关文件，说明现有代码实现逻辑。
- 正式编码前，明确列出所有预设前提。
- 若需求存在多种解读方向，列出全部可选方案，不擅自选定其中一种。
- 拿不准时优先简洁提问确认需求，而非贸然做出有风险的猜测。

### Simplicity First for simple problems
- 对于小问题采用能解决需求的最小改动方案。
- 不新增预判性功能、抽象层、配置层或多余扩展能力。
- 若无充分合理说明，不引入新依赖包。
- 若解决方案代码量持续膨胀，暂停操作并给出更轻量化的替代方案。

### Surgical Changes
- 仅改动和任务直接相关的文件。
- 不重构无关业务代码。
- 不格式化本次修改无关的文件。
- 遵循项目现有代码风格，即便其他编码风格更优也保持统一。
- 若发现无关的废弃代码或可疑代码，仅在总结中备注，不擅自修改。

### Goal-Driven Execution

所有复杂任务均遵循以下步骤：

1. 定位需要改动的相关文件
2. 说明代码当前运行逻辑
3. 提出最小化实现方案
4. 方案确认清晰后再执行编码修改
5. 使用适配的命令或人工核验，验证修改效果
6. 汇总改动文件、验证结果与尚存风险

### 禁止操作

无用户明确指令时，严禁执行以下操作：

- 不执行 `rm -rf` 等具有破坏性的文件操作
- 不修改 `.env`、密钥、凭证及本地机器配置文件
- 未经许可，不改动依赖版本与构建脚本
- 未经许可，不进行大规模架构重写

## 5. 用户偏好设定

### 代码注释要求

所有新增代码注释、文档字符串统一使用详尽中文编写。代码需做到自解释，注释重点说明**设计原因**，而非单纯复述代码功能；简单逻辑使用简短单行注释；仅当函数逻辑晦涩难懂时，才编写多行文档字符串。

### 代码修改后的回复格式

完成代码改动后，输出结构化总结，包含三部分内容：

1. **工作目标**：本轮工作要完成什么，或者要解决什么问题。
1. **修改内容与逻辑**：汇总每项改动，包括位置、设计思路或原因、具体做了什么。
2. **工作总结**：首先总结本轮执行任务情况，然后总结当前项目验证状态及遗留风险等