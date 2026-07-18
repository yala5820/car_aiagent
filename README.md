# AIAgent — AI 智能座舱后台服务引擎

## 1. 项目概述

AIAgent 是运行于 Android 车机系统上的 **AI 语音助手的后台引擎**。它是一个持续运行在后台的**前台 Service**，本身**无任何 UI**，对外通过 AIDL（`IAIAgentAidlInterface`）暴露 LLM 对话能力，供 Launcher 或其他 App 调用。

**核心业务：** 利用大语言模型（LLM）和多模态视觉模型（VLM），为驾驶员提供自然语言对话、车辆控制、场景感知、前向视野问答等智能座舱能力。

**架构定位：** AIAgent 是“确定性控制面 + 模型驱动 Tool Loop”的领域约束型单 Agent。AIDL Service、Runtime、Context、ToolGroup、Safety 与请求终态由项目代码控制；模型只在本轮允许的上下文和工具空间内完成自然语言理解、参数生成和多轮工具决策。LangChain4j 提供模型、消息、ChatMemory 与 Tool Calling 原语，不负责项目的业务编排。

**关键特征：**
- 纯后台 Service，无 UI / 无悬浮窗
- 对外暴露 AIDL 接口（`processAgentRequest(AgentRequest)`），通过 AIDL Binder 供 Launcher 调用
- 使用阿里云 DashScope 的 OpenAI 兼容 API（`qwen-turbo` / `qwen-flash` / `qwen-vl-max`）
- 使用 LangChain4j 1.16.3 的消息、模型与 Tool Calling 原语，自研 Runtime / Context / AgentLoop 负责业务编排
- 集中式反射工具调度（ToolRegistry + ToolDispatcher），消除样板代码
- 组件化 Agent 循环（AgentLoopOrchestrator / TextAgentLoopOrchestrator）
- 独立确定性的车控安全审核（ToolSafetyEngine），支持 ALLOW / DENY / REQUIRE_CONFIRMATION，并通过下一条普通 TEXT 请求完成高风险动作二次确认
- 外部化 Prompt 管理（`assets/prompts/` + PromptTemplate），支持 TEXT Persona 模板选择
- 四层记忆系统（Session / 长期记忆 / 压缩 / 提取）
- 全链路追踪（OpenTelemetry + Phoenix）
- **虚拟车辆状态机（VehicleStateMachine）**：Demo 阶段车控 tool 的状态托管中心，参数校验 + 状态收敛
- **Context 上下文模块**：以 8 个请求级 Provider + 3 个迭代级 Provider 统一采集 Prompt、Memory、Tool、车辆与时间信息；独占装配 TEXT 模型输入，并支持集中策略、预算裁剪、Memory 摘要恢复和完整 Trace

> **当前范围：** TEXT 是唯一进入 Runtime / Context / TextAgentLoop 的业务主路径；IMAGE、VOICE 返回结构化不支持响应，CONTROL 与主动场景链保留兼容实现。前向视觉问答使用受控 Demo assets 图片与 `qwen-vl-max`，不代表实时摄像头能力。Demo 车控由 `VehicleStateMachine` 托管，`SoaService` 尚未形成真实车辆执行与回执闭环，因此 README 中的“完成”均不代表量产车控验收完成。

### 改造历史

| 阶段 | 说明 |
|------|------|
| **Phase 1** | 16 个 Gradle 模块合并为单一 `app/` 模块，移除 UI 悬浮窗组件 |
| **Phase 2** | 工具系统重构：ToolDispatcher + ToolRegistry，消除 450 行样板代码，修复 5 个 Bug |
| **Phase 3** | Prompt 规范化：9 个 `.txt` 模板文件替代 150 行硬编码，PromptManager + PromptSelector |
| **Phase 4** | Agent 主循环统一：AgentLoopOrchestrator + 组件化执行管线（原 SafetyGuard 已由 ToolSafetyEngine 取代） |
| **Phase 5** | 记忆系统：四层架构（Session/长期记忆/压缩/提取），3 张 SQLite 表 |
| **Phase 6** | Trace 系统：OpenTelemetry + Phoenix 全链路追踪 |
| **Phase 7** | AgentLoop Bug 修复：状态机重置、长期记忆刷新、onTurnComplete 去重 |
| **Phase 8** | **统一入口改造**：`sendMessage/sendMessageWithImage/requestAI` → `processAgentRequest(AgentRequest)`，`AgentResponse` 统一回调 |
| **Phase 9** | **虚拟车辆状态机**：VehicleStateMachine + 8 个子系统 State POJO，替换 SoaService 调用，参数校验 |
| **Phase 10** | **Runtime 层**：AgentRuntime + RequestSession + RequestSessionFactory，解耦 Service 与 Orchestrator |
| **Phase 11** | **IntentRouter 初版**：KeywordIntentRouter 关键词+正则意图标签器，11 种 IntentTag；初版只观测，后续已接入 ToolGroup 选择 |
| **Phase 12** | **ToolGroup 初版**：13 个工具组元数据 + DefaultToolGroupSelector；初版只记录，Phase 18 起已实际限制 TEXT 模型可见工具 |
| **Phase 13** | **协议扩展**：AgentRequest/AgentResponse 新增 userId/personaId/clientMessageId/status/errorDetail；新增 5 个会话与取消 Parcelable；AIDL 新增 6 个管理接口 |
| **Phase 14** | **会话管理**：ConversationManager 门面，ConversationSessionGateway 可测试抽象，create/list/switch/delete/getActive 全链路；SessionManager 多用户隔离修复；SessionMemoryStore 扩展（元数据、事务创建、级联删除） |
| **Phase 15** | **用户与 Persona**：RequestSessionFactory userId/sessionId 分离；TEXT 三种内置人格（chat/friendly/concise）；AgentConfigFactory.createTextPersona() 统一入口；Trace 记录有效 persona |
| **Phase 16** | **请求取消**：ActiveRequestRegistry + CAS 终态抢占（RUNNING/COMPLETED/CANCELLED/TIMEOUT/FAILED）；cancelAgentRequest AIDL 全链路；RuntimeResult/Response 元信息补齐 |
| **Phase 17** | **Context 上下文模块骨架**：建立 ContextFrame、Provider、预算、Trace 与 Runtime 取消检查，并验证上下文能够进入模型可见输入 |
| **Phase 18** | **Context TEXT 独占切换**：11 个 Provider 统一输出 Contribution；按 `prepare` / `assemble` 分离静态与动态上下文；`ContextMessageAssembler` 独占生成 TEXT 消息和工具规格；补齐预算、消息序列、工具交换、取消和端到端测试 |
| **Phase 19** | **Context / Agent Trace 收口**：建立 `agent.request → agent.loop → iteration → context / gen_ai / tool → response.dispatch` Trace 树，补充 Provider、消息、工具 schema 与 Tool 执行阶段诊断；仍保留少量准确性和设备侧验收项 |
| **Phase 20** | **P0 运行时与车控安全收口**：单 TEXT 准入、统一 30 秒 deadline、模型 HTTP Call 取消、ToolGroup fail-closed、高风险动作文本二次确认 |
| **Phase 21** | **Context 长会话与语义收口**：完整 Session 历史、集中 ContextPolicy、可选数据裁剪、真实摘要压缩与一次重装配、结构化 Tool outcome、FULL_DEBUG 全文 Trace、Token 误差字段和 Legacy 清理 |

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
│  │  RequestAdmission → ActiveRequestRegistry    │            │
│  │  RequestDeadline → RequestCallRegistry       │            │
│  │  文本确认分支 / 唯一终态 / Listener 派发      │            │
│  └──────────────────┬──────────────────────────┘            │
│                     │                                       │
│                     ▼                                       │
│  ┌─────────────────────────────────────────────┐            │
│  │              AgentRuntime                    │            │
│  │  运行时协调层（Service 与 AgentLoop 之间）   │            │
│  │  IntentRouter → ToolGroupSelector            │            │
│  │  ├─ RequestSession（请求事实快照）           │            │
│  │  ├─ 选择状态：SELECTED / CHAT_ONLY / 澄清 / 失败关闭│       │
│  │  └─ ContextOrchestrator.prepare()            │            │
│  │      8 个 REQUEST_STATIC Provider            │            │
│  └──────────────────┬──────────────────────────┘            │
│                     │ execute(session, prepareResult)        │
│                     ▼                                       │
│  ┌─────────────────────────────────────────────┐            │
│  │        TextAgentLoopOrchestrator            │            │
│  │  每轮 ContextOrchestrator.assemble()        │            │
│  │  ├─ 3 个 ITERATION_DYNAMIC Provider        │            │
│  │  ├─ ContextMessageAssembler                │            │
│  │  ├─ 预算与消息序列校验                      │            │
│  │  └─ ChatRequest(messages, toolSpecifications)│           │
│  │     → ModelCaller → ToolSafetyEngine → Tool│            │
│  │       高风险动作 → 普通 TEXT 二次确认       │            │
│  │     → PostProcessor → Terminator → Collector│            │
│  ├─────────────────────────────────────────────┤            │
│  │           Tool 调用层（集中式反射调度）         │            │
│  │  ToolRegistry (Map<String, ToolDispatcher>)   │            │
│  │  ToolDispatcher (反射扫描 @Tool 方法)         │            │
│  │  Vehicle*Manager | WeatherUtils | VlManager  │            │
│  ├─────────────────────────────────────────────┤            │
│  │           Prompt 管理                         │            │
│  │  assets/prompts/（11 个 .txt 模板）           │            │
│  │  PromptManager (加载+缓存+渲染)               │            │
│  │  PromptSelector (动态切换策略)                │            │
│  ├─────────────────────────────────────────────┤            │
│  │           记忆系统（四层架构）                  │            │
│  │  SessionManager → 会话生命周期（多用户隔离）    │            │
│  │  SessionMemoryStore → SQLite 持久化（含元数据） │            │
│  │  SessionChatMemoryProvider → 按 sessionId 选择 ChatMemory  │
│  │  LongTermMemory → 用户偏好持久化              │            │
│  │  MemoryCompressor → 提供摘要压缩能力           │            │
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
| Android | compileSdk / targetSdk 35，minSdk 33，Java 17 |
| AI 基础库 | LangChain4j 1.16.3（模型、消息、Tool Calling）；Agent 编排由项目自研 Runtime / Context / AgentLoop 完成 |
| LLM 模型 | qwen-turbo（对话）、qwen-flash（场景）、qwen-vl-max（视觉问答） |
| LLM API | 阿里云 DashScope（OpenAI 兼容接口） |
| 通信 | AIDL（Launcher ↔ AIAgent：主对话 + 会话 CRUD + 取消 + Listener、SOA 总线、Camera） |
| UI | **无**（纯后台 Service） |
| 持久化 | SQLite（ChatMemory 持久化 + 长期记忆 + Session 管理） |
| 网络 | OkHttp 4.12 |
| Trace | OpenTelemetry 1.48.0 + Phoenix（开发调试用） |
| VR/TTS | adapter_vr.jar（闭源） |
| 构建 | Gradle 8.11.1 / AGP 8.9.1 / Kotlin 2.0.21 / Version Catalog |

---

## 3. 项目目录结构

```
AIAgent/
├── app/                                    # 唯一模块
│   ├── src/main/
│   │   ├── AndroidManifest.xml             # Service + Receiver + Launcher Activity
│   │   ├── assets/prompts/                 # Prompt 模板文件（11 个 .txt）
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
│   │   │   ├── ConversationInfo.java       # 会话信息 Parcelable（11 字段）
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
│   │   │   │   ├── RequestAdmission.java         # Runtime 前准入快照
│   │   │   │   ├── RequestDeadline.java          # TEXT 端到端 30 秒绝对期限
│   │   │   │   ├── RequestCallRegistry.java      # requestId → 同步模型 HTTP Call
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
│   │   │   │   ├── ToolGroupRegistry.java       # 注册表（defaultRegistry 全量 46 个 toolName 注册）
│   │   │   │   ├── ToolGroupSelector.java       # 选择器接口
│   │   │   │   ├── DefaultToolGroupSelector.java # 基于 IntentResult + 弱车载关键词的选择
│   │   │   │   ├── ToolGroupSelectionStatus.java # 四种稳定选择状态
│   │   │   │   └── ToolGroupSelectionResult.java # 选择结果
│   │   │   │
│   │   │   ├── conversation/                  # 会话管理门面
│   │   │   │   ├── ConversationManager.java        # 面向 AIDL 的会话 CRUD 门面
│   │   │   │   ├── ConversationConstants.java      # 统一常量
│   │   │   │   ├── ConversationSessionGateway.java # 可测试抽象接口
│   │   │   │   └── MemoryConversationSessionGateway.java # 生产实现（委托 MemoryOrchestrator）
│   │   │   │
│   │   │   ├── context/                      # TEXT Context 统一输入控制层
│   │   │   │   ├── ContextOrchestrator.java     # prepare / assemble 编排入口
│   │   │   │   ├── ContextFrame.java            # 请求级静态上下文快照
│   │   │   │   ├── ContextPrepareResult.java    # prepare 结果
│   │   │   │   ├── ContextAssemblyRequest.java  # 单轮装配请求
│   │   │   │   ├── ContextAssemblyResult.java   # 最终消息、工具与预算结果
│   │   │   │   ├── ContextAssemblyAttempt.java  # 单次预算/压缩决策
│   │   │   │   ├── ContextContributionDecision.java # produced/included/trimmed 诊断
│   │   │   │   ├── ContextPolicies.java         # 生产 source 集中策略
│   │   │   │   ├── ContextContribution.java     # Provider 统一输出契约
│   │   │   │   ├── TextContextContribution.java
│   │   │   │   ├── MessageContextContribution.java
│   │   │   │   ├── ToolContextContribution.java
│   │   │   │   ├── ContextMessageAssembler.java # 最终 ChatMessage / ToolSpec 装配
│   │   │   │   ├── ContextMessageSequenceValidator.java
│   │   │   │   ├── ContextBudgetPolicy.java      # 模型窗口预算策略
│   │   │   │   ├── ContextBudgetReport.java      # 单轮预算报告
│   │   │   │   ├── ContextTraceRecorder.java     # Provider / 输入来源 Trace
│   │   │   │   └── provider/                    # 8 静态 + 3 动态 Provider
│   │   │   │       ├── RuntimeContextProvider.java
│   │   │   │       ├── PersonaContextProvider.java
│   │   │   │       ├── PromptContextProvider.java
│   │   │   │       ├── UserInputContextProvider.java
│   │   │   │       ├── IntentContextProvider.java
│   │   │   │       ├── ToolGroupContextProvider.java
│   │   │   │       ├── LongTermMemoryContextProvider.java
│   │   │   │       ├── CallerExtraContextProvider.java
│   │   │   │       ├── SessionMemoryContextProvider.java
│   │   │   │       ├── VehicleStateContextProvider.java
│   │   │   │       └── TimeContextProvider.java
│   │   │   │
│   │   │   ├── ai/langchain4j/tool/        # 工具调度系统
│   │   │   │   ├── ToolDispatcher.java     # 反射工具执行器
│   │   │   │   ├── ToolRegistry.java       # 注册中心
│   │   │   │   └── ToolDispatchOutcome.java # 结构化执行结果
│   │   │   │
│   │   │   ├── core/                       # Agent 循环引擎
│   │   │   │   ├── AgentLoopOrchestrator.java  # 非 TEXT / 兼容循环引擎
│   │   │   │   ├── TextAgentLoopOrchestrator.java # TEXT 专用循环，输入由 Context 提供
│   │   │   │   ├── AgentConfig.java            # 人格配置 + Builder
│   │   │   │   ├── AgentLoopState.java         # 状态跟踪
│   │   │   │   ├── AgentLoopContext.java       # 执行上下文
│   │   │   │   ├── AgentResult.java            # 结构化结果
│   │   │   │   ├── ToolExecutionRecord.java    # 工具执行快照
│   │   │   │   ├── component/                  # Agent 循环组件接口
│   │   │   │   ├── preprocessor/               # PreProcessor 实现
│   │   │   │   ├── model/                      # 模型调用器
│   │   │   │   ├── postprocessor/              # 后处理器实现
│   │   │   │   ├── terminator/                 # 循环终止器
│   │   │   │   ├── collector/                  # 结果收集器
│   │   │   │   └── factory/                    # 人格工厂
│   │   │   │
│   │   │   ├── safety/                     # 独立 Tool 执行前安全审核
│   │   │   │   ├── ToolSafetyEngine.java       # 统一审核入口与规则调度
│   │   │   │   ├── SafetyCheckContext.java     # 工具名 + 标准化参数
│   │   │   │   ├── SafetyDecision.java         # ALLOW / DENY / REQUIRE_CONFIRMATION
│   │   │   │   ├── SafetyCheckMode.java        # INITIAL / CONFIRMED_RECHECK
│   │   │   │   ├── SafetyRule.java             # 单条规则接口
│   │   │   │   ├── DefaultSafetyRules.java     # 工具名到规则列表的固定映射
│   │   │   │   ├── confirmation/               # 单 Session 文本确认状态机
│   │   │   │   └── rules/                      # 车控业务安全规则
│   │   │   │       ├── DoorUnlockSafetyRule.java
│   │   │   │       └── ChassisModeSafetyRule.java
│   │   │   │
│   │   │   ├── memory/                     # 记忆系统
│   │   │   │   ├── MemoryOrchestrator.java  # 协调器（含会话管理门面）
│   │   │   │   ├── SessionManager.java      # Session 生命周期（多用户隔离）
│   │   │   │   ├── SessionMemoryStore.java  # Session 持久化（含元数据字段）
│   │   │   │   ├── SessionMemoryIds.java    # memoryId 统一生成工具
│   │   │   │   ├── SessionChatMemoryProvider.java # 按 sessionId 选择 ChatMemory
│   │   │   │   ├── PersistentSessionChatMemory.java # 不按消息数静默淘汰历史
│   │   │   │   ├── SessionHistorySequenceValidator.java # turn/tool exchange 校验与尾部修复
│   │   │   │   ├── UserMemoryContext.java   # 用户记忆聚合
│   │   │   │   ├── LongTermMemoryStore.java # 长期记忆
│   │   │   │   ├── MemoryEntry.java         # 记忆条目
│   │   │   │   ├── MemoryCompressor.java    # 摘要压缩与写回能力
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
└── docs/                                    # 设计、计划、审查与测试文档
    ├── overview/                            # 当前架构与模块现状
    ├── design/                              # 设计说明
    ├── plan/ / plan_overall/                # 阶段计划与总体计划
    ├── review/ / evaluation/                # 源码审查与模块评估
    ├── act_summary/                         # 阶段总结
    └── testresult/                          # 测试结果
```

---

## 4. 核心组件

### 4.1 AIAgentService（主控中枢 - AIDL 服务端）

**文件：** `AIAgentService.kt`

前台 Service，职责包括：

- **AIDL Binder 实现**：完整的对话与会话管理接口集
- `processAgentRequest(AgentRequest)` — 主入口（TEXT → AgentRuntime + TextAgentLoop；IMAGE/VOICE → UNSUPPORTED_INPUT_TYPE；CONTROL → StartListen/StopListen/ClearChatMemory）
  - `createConversation(ConversationRequest)` — 创建新对话（不结束旧会话，响应含 sessionId）
  - `listConversations(String userId)` — 列出用户最近 50 条会话
  - `deleteConversation(String userId, String sessionId)` — 删除会话（消息 + 记录）
  - `switchConversation(String userId, String sessionId)` — 切换活跃对话（只改 is_active，不写 ended_at）
  - `getActiveConversation(String userId)` — 查询当前活跃对话
  - `cancelAgentRequest(String requestId, String reason)` — 协作式取消（CAS 终态抢占，抑制 late result）
  - `registerListener(IAIAgentAidlListener)` / `unregisterListener(...)`
- **AgentRuntime 协调层**：TEXT 请求经 startSession → routeIntentSafely → selectToolGroupsSafely → execute 管线
- **TEXT Persona**：请求只接受 `chat` / `friendly` / `concise`，其他值回退到 `chat`；`PromptContextProvider` 按当前 `personaId` 选择唯一 System Prompt
- **ActiveRequestRegistry**：运行中请求终态管理，timeout/success/failure/cancel 四路通过 CAS 抢占终态，只允许一方发送 listener 响应
- **Trace 元信息**：Trace 创建前补齐 requestId，userId 使用 `request.userId ?: default_user`，记录有效 personaId（normalize 后）
- **Camera 接入**：每 1 秒请求一次前向摄像头抓拍
- **场景识别循环**：抓拍 → SceneMatch 识别 → 场景变化 → AgentLoopOrchestrator 主动响应
- **Listener 回调推送**：`onAIResponse(AgentResponse)` 统一回调
- **TEXT 统一 30 秒端到端 deadline**（经 ActiveRequestRegistry 终态抢占，并可取消同步模型 HTTP Call）

### 4.2 TextAgentLoopOrchestrator（TEXT 专用循环引擎）

**文件：** `core/TextAgentLoopOrchestrator.java`

TEXT 请求的唯一 Agent 执行入口。构造器接收 `AgentConfig`、`ContextMemoryGateway`、`ContextAssemblyGateway`、共享的 `ToolSafetyEngine` 与确认协调器，不持有 PromptManager 或固定工具规格。每轮模型调用使用的消息和工具全部来自 `ContextAssemblyResult`：

```
execute(session, prepareResult)
  → UserMessage 提交推迟到预算和取消检查通过后
  → for i in 0..maxIterations:
      ① ContextOrchestrator.assemble()
           → 动态 Provider + ContextMessageAssembler
           → ContextAssemblyResult(messages, toolSpecifications, budgetReport)
      ② 预算 & 取消检查
      ③ ChatRequest(messages, toolSpecifications) → ModelCaller
      ④ LLM 返回 ToolCall → 整批 Safety 预检
           ├─ ALLOW / DENY → 执行或拒绝结果写回
           └─ REQUIRE_CONFIRMATION → 整批零执行，保存单个原始动作并返回确认文本
      ⑤ LLM 返回文本 → PostProcessor → Terminator → ResultCollector → return
  → max iterations → AgentResult.error()
```

`AgentConfig` 负责 ModelCaller、PostProcessor、Terminator 和 ResultCollector 等执行策略；TEXT 工具规格由 Context 提供，实际分发使用配置中的全局 `ToolRegistry`。`ToolSafetyEngine` 由 Service 统一创建并注入各 AgentLoop，不随 Persona 改变。Prompt、短期记忆、车辆状态、时间和工具规格不再由 TEXT PreProcessor 各自拼装，而是统一通过 Context Provider 进入最终请求。

**会话隔离：** `ContextMemoryGateway` 按 `sessionId` 选择 live ChatMemory，`SessionMemoryContextProvider` 每轮重新读取当前会话快照；create/switch/delete 会话会真实改变下一轮模型可见的短期历史。

### 4.3 ToolRegistry + ToolDispatcher（集中式工具调度）

**文件：** `ai/langchain4j/tool/`

替代了原 10 个 Manager 中 450 行重复的 `hasTool`/`handleToolRequest` 样板代码：

- `ToolDispatcher`：构造时反射扫描目标对象的所有 `@Tool` 方法，建立 工具名→Method 映射
- `ToolRegistry`：管理多个 Dispatcher，`registerAll()` 注册，`dispatch()` / `dispatchWithOutcome()` 路由
- 工具名直接来自 `@Tool(name=...)` 注解，不在源码中手写字符串匹配
- `ToolDispatchOutcome`：区分工具注册、参数解析、反射调用和技术分发状态，并向 Tool Trace 提供目标类、方法和失败信息

`ToolDispatchOutcome` 描述的是“工具方法是否完成技术调用”，不等同于“车辆动作已经生效”。当前 Demo 工具仍主要返回字符串；真实车控需要进一步引入结构化动作回执和执行后状态确认。

### 4.4 PromptManager（外部化 Prompt 管理）

**文件：** `prompt/PromptManager.java` + `assets/prompts/`

所有 Prompt 文本外置为 `.txt` 模板文件，使用 LangChain4j `{{variable}}` 语法：
- 11 个模板文件，按 system / task / user / messages 分类
- `PromptManager` 懒加载 + 缓存 + 渲染
- `PromptSelector` 预留动态切换接口

### 4.5 MemoryOrchestrator（四层记忆系统）

**文件：** `memory/`

| 层 | 组件 | 职责 |
|----|------|------|
| Session 管理 | SessionManager + SessionMemoryStore | 会话生命周期、元数据与 SQLite 消息持久化 |
| 长期记忆 | LongTermMemoryStore + MemoryExtractor | 跨 Session 持久化用户偏好/事实（SQLite） |
| 压缩能力 | MemoryCompressor + MemoryOrchestrator | Context 超预算时按完整 turn 摘要，CAS 原子写回并重读后最多二次装配一次 |
| 用户隔离 | UserMemoryContext | userId 维度隔离，多用户支持 |

### 4.6 TraceManager（全链路追踪）

**文件：** `trace/`

基于 OpenTelemetry + Phoenix 的追踪系统：
- 目标树为 `agent.request → agent.loop → agent.iteration → context / gen_ai / tool → response.dispatch`
- Context 记录 prepare/assemble、Provider、fragment、message、toolset、裁剪、压缩和 Token 估算误差信息
- Tool 记录 safety_check、dispatch、result_writeback 等阶段诊断
- Span 携带模型、消息、工具 schema、Token、HTTP、工具参数与结果等调试信息
- 开发环境通过 `adb reverse tcp:6006 tcp:6006` 连接 PC 端 Phoenix
- `TraceConfig.production()` 一键关闭（全局 no-op）

Demo `FULL_DEBUG` 在业务层直接记录完整文本、参数、结果和工具 schema，不主动截断。Phoenix/exporter 对超长 attribute 的设备侧展示仍需验收。

### 4.7 ConversationManager（会话管理门面）

**文件：** `conversation/`

面向 AIDL 的会话 CRUD 门面，通过 `ConversationSessionGateway` 接口解耦测试与生产：

| 组件 | 说明 |
|------|------|
| `ConversationConstants` | 统一常量（DEFAULT_USER_ID/DEFAULT_PERSONA_ID/OP_CREATE/OP_DELETE/OP_SWITCH） |
| `ConversationSessionGateway` | 6 方法接口：createConversationSession / listSessions / getActiveSession / getSession / switchSession / deleteSession |
| `MemoryConversationSessionGateway` | 生产实现，委托 MemoryOrchestrator + SessionManager + SessionMemoryStore |
| `ConversationManager` | CRUD 门面：create/list/delete/switch/getActive，含 toConversationInfo 元数据映射 |

**会话元数据：** `ConversationInfo` 包含 11 个字段（userId/sessionId/personaId/title/active/createdAt/updatedAt/endedAt/messageCount/tokenEstimate/compressionCount），`endedAt=0` 表示对话尚未结束。

**底层支持：**
- `SessionMemoryStore` 当前 DB_VERSION 为 3：v2 为 sessions 表补充 title/persona_id/source_app/updated_at，v3 将新短期记忆 key 收敛为 sessionId（Demo 阶段不自动合并旧 key）
- `SessionManager` active session 从单个字段改为 `ConcurrentHashMap<userId, ActiveSessionState>`，`createConversationSession` 不结束旧会话（区别于 `startNewSession`）
- `SessionChatMemoryProvider` 以 `sessionId` 作为短期记忆 key；用户与 Persona 归属由会话元数据维护，对话切换会真实改变 LLM 可见历史

### 4.8 Vehicle*Manager 系列（车控工具）

8 个车辆模块，其中 7 个模块共提供 **44 个 @Tool 方法**，全部使用 `ToolRegistry` 统一调度；`VehicleSpeedManager` 仅保留状态查询能力，不再向模型暴露修改车速的工具。
每个 Manager 的 `@Tool` 方法**委托给 `VehicleStateMachine`** 执行状态变更和参数校验。

| 模块 | 工具数 | 覆盖功能 |
|------|-------|---------|
| VehicleDoorManager | 1 | 车门闭锁/解锁 |
| VehicleWindowManager | 11 | 车窗、天窗、遮阳帘、除霜、后视镜加热 |
| VehicleSeatManager | 11 | 座椅加热、通风、按摩、方向盘加热 |
| VehicleAcManager | 15 | 空调开关、温度、风量、ECO、负离子、内外循环 |
| VehicleChassisManager | 1 | 底盘模式（普通/越野/雪地） |
| VehicleFragManager | 2 | 香氛类型、浓度 |
| VehicleSpeedManager | 0 | 车辆速度状态查询（非模型 Tool） |
| VehicleDMSManager | 3 | 驾驶员疲劳、分心、情绪 |

### 4.9 ActiveRequestRegistry（请求取消与终态管理）

**文件：** `runtime/ActiveRequest.java` / `runtime/ActiveRequestRegistry.java`

为当前单 Session 场景提供 TEXT 单槽位准入、统一 deadline、底层模型调用取消和唯一终态：

- `ActiveRequest`：不可变请求快照（requestId/sessionId/userId/personaId/clientMessageId） + CAS 终态标记
- `ActiveRequest.TerminalState`：RUNNING → COMPLETED / CANCELLED / TIMEOUT / FAILED（只有 RUNNING→终态一次有效）
- `ActiveRequestRegistry`：原子单槽位；第二个 TEXT 请求立即返回 BUSY，不进入 Runtime 或排队；完成 requestId 缓存 60 秒防重放
- `RequestDeadline`：从 Service 准入时开始计算绝对 30 秒期限，贯穿 Runtime、Context、AgentLoop 和模型 adapter
- `RequestCallRegistry`：同步 OkHttp `Call` 按 requestId 登记；cancel / timeout 会调用 `Call.cancel()`，并处理“先取消、后登记”的竞态
- **四路终态抢占**：cancel AIDL、timeout runnable、worker success、worker exception 通过 `tryComplete` 竞争终态；只有首次抢占成功方允许发送 listener 响应
- **槽位释放**：终态响应可以先发出，但执行体真正退出前不释放单槽位，避免旧调用与新调用重叠

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
- **ToolGroup 选择**：基于 IntentResult 调用 ToolGroupSelector，生成带 `SELECTED / CHAT_ONLY / CLARIFICATION_REQUIRED / FAILED_CLOSED` 状态的选择结果
- **Trace 写入**：将 intent / toolgroup / clientMessageId 信息写入 Trace root span 的 11 个 attribute
- **异常边界**：Router 异常降级为 `UNKNOWN`；Selector 返回 null、状态不一致或抛异常时统一 `FAILED_CLOSED`，在 Context / 模型前终止
- **执行封装**：`execute()` / `timeoutResult()` / `errorResult()` / `cancelledResult()` 统一封装 RuntimeResult（全部含 userId/personaId/clientMessageId 元信息）
- **服务端取消支持**：`cancelledResult(session, reason)` → RuntimeResult.cancelled → Mapper 按状态码 CANCELLED 映射
- **Context 编排**：`execute(session)` 内先调 `ContextOrchestrator.prepare(session, cancelChecker)`，成功后把 `ContextPrepareResult` 交给 TEXT executor；Context 失败、取消和运行异常统一映射为 RuntimeResult

TEXT 请求执行路径：

```
handleTextRequest()
  → ActiveRequestRegistry.tryAcquire(RequestAdmission)  ← 单槽位 / 重复 requestId 门禁
  → traceManager.startAgentRequest() → agentRuntime.startSession(deadline)
  → routeIntentSafely() → selectToolGroupsSafely()
  → writeIntentToTrace() → writeToolGroupsToTrace() → sessionFactory.create() → writeRequestMetaToTrace()
  → agentRuntime.execute()
     → contextOrchestrator.prepare(session, contextCancelChecker)
     → prepare 失败/取消映射，成功则再次检查 cancelled_before_agent_loop
     → chatExecutor.execute(session, contextPrepareResult)
  → tryComplete(COMPLETED/FAILED) → runtimeResponseMapper.toAgentResponse()
  → notifyAIAgentListeners() → session.close()
```

### 4.12 IntentRouter（轻量意图标签器）

**文件：** `intentrouter/`

非 LLM 的纯关键词 + 正则匹配意图系统。它不直接执行工具，但结果会进入 `DefaultToolGroupSelector`，实际影响 TEXT 模型可见工具：

- `KeywordIntentRouter`：使用 LinkedHashMap + 正则实现，当前约 56 个业务关键词 + 少量正则，空文本返回 UNKNOWN，无关键词返回 CHAT
- 11 种 IntentTag：`CHAT` / 7 个车辆域 / `VISION_QA` / `WEATHER` / `UNKNOWN`
- 输出 `IntentResult` 含 6 个字段：`intentTag` / `confidence` / `matchedKeywords` / `normalizedText` / `sourceInputType` / `debugReason`
- 优先级稳定（LinkedHashMap 保证顺序），不调用 LLM、不引用 tool registry
- 当前为单标签 winner 设计；复合领域指令和平分场景只保留一个结果，复杂任务拆分仍是后续能力

### 4.13 ToolGroup（工具分组与选择）

**文件：** `toolgroup/`

将当前 46 个 `@Tool` 方法按功能域分组。Selector 本身只生成选择结果，但 TEXT 生产链中的 `ToolGroupContextProvider` 会把候选工具名解析为真实 `ToolSpecification`，因此当前 ToolGroup 已实际参与控制 TEXT 请求的模型可见工具：

- 13 个 `ToolGroupId`：11 个基础/领域组 + `COMMON_VEHICLE_GROUP`（车辆聚合）+ `ALL_SAFE_DEMO_GROUP`（全量）
- `ToolGroupRegistry.defaultRegistry()`：全量 46 个 toolName 注册，支持按 toolName 反查、多组合并去重
- `DefaultToolGroupSelector`：明确意图只选择对应业务组；普通 CHAT / UNKNOWN 使用空工具；弱车控表达要求先澄清
- `ToolGroupSelectionResult`：使用 `SELECTED / CHAT_ONLY / CLARIFICATION_REQUIRED / FAILED_CLOSED` 稳定状态，不再解析原因文本决定权限
- Selector 返回 null、抛异常或普通聚合组时，Runtime 失败关闭；结构合法的 `ALL_SAFE_DEMO_GROUP` 作为 `allToolsFallback` 允许进入 Context
- `ToolGroupContextProvider` 在 `SELECTED` 状态解析规格，并区分 `SELECTED` 与 `ALL_FALLBACK`；其他状态输出空工具
- 聚合组 `riskLevel` 遵循最高风险上浮规则（`COMMON_VEHICLE_GROUP` 和 `ALL_SAFE_DEMO_GROUP` 均为 HIGH）
- ToolGroup 不负责执行工具；实际调用由 ToolSafetyEngine 先审核，再由 ToolRegistry / ToolDispatcher 完成

### 4.14 ContextOrchestrator（上下文模块）

**文件：** `context/`

Context 是当前 TEXT 模型输入的统一控制层，而不是旁路观测器。在 `AgentRuntime.execute()` 中，`ContextOrchestrator.prepare()` 先采集请求级静态信息并生成 `ContextPrepareResult`；随后 `TextAgentLoopOrchestrator` 每轮调用 `assemble()`，经 `ContextMessageAssembler` 生成最终 `ChatRequest.messages()` 和 `toolSpecifications()`。

#### Contribution 输出与 Provider 链

Provider 分为 REQUEST_STATIC（8 个，prepare 执行）和 ITERATION_DYNAMIC（3 个，assemble 执行）：

- **REQUEST_STATIC**：Runtime、Persona、Prompt、UserInput、Intent、ToolGroup、LongTermMemory、CallerExtra
- **ITERATION_DYNAMIC**：SessionMemory、VehicleState、Time

每个 Provider 输出 `List<ContextContribution>`。`ContextPolicies` 集中定义 lifecycle、required、visibility、trust、priority 和 trim eligibility，Orchestrator 会校验生产 Provider 与 Contribution 是否发生策略漂移。旧 `ContextSection`、`ContextSectionType` 和 `ContextDebugInfo` 已删除。

#### 消息装配

`ContextMessageAssembler` 根据 iteration 参数决定是否包含 CURRENT_USER：
- **iteration 0**：SystemMessage → Context Data（ContextDataFormatter envelope） → SessionMemory → CurrentUser
- **iteration 1+**：SystemMessage → Context Data → SessionMemory（不含 CURRENT_USER）

#### 取消与失败保护

取消检查覆盖 Service worker、prepare 前及 Provider 之间、prepare 后、assemble 前后、模型和工具边界；Service 返回阶段还会抑制取消后的 late success。终态由 `ActiveRequestRegistry` 的 CAS gate 保证只完成一次。

```
Service worker → Context.prepare → Runtime gate → iteration/assemble → model/tool → response
      │              │              │                  │              │          │
      └──────────────┴──────────────┴──── cancel checks ┴──────────────┘          └─ CAS 终态
```

#### 核心组成

| 组件 | 职责 |
|------|------|
| `ContextOrchestrator` | `defaultForText()` 装配 8 个静态 + 3 个动态 Provider，执行 prepare / assemble |
| `ContextFrame` | 保存请求事实与静态 Contribution，供每轮 assemble 复用 |
| `ContextPrepareResult` | prepare 的 Frame、当前用户消息、Provider outcome、取消与错误信息 |
| `ContextMessageAssembler` | 纯函数装配 System、Context Data、SessionMemory、CurrentUser 与 ToolSpecification |
| `ContextMessageSequenceValidator` | 校验唯一 System、当前用户唯一性和 ToolExchange 原子序列 |
| `ContextBudgetPolicy` | qwen-turbo Demo 窗口 32768，预留输出 2048、安全余量 1024，最大输入 29696 tokens |
| `ContextPolicies` | 集中管理所有生产 source 的生命周期、required、可见性、信任、优先级和裁剪资格 |
| `ContextAssemblyAttempt` | 保存裁剪结果、压缩建议、SessionMemory 目标预算和最终 Contribution 决策 |
| `ContextTraceRecorder` | 记录 prepare/assemble、Provider、fragment、message、toolset、预算与压缩诊断 |
| `ContextMemoryGateway` | Context 与 Memory 间窄接口，提供 Session/长期记忆快照和压缩协议 |

#### 11 个 Provider

| Provider | 生命周期 | required | 输出目标 |
|----------|---------|----------|---------|
| RuntimeContextProvider | REQUEST_STATIC | TEXT 始终 required | POLICY_ONLY 诊断 |
| PersonaContextProvider | REQUEST_STATIC | optional | POLICY_ONLY |
| PromptContextProvider | REQUEST_STATIC | 始终 required | SYSTEM Message |
| UserInputContextProvider | REQUEST_STATIC | 始终 required | CURRENT_USER Message |
| IntentContextProvider | REQUEST_STATIC | optional | POLICY_ONLY |
| ToolGroupContextProvider | REQUEST_STATIC | 仅 `SELECTED` 状态 required | ToolContextContribution |
| LongTermMemoryContextProvider | REQUEST_STATIC | optional | CONTEXT_DATA |
| CallerExtraContextProvider | REQUEST_STATIC | optional | CONTEXT_DATA |
| SessionMemoryContextProvider | ITERATION_DYNAMIC | TEXT 始终 required | SESSION_MEMORY Message |
| VehicleStateContextProvider | ITERATION_DYNAMIC | 需 vehicle_status | CONTEXT_DATA |
| TimeContextProvider | ITERATION_DYNAMIC | optional | CONTEXT_DATA |

#### 异常处理策略

- required Provider 只有 `SUCCESS` 才放行；返回 `FALLBACK` / `FAILED` 或抛异常都会中止当前请求
- optional Provider 可降级，状态和错误写入 Provider outcome / Trace，其余 Provider 继续执行
- assemble 会拒绝重复 System、缺少当前用户、孤立 ToolResult、ToolExchange 不闭合和工具 schema 冲突
- 超预算时先按优先级删除允许裁剪的 optional Context Data，再请求 Memory 按完整 turn 摘要并 CAS 写回，随后重读动态 Provider 并最多二次 assemble 一次
- Prompt、CURRENT_USER、SessionMemory、摘要、required 数据和工具规格不会被普通裁剪；恢复后仍超限才返回 `CONTEXT_BUDGET_EXCEEDED`
- 失败结果不会调用模型，也不会在预算通过前写入当前用户消息

### 4.15 ToolSafetyEngine（Tool 执行前安全审核）

**文件：** `safety/`

ToolSafetyEngine 是独立、轻量、确定性的车控安全审核入口。AgentLoop 在 ToolExecutor 前对整批 Tool 完成一次 `INITIAL` 审核；确认请求使用同一个 Engine 的 `CONFIRMED_RECHECK` 再读实时状态，不依赖完整的 `AgentLoopContext`。

- `DefaultSafetyRules` 使用固定的 `Map<String, List<SafetyRule>>` 保存工具与规则的对应关系，后续新增十余条规则时仍可直接定位和测试
- 已注册安全工具若配置为空规则列表、null 规则或非法工具名，会在 Engine 创建时直接失败，避免配置错误静默变成默认放行
- 当前 `DoorUnlockSafetyRule` 在静止解锁时要求二次确认；上锁直接放行，行驶中解锁直接拒绝
- 当前 `ChassisModeSafetyRule` 在静止切换时要求二次确认；行驶中直接拒绝
- 只接受 trim 后完全等于“确认执行”或“取消执行”的普通 TEXT；PendingAction 仅存内存、TTL 30 秒、原子消费且只保存原始 Tool 与参数
- 确认前重新读取车速；状态变化、过期、取消、重复或并发确认均不执行；多 Tool 确认批次零部分执行
- VOICE / scene / legacy 路径遇到 REQUIRE_CONFIRMATION 时返回确认通道不可用，不会 dispatch
- 没有专用规则的低风险工具默认 ALLOW；有专用规则但参数、车速不可用或规则异常时返回稳定的 DENY 原因码
- DENY 时不执行工具，而是把结构化拒绝结果作为 ToolResult 写回模型，由模型自然向用户说明原因
- `VehicleStateMachine` 继续负责执行阶段的参数校验与状态收敛，安全模块只承担执行前业务判断
- 虚拟车辆默认以静止状态（0 km/h）启动；行驶状态由 Demo 测试或后续车辆状态接入更新，不向模型暴露修改车速的 Tool

### 4.16 SoaService（SOA 总线封装）

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
    ├─ toolSafetyEngine = ToolSafetyEngine(vehicleStateMachine, defaultRules) ← 全 Persona 共享
    ├─ vl = VlManager(this, promptManager)
    ├─ toolRegistry.registerAll(9 tool providers) ← 注册 46 个模型工具（SpeedManager 不注册）
    ├─ memoryOrchestrator(...)             ← 记忆系统初始化
    ├─ traceManager = TraceManager(...)    ← Trace 系统初始化
    ├─ chatOrchestrator = AgentLoopOrchestrator(..., toolSafetyEngine) ← 兼容链路共用安全引擎
    ├─ conversationManager = ConversationManager(...)  ← 会话管理门面
    ├─ contextOrchestrator = ContextOrchestrator.defaultForText(...) ← Context 模块
    ├─ textOrchestrator = TextAgentLoopOrchestrator(config, memoryOrch, contextOrch, toolSafetyEngine) ← TEXT 专用循环
    ├─ agentRuntime = AgentRuntime(
    │      AgentExecutor { session, prepared → textOrchestrator.execute(session, prepared) },
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
    ├─ 规范化 requestId / userId / personaId，创建绝对 30 秒 RequestDeadline
    ├─ ActiveRequestRegistry.tryAcquire(RequestAdmission)
    │    └─ BUSY / DUPLICATE → 直接返回，不进入 Runtime
    ├─ traceManager.startAgentRequest() → agent.request root span
    ├─ ConfirmationTextParser
    │    ├─ “确认执行 / 取消执行” → 确认分支，不经过 IntentRouter 和 LLM
    │    └─ 普通文本 → 取消旧 PendingAction，继续主链
    ├─ agentRuntime.startSession(request, traceContext, deadline)
    │    ├─ KeywordIntentRouter → IntentResult
    │    ├─ DefaultToolGroupSelector → SELECTED / CHAT_ONLY / CLARIFICATION / FAILED_CLOSED
    │    └─ RequestSession（不可变请求事实快照）
    └─ timeout runnable + TEXT worker + RequestExecutionContext
    │
    ▼
agentRuntime.execute(runtimeSession)
    ├─ 选择状态 gate：澄清直接返回，失败关闭直接终止
    ├─ agent.loop span
    ├─ contextOrchestrator.prepare(session, cancelChecker)
    │     8 个 REQUEST_STATIC Provider
    │     → ContextFrame + CurrentUser + Provider outcomes
    ├─ prepare 失败 / 取消 / deadline 映射
    └─ textOrchestrator.execute(session, prepareResult)
         └─ for iteration = 0..maxIterations
              ├─ agent.iteration span
              ├─ contextOrchestrator.assemble(request)
              │    3 个 ITERATION_DYNAMIC Provider
              │    → ContextMessageAssembler → 序列校验 + Token 预算
              │    → 可选数据裁剪；必要时 Memory 摘要压缩并重新装配一次
              │    → ContextAssemblyResult(messages, toolSpecifications)
              ├─ iteration 0 在预算 / 取消通过后提交 CurrentUser 到 SessionMemory
              ├─ ChatRequest(messages, toolSpecifications) → gen_ai.chat
              │    ├─ ToolCall → 整批 tool.safety_check
              │    │    ├─ ALLOW → tool.dispatch → ToolResult 写回
              │    │    ├─ DENY → 拒绝 ToolResult 写回
              │    │    └─ REQUIRE_CONFIRMATION → 零执行并创建 30 秒 PendingAction
              │    └─ 文本 → PostProcessor → Terminator → ResultCollector
              └─ ToolResult 后进入下一轮 assemble，普通文本形成 AgentResult
    │
    ▼
ActiveRequestRegistry.tryComplete(terminalState)  ← success / failure / timeout / cancel 竞争唯一终态
    ├─ runtimeResponseMapper.toAgentResponse() → notifyAIAgentListeners()
    ├─ TraceResponseDispatcher → response.dispatch → root span end
    ├─ RequestCallRegistry.clear(requestId)
    └─ Worker 完全退出后 release 单槽位
```

---

## 6. 当前开发状态

下表中的“完成”指当前 Demo / 代码边界已经落地，不等同于真实车辆量产验收完成。

| 模块 | 阶段状态 | 当前结论与主要缺口 |
|------|----------|--------------------|
| 对外 AIDL 协议 | 基本完成 | 主请求、会话 CRUD、取消与 Listener 接口已落地；exported Service 的签名权限、Binder caller 身份与确认归属仍需收口 |
| AgentRuntime | 基本完成 | TEXT 已具备单槽位准入、30 秒 deadline、HTTP Call 取消、唯一终态与 requestId 防重放；VOICE/SCENE 等仍使用兼容链路 |
| TEXT Context | 代码完成、部分设备验收 | 8 静态 + 3 动态 Provider；独占消息和工具规格；集中策略、裁剪、压缩重试和 Legacy 清理已完成；Automotive 模拟器 AIDL、基础 TEXT、会话/用户隔离与 52 条消息长历史已通过，压缩、工具和 Phoenix 专项仍待验收 |
| TEXT AgentLoop | 基本完成 | 多轮循环、整批 Safety 预检、确认与状态复核已接通；模型同步 HTTP Call 可取消，真实车控执行仍未接入 |
| IntentRouter | Demo 完成 | 11 类关键词/正则意图，低成本且可解释；当前为单标签 winner，复合领域和查询/控制语义尚未强类型化 |
| ToolGroup | P0 基线完成 | 13 个工具组；明确意图最小暴露，普通聊天空工具，不确定/异常/聚合结果失败关闭 |
| ToolRegistry / Dispatcher | 基本完成 | 46 个 `@Tool` 统一反射注册和调度；TEXT 使用结构化 ToolDispatchOutcome 描述技术分发结果，但尚未形成统一车辆动作回执 |
| Tool Safety Engine | P0 基线完成 | 统一入口、HIGH 规则漏配拒绝、文本确认、30 秒 PendingAction、确认前复核和最多一次执行已接通；仍不是量产功能安全方案 |
| Prompt / Persona | 基本完成 | 11 个模板；支持 chat/friendly/concise TEXT System Prompt；更复杂动态策略未实现 |
| Session 记忆 | 代码完成、基础设备验收通过 | SQLite 持久化、会话 CRUD、完整历史和 ToolExchange 校验已接通；Automotive 模拟器实测 52 条消息连续有序、首尾保留，含 ToolExchange 的超长会话和目标车机仍待验收 |
| 长期记忆 | 可用 | 提取、存储、按 userId 注入和 FALLBACK 状态已接通；提取仍位于主响应路径，相关性、衰减与用户修正策略可继续完善 |
| Memory 压缩 | 代码完成 | Context 超预算时按完整 turn 摘要、CAS 写回、动态重读并最多重装配一次；真实摘要模型待设备验收 |
| Trace | 代码完成 | 主 Trace 树、Context 来源、最终消息、工具 schema、裁剪/压缩和 Tool 阶段已接通；业务层全文输出，Phoenix 设备显示待验收 |
| 虚拟车辆状态机 | Demo 完成 | 8 个状态子系统、参数校验和状态收敛已用于 Demo 车控 |
| SoaService 真车通信 | 未完成 | 方法体仍为空；当前车控结果来自 VehicleStateMachine，不代表真实车辆执行 |
| 动作语义闭环 | 待完善 | ToolGroup 已限制模型可见工具，Safety 已控制风险动作；Dispatch 前本轮授权复核、ActionReceipt、状态回读和最终答复真实性仍待建立 |
| 场景 / VLM / VR | 部分完成 | 已有模型、Camera SDK 和闭源 VR/TTS 适配；依赖目标设备、外部服务与实车验收 |
| UI | 不在本项目范围 | 仅保留无界面的调试启动 Activity；业务 UI 由 Launcher / 调用方提供 |

### 6.1 Context 当前剩余边界

- Token 预算仍使用完整结构的启发式估算，不是 Qwen 精确 tokenizer；Trace 已提供 estimated/actual/delta/ratio，需用至少 10 个真实请求校准。
- 压缩摘要当前以头部 `【对话摘要】` UserMessage 持久化，Provider 会把它分离为 UNTRUSTED Context Data；未来可单独迁移为 SQLite 结构化字段。
- Runtime/Context 已支持 allToolsFallback，但默认 Selector 当前对普通 UNKNOWN 返回 CHAT_ONLY，不主动触发全量兜底；是否改变匹配规则属于独立 ToolGroup 策略任务。
- FULL_DEBUG 在业务层不截断；Phoenix/exporter 是否限制超长 attribute 仍需设备验证。
- Context 当前只独占 TEXT 输入；VOICE、SCENE、VLM 等兼容链路仍保留旧 AgentLoop/PreProcessor。

### 6.1.1 Debug Eval 适配边界

- Debug APK 提供独立的 `IAIAgentEvalDebug` 环境控制入口，用于 TestApp 获取唯一虚拟车辆状态机的快照、reset、原子 Patch 与临时租约；它不属于主业务 AIDL，也不承担 Dataset、Judge 或 Report。
- Eval case 的跨系统关联复用 `clientMessageId`，根 Trace 额外记录环境是否处于租约及请求开始时的 revision。AgentResponse、状态快照与 Trace 分别保持结果、环境与观测事实边界。
- Release variant 不合并 Eval Debug Service，leaseToken 只在 Android 内部传递，不能进入 Prompt、Trace、Logcat 或电脑端结果。
- 当前仅完成 AIAgent 侧编译与 JVM 验证；需待 TestApp Bridge 和电脑端 Eval 项目完成后关闭真实 Binder、Phoenix 与安全确认的三方设备验收。

### 6.2 Agent 当前设计边界

- **工具可见性不等于执行授权**：Context 会按 ToolGroup 生成本轮 ToolSpecification，但 Dispatch 点尚未再次核验模型请求的工具是否属于本轮授权集合。
- **技术调用成功不等于车辆目标完成**：`ToolDispatchOutcome` 能证明注册、解析与反射调用状态；真实车控仍需要 `ActionReceipt`、SOA 回执和状态回读。
- **最终文本尚未由动作证据强约束**：当前主要依靠 Prompt 要求模型如实回复，后续应按 APPLIED / REJECTED / FAILED / PARTIAL / UNKNOWN 约束最终语义。
- **Intent 仍是单领域标签**：适合单目标 Demo 指令，复合动作、查询与控制区分、条件动作需要独立任务语义层。
- **TEXT 是新主链，其他输入仍为兼容链**：后续应先收敛共享的“授权 → Safety → Dispatch → 回执 → Trace”管线，再按需要迁移 IMAGE / VOICE / SCENE。

完整设计评估见 [Agent 设计与架构评估报告](docs/overview/agent-design-and-architecture-evaluation.md)。

### 6.3 当前验证基线

- 自动门禁已通过 62 个测试类、354 个 JVM 测试，以及 Debug APK 构建和 Android Lint。
- 已验证完整历史、裁剪、压缩重装配、stale CAS、allToolsFallback、结构化 Tool outcome、PostProcessor 写回和 FULL_DEBUG 长正文。
- Automotive 模拟器已验证外部 TestApp AIDL 绑定、真实 DashScope TEXT 回复、同 Session 记忆召回、新 Session 隔离、不同用户长期记忆隔离，以及 SQLite 52 条消息不截断且序列错误为 0。
- 尚未完成外部验收：持久化 ToolExchange 长会话、真实超预算摘要、同 sessionId 切换 user、快速请求终态、异常工具/安全拒绝、真实 Qwen token 偏差、Phoenix 长字段与父子 Span 展示、目标车机和真车 SOA 控制。

---

## 7. 本地配置、构建与调试

### 7.1 环境要求

- Android Studio / Android SDK 35
- JDK 17
- Windows PowerShell（仓库自带 `gradlew.bat`）
- 目标运行环境为 Android 13+ 车机或具备相应系统能力的测试设备

### 7.2 本地配置

复制 `local.properties.example` 中需要的字段到本机 `local.properties`。不要提交真实密钥。

```properties
sdk.dir=D:\\path\\to\\Android\\Sdk
dashscope.api_key=your_dashscope_key
weather.api_key=your_weather_key
```

如目标车机要求平台签名，可在项目根目录提供 `platform.jks`，并补充 `signing.storePassword`、`signing.keyAlias`、`signing.keyPassword`。普通本地 Debug 构建在未提供该文件时使用 Android 默认 Debug 签名。

### 7.3 常用命令

```powershell
# JVM 单元测试
.\gradlew.bat testDebugUnitTest

# 构建 Debug APK
.\gradlew.bat assembleDebug

# Android Lint
.\gradlew.bat lintDebug

# 安装并通过无界面调试 Activity 启动前台 Service
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.hirain.aiagent/.MainActivity
```

启用 Phoenix 调试前，先确保 PC 端服务监听 6006 端口：

```powershell
adb reverse tcp:6006 tcp:6006
```

项目申请了多项车机系统级权限，并依赖 Camera / VR 等厂商 JAR；在普通手机或未配置系统签名的设备上，部分能力不可用属于预期边界。

---

## 8. 延伸文档

- [Context 模块现状、功能与完成度审计](docs/overview/context-module-overview.md)
- [自研模块与 LangChain4j 边界](docs/overview/aiaagent-langchain4j-boundary-and-completion-overview.md)
- [ToolGroup 模块概览](docs/overview/toolgroup-module-overview.md)
- [Memory 模块概览](docs/overview/memory-module-overview.md)
- [Trace 模块概览](docs/overview/trace-module-overview.md)
- [Agent 设计与架构评估（当前）](docs/overview/agent-design-and-architecture-evaluation.md)
- [Agent 架构与运行流程历史评估](docs/overview/agent-architecture-and-runtime-flow-evaluation.md)
- [Tool Safety 当前实现与边界](docs/overview/tool-safety-policy-engine-current-state.md)
- [三个 P0 改进计划与实施状态](docs/plan/2026-07-13-agent-p0-runtime-tool-safety-improvement-plan.md)
- [Context Full Control 设计](docs/design/2026-07-11-context-full-control-design.md)

**当前结论：** AIAgent 已形成“确定性控制面 + 模型驱动 Tool Loop”的车载领域单 Agent 主链；AIDL、TEXT Runtime、Context 输入控制、工具调度、Prompt、会话记忆、安全确认和 Trace 已可协同运行。Context 长会话预算恢复已完成代码与自动化门禁，Automotive 模拟器也已通过基础 AIDL、TEXT、隔离和 52 条 SQLite 历史验收。下一阶段应优先完成本轮工具执行授权、结构化动作回执、执行后状态确认、最终答复真实性和 AIDL 调用方身份，再推进真实 SOA、目标车机故障评测、Phoenix 展示与真实 Qwen Token 校准。
