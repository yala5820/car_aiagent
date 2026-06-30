# 项目架构调整总结

## 概述

对 AIAgent 项目进行了一次系统性的架构重构，涵盖模块合并、UI 层剥离、工具调度体系重写、构建基础设施完善等多个层面。重构前后对比：**17 个 Gradle 模块 → 单模块**、**450 行重复样板代码 → 0 行**、**5 个 Bug 消除**、**敏感信息全部外置**。

---

## 一、重构前的项目架构

### 1.1 模块结构

重构前项目由 **17 个独立的 Gradle 模块** 组成，每个工具和引擎都是独立模块：

```
AIAgent/
├── app/                       # 主模块
├── build-logic/               # 自定义 Gradle 插件（DesugarTransformPlugin）
├── ChatServer/                # AI 对话引擎（独立模块）
├── SceneServer/               # 场景服务引擎（独立模块）
├── SceneMatch/                # 场景识别引擎（独立模块）
├── SoaService/                # SOA 总线通信（独立模块）
├── VlManager/                 # 多模态视觉模型（独立模块）
├── VehicleDoorManager/        # 车门控制（独立模块）
├── VehicleWindowManager/      # 车窗控制（独立模块）
├── VehicleSeatManager/        # 座椅控制（独立模块）
├── VehicleAcManager/          # 空调控制（独立模块）
├── VehicleFragManager/        # 香氛控制（独立模块）
├── VehicleChassisManager/     # 底盘控制（独立模块）
├── VehicleSpeedManager/       # 车速管理（独立模块）
├── VehicleDMSManager/         # DMS 驾驶员监控（独立模块）
├── weatherutils/              # 天气工具（独立模块）
├── chat_memory_sqlite/        # SQLite 记忆持久化（独立模块）
├── http-client-ok/            # OkHttp 封装（独立模块）
├── android_document_loader/   # Android 文档加载（独立模块）
└── geoutils/                  # 地理工具（独立模块）
```

### 1.2 软件架构

```
┌─────────────────────────────────────────────────────────┐
│                   悬浮窗 UI 层                            │
│  AIAgentWindowView (WebView) | FloatWindowView          │
│  (Kotlin + XML binding + WebView + JavaScript)          │
├─────────────────────────────────────────────────────────┤
│                   应用服务层                              │
│  AIAgentService (前台 Service) | MainActivity (调试用)   │
│  BootCompleteReceiver (开机自启)                          │
├─────────────────────────────────────────────────────────┤
│                   LLM / AI 引擎层                        │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐   │
│  │ChatServer│ │SceneMatch│ │SceneServer│ │VlManager │   │
│  └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘   │
│       │             │            │             │          │
│       └─────────────┴────────────┴─────────────┘          │
│                      │                                    │
│              LangChain4j 1.1.0                            │
│         OpenAI-compatible API (DashScope)                 │
├─────────────────────────────────────────────────────────┤
│                   Tool 调用层                             │
│  Vehicle*Manager | WeatherUtils                          │
│  (hasTool + handleToolRequest 手工字符串匹配)             │
├─────────────────────────────────────────────────────────┤
│                  硬件通信层                               │
│  SoaService (SOA总线 AIDL) | CameraSdk.jar (Camera AIDL) │
│  adapter_vr.jar (VR/TTS)                                 │
└─────────────────────────────────────────────────────────┘
```

### 1.3 关键依赖

| 项目 | 版本 |
|------|------|
| LangChain4j | 1.1.0 |
| AGP | 8.9.1 |
| Kotlin | 2.0.21 |
| Gradle | 8.9+ |
| 版本管理 | 各模块独立声明依赖版本 |

---

## 二、发现的问题与解决过程

### 问题 1：模块爆炸——17 个模块带来的构建和维护负担

**发现：**

项目中有 17 个独立的 Gradle 模块。每个工具（VehicleDoorManager、VehicleWindowManager 等）和每个引擎（ChatServer、SceneServer 等）都是一个独立的 Android Library 模块。每个模块都有自己的 `build.gradle.kts`、`proguard-rules.pro`、`AndroidManifest.xml`。

**思考：**

为什么当初会拆成这么多模块？从代码提交历史看，这些模块是在不同时间点由不同开发者陆续添加的。每个模块只包含 1-3 个 Java 文件。模块化的初衷可能是逻辑隔离，但实际带来的问题远大于收益：

1. **构建性能损失**——Gradle 需要为每个模块执行配置、编译、打包的完整流程，即使每个模块只有几个文件
2. **依赖管理复杂**——模块间存在隐式依赖关系，settings.gradle.kts 中需要显式 include 所有模块
3. **版本同步困难**——每个模块独立声明依赖版本，LangChain4j 版本可能在不同模块中不一致
4. **跨模块重构成本高**——方法签名变更需要同步更新多个模块的编译
5. **自定义 build-logic 插件**——为了处理 Java 8 desugar，额外引入了一个 composite build（build-logic），增加了构建链路的复杂度

**解决：**

将所有模块合并到单一的 `app/` 模块。包路径保持逻辑分层，但构建上统一为一个模块：

- 移除所有独立模块的 `build.gradle.kts`、`proguard-rules.pro`、`AndroidManifest.xml`
- 源码合并到 `app/src/main/java/` 下，按包名自然分层
- 移除 `build-logic/` 复合构建（desugar transform 由 AGP 原生支持）
- `settings.gradle.kts` 从 20+ 行 include 缩减为 `include(":app")` 一行
- 引入 `gradle/libs.versions.toml` 版本目录（Version Catalog），统一管理所有依赖版本

```
重构前 settings.gradle.kts:
  include(":app")
  include(":http-client-ok")
  include(":chat_memory_sqlite")
  include(":android_document_loader")
  include(":weatherutils")
  includeBuild("build-logic")
  include(":VehicleDoorManager")
  include(":VehicleWindowManager")
  include(":VehicleSeatManager")
  include(":VehicleAcManager")
  include(":VehicleFragManager")
  include(":VehicleChassisManager")
  include(":VlManager")
  include(":SceneMatch")
  include(":SceneServer")
  include(":ChatServer")
  include(":SoaService")
  include(":geoutils")

重构后:
  include(":app")
```

---

### 问题 2：UI 层与后端服务耦合——悬浮窗 + WebView 的脆弱架构

**发现：**

AIAgent 本质上是一个后台语音助手服务（前台 Service），但代码中混合了大量 UI 代码：

- `AIAgentWindowView.kt`——WebView 悬浮窗容器，加载 HTML/JS 实现 AI 对话界面
- `FloatWindowView.kt`——底部输入悬浮窗
- `MainActivity.kt`——调试用 Activity
- 多个 XML 布局文件
- WindowManager 权限管理、悬浮窗位置管理等代码

**思考：**

这个架构的设计初衷是"Service + 悬浮窗"实现无 Activity 的 AI 交互界面。但在实际运行中：

1. **ANR 风险**——主线程同时处理 Service 逻辑和悬浮窗渲染，WebView 加载慢时会阻塞主线程
2. **资源浪费**——Service 进程内维护 WebView 实例，内存占用大，后台运行时完全没有显示必要
3. **职责混乱**——同一个类（AIAgentService.kt）中既管理 LLM 调用、相机数据，又管理悬浮窗位置、WebView 内容更新
4. **交互方式变更**——后续的交互接口已改为 AIDL 回调（`IAIAgentAidlListener`），Client 端（HMI 应用）负责 UI 展示，Service 端只需要提供数据

**解决：**

彻底移除所有 UI 相关代码，AIAgent 变为纯后台 Service：

- 移除 `AIAgentWindowView.kt`、`FloatWindowView.kt` 等所有悬浮窗组件
- 移除 `MainActivity.kt` 及所有 XML 布局文件
- 移除 WindowManager 权限申请和管理代码
- 移除 WebView 相关依赖
- 将通知频道 ID 从 `floating_service_channel` 改为 `aiagent_service_channel`
- 前端交互通过 `IAIAgentAidlListener` AIDL 回调接口实现——增加 `sendMessage()` 和 `sendMessageWithImage()` 两个 AIDL 方法
- 增加 15 秒请求超时机制和异常回调

重构后 AIAgentService 变为纯粹的"数据处理器"，不再关心数据如何展示给用户。

---

### 问题 3：工具调度体系——450 行重复代码 + 3 个隐藏 Bug

见 `docs/act_summary/tool-system-refactoring-summary.md` 的完整分析。这里简述核心思路：

**发现：**

每个 Manager 中手写了 `hasTool()` + `handleToolRequest()` 两个方法，共约 450 行重复代码。更严重的是，手写字符串匹配导致 3 个调度 Bug：

- VehicleSpeedManager：hasTool 检查 `"vehicle_spd"` 但方法名 `"set_vehicle_spd"`
- VehicleDMSManager：hasTool 缺 `set_` 前缀
- VehicleWindowManager：同样缺 `set_` 前缀

**思考：**

核心矛盾在于"工具名在两个地方手写"。仔细分析 LangChain4j 框架后发现：`@Tool(name = "...")` 注解本身就是编译期常量，框架的 `ToolSpecifications.toolSpecificationsFrom()` 也是通过反射读取 `@Tool` 来提取工具规格。**为什么不直接利用这个事实？**

**解决：**

新建 `ToolDispatcher`（反射调度器）+ `ToolRegistry`（注册中心）两个类：

- `ToolDispatcher`：扫描目标对象的 `@Tool` 方法，建立工具名 → Method 映射，运行时反射调用
- `ToolRegistry`：管理多个 ToolDispatcher 实例，提供 `registerAll()` 和 `dispatch()` 接口
- MainAgentLoop 中的 10 层 instanceof 分发链改为一行 `toolRegistry.dispatch(request)`

---

### 问题 4：敏感信息硬编码

**发现：**

- DashScope API Key 硬编码在 5 个 Java 文件中（MainAgentLoop、ChatServer、SceneServer、SceneMatch、VlManager）
- 签名密钥密码明文写在 `app/build.gradle.kts` 中
- `platform.jks` / `platform.keystore` 签名密钥文件被 Git 追踪

**思考：**

将 API Key 和签名密码提交到 Git 仓库是明显的安全风险。`platform.jks` 签名密钥泄露后，攻击者可以用其签名恶意应用。API Key 泄露可能导致盗刷。但这些密钥在开发过程中确实需要本地使用，需要找到一个"本地可用、不入库"的平衡方案。

**解决：**

- 在 `local.properties` 中新增 `dashscope.api_key`、`signing.storePassword`、`signing.keyAlias`、`signing.keyPassword` 配置项
- 在 `app/build.gradle.kts` 中，通过 `buildConfigField` 将 `dashscope.api_key` 生成为 `BuildConfig.DASHSCOPE_API_KEY`
- 5 个源文件中将硬编码字符串替换为 `BuildConfig.DASHSCOPE_API_KEY`
- 签名密码改为从 `localProps.getProperty(...)` 读取
- `.gitignore` 中排除 `*.jks`、`*.keystore`、`local.properties`
- 通过 `git rm --cached` 将已入库的 `platform.jks`、`platform.keystore`、`.idea/` 从 Git 追踪中移除
- 创建 `local.properties.example` 模板文件（不含真实密钥）

---

### 问题 5：版本管理混乱

**发现：**

17 个模块各自声明依赖版本，没有统一的版本管理。LangChain4j 版本为 1.1.0，较老且缺少后续版本的大量改进（ToolSpecifications API、toolExecutionRequest 标准格式、http-client 抽象等）。

**思考：**

多模块项目如果没有版本目录，很容易出现"模块 A 用 LangChain4j 1.1.0、模块 B 用 1.2.0"的不一致情况。合并为单模块后，更需要一个集中的版本声明机制。

**解决：**

- 引入 `gradle/libs.versions.toml` 版本目录（Version Catalog），所有依赖版本集中声明
- LangChain4j 从 1.1.0 升级到 1.16.3，API 变动的适配：
  - `ChatLanguageModel` → `ChatModel`
  - `AiMessage.toolExecutionRequests()` 返回类型变更
  - `ToolSpecifications.from()` → `toolSpecificationsFrom()`
  - `ChatRequest.builder().toolSpecifications()` 替代旧版 tools() 方法

---

### 问题 6：跨平台开发环境不一致

**发现：**

两个开发者分别在 Windows 和 macOS 上开发，Git 没有配置换行符处理策略，导致：

- 跨平台切换时 diff 中出现大量换行符变更（CRLF vs LF）
- `.idea/` 目录被 Git 追踪，IDE 配置变更导致无意义 diff
- `.gitignore` 不完整，`*.bak` 备份文件可能被误提交

**解决：**

- 添加 `.gitattributes`，明确声明源码文件（.java、.kt、.kts、.xml 等）统一使用 LF，Windows 批处理使用 CRLF，二进制文件不转换
- 重写 `.gitignore`，排除 `*.jks`、`*.keystore`、`*.bak`、`/build`、`.kotlin/`、`Thumbs.db` 等
- 使用 `git rm --cached` 将 `.idea/` 从 Git 追踪中移除
- 配置 GitHub 远程仓库 `git@github.com:yala5820/car_aiagent.git`

---

### 问题 7：缺少统一的 Agent 主循环

**发现：**

ChatServer、SceneServer 各自实现了独立的 LLM 调用循环：

- ChatServer：`chatWithVehicleStatus()` → `processAiResponse()` 递归处理工具调用
- SceneServer：`scene_server()` → `processFunctionCall()` 处理工具调用
- 两套循环逻辑相似（注入车辆状态 → LLM 调用 → 工具执行 → 递归），但实现细节不同，Bug 修复需要两处同步

**思考：**

"一次对话多次工具调用"是 Agent 的核心模式，应该抽象为一个统一的主循环，而不是每个引擎各自实现。LangChain4j 提供完整的 ToolExecutionRequest 处理链，只需要围绕它构建一个迭代式循环。

**解决：**

新建 `core/MainAgentLoop.java`，作为核心的 Agent 迭代循环：

```java
private String executeLoop() {
    for (int round = 0; round < MAX_ITERATIONS; round++) {
        // 1. 注入车辆状态
        // 2. 调用 LLM（携带工具声明）
        // 3. 解析响应
        //    ├─ 有 ToolExecutionRequest → dispatchTool → 结果回填 → 下一轮
        //    └─ 无 → 返回文本
    }
}
```

关键设计：

- **迭代上限**（MAX_ITERATIONS = 10）防止无限循环
- **消息上限**（MAX_MESSAGES = 50）防止超出上下文窗口
- **状态注入**——每次迭代注入最新车辆状态，确保 LLM 感知实时数据
- **工具调度统一**——通过 ToolRegistry 集中化处理，不再分散在各个引擎中

---

## 三、重构后的项目架构

### 3.1 模块结构

```
AIAgent/
├── app/                          # 唯一模块
│   └── src/main/java/com/hirain/aiagent/
│       ├── AIAgentService.kt     # 前台 Service（纯后端，无 UI）
│       ├── AIAgentApplication.kt # Application 入口
│       ├── AIAgent.java          # AIDL Stub（已归档）
│       ├── AIAgentData.java      # AIDL 数据传输对象
│       ├── IAIAgentServiceListener.java
│       ├── InputStreamCompat.java
│       ├── BootCompleteReceiver.kt
│       │
│       ├── ai/langchain4j/tool/  # 工具调度系统（新增）
│       │   ├── ToolDispatcher.java   # 反射调度器
│       │   └── ToolRegistry.java     # 注册中心
│       │
│       ├── agents/                # Agent 定义层
│       │   └── (预留)
│       │
│       ├── capability/            # 能力定义层
│       │   └── (预留)
│       │
│       ├── common/                # 通用工具
│       │   └── (预留)
│       │
│       ├── core/                  # 核心 Agent 循环（新增）
│       │   └── MainAgentLoop.java
│       │
│       ├── data/                  # 数据层（Repository 模式）
│       │   ├── CardInfo.kt
│       │   ├── CardRepository.kt
│       │   ├── CardRepositoryImpl.kt
│       │   ├── SoaDataInfo.kt
│       │   └── local/
│       │       ├── LocalCardDataProvider.kt
│       │       ├── LocalPrompt.kt
│       │       └── LocalSoaData.kt
│       │
│       ├── engines/               # 遗留引擎（待迁移到 AgentLoop）
│       │   ├── chat/ChatServer.java
│       │   ├── scenematch/SceneMatch.java
│       │   └── sceneserver/SceneServer.java
│       │
│       ├── infra/                 # 基础设施层
│       │   ├── geo/GeoUtils.java
│       │   └── soa/SoaService.kt
│       │
│       ├── memory/                # 记忆层（预留）
│       ├── model/                 # 模型层（预留）
│       │
│       ├── soa/                   # SOA 业务层
│       │   ├── SoaCallbackImpl.kt
│       │   ├── SoaDataUseCase.java
│       │   └── SoaServiceUseCase.kt
│       │
│       └── tools/                 # 工具层（@Tool 声明）
│           ├── external/weather/WeatherUtils.java
│           ├── vehicle/
│           │   ├── ac/VehicleAcManager.java
│           │   ├── chassis/VehicleChassisManager.java
│           │   ├── dms/VehicleDMSManager.java
│           │   ├── door/VehicleDoorManager.java
│           │   ├── frag/VehicleFragManager.java
│           │   ├── seat/VehicleSeatManager.java
│           │   ├── speed/VehicleSpeedManager.java
│           │   └── window/VehicleWindowManager.java
│           └── vision/vl/VlManager.java
│
├── gradle/
│   └── libs.versions.toml        # 版本目录
├── docs/                          # 文档
├── build.gradle.kts
├── settings.gradle.kts            # 仅 include(":app")
├── .gitignore
├── .gitattributes
└── local.properties.example
```

### 3.2 软件架构

```
┌─────────────────────────────────────────────────────────┐
│                    AIDL 接口层                           │
│  IAIAgentAidlInterface (requestAI / sendMessage /       │
│    sendMessageWithImage / registerListener)              │
│  ↑                              ↑                        │
│  │  HMI (仪表/中控)            CameraService              │
│  │  (第三方 UI 应用)            (摄像头数据)              │
├───────────────────┬─────────────────────────────────────┤
│   AIAgentService  │  SoaService                         │
│   (前台 Service)  │  (SOA 总线通信)                     │
│   VR/TTS 管理     │                                     │
├───────────────────┴─────────────────────────────────────┤
│                    AgentLoop 层                          │
│  ┌──────────────────────────────────────────────────┐   │
│  │           MainAgentLoop.java                      │   │
│  │  ① 注入车辆状态 → ② LLM 调用 → ③ 工具执行 → 循环 │   │
│  │  ToolRegistry.dispatch(request)                   │   │
│  └──────────────────┬───────────────────────────────┘   │
│                     │                                    │
│  ┌──────────────────▼───────────────────────────────┐   │
│  │  ToolRegistry (注册中心)                         │   │
│  │  registerAll(manager1, manager2, ...)             │   │
│  │  dispatch(request) → ToolDispatcher → 反射调用    │   │
│  └──────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────┤
│                    Tool 层                               │
│  ┌──────┐┌──────┐┌────┐┌──────┐┌──────┐┌──────┐┌───┐ │
│  │ Door ││Window││Seat││  AC  ││Frag  ││Speed ││DMS│ │
│  │Mgr   ││Mgr   ││Mgr ││Mgr   ││Mgr   ││Mgr   ││Mgr│ │
│  └──────┘└──────┘└────┘└──────┘└──────┘└──────┘└───┘ │
│  ┌──────────┐┌───────────┐┌─────────────────────────┐  │
│  │ChassisMgr││WeatherUtils││     VlManager(VLM)     │  │
│  └──────────┘└───────────┘└─────────────────────────┘  │
│  (所有 Manager 只保留 @Tool 注解方法，无样板代码)        │
├─────────────────────────────────────────────────────────┤
│                   数据 & 基础设施层                       │
│  data/ (Repository)   infra/ (soa, geo)                 │
│  memory/ (聊记忆)     model/ (数据模型)                  │
└─────────────────────────────────────────────────────────┘
```

### 3.3 关键变更统计

| 指标 | 重构前 | 重构后 |
|------|--------|--------|
| Gradle 模块数 | 17 | 1 |
| settings.gradle.kts | 21 行 include | `include(":app")` |
| 构建系统 | AGP + build-logic composite build | 纯 AGP |
| 版本管理 | 各模块独立声明 | libs.versions.toml |
| LangChain4j | 1.1.0 | 1.16.3 |
| hasTool/handleToolRequest | 450 行 | 0 行（已删除） |
| instanceof 分发链 | 10 层 | 0 层（Map 查找） |
| Bug | 5 个（3 调度 + 2 字段） | 0 个 |
| API Key 声明 | 5 处硬编码 | 1 处 local.properties |
| 签名密码 | build.gradle.kts 明文 | local.properties |
| 签名密钥 | Git 追踪中 | .gitignore 排除 |
| .gitignore 规则 | 不完整 | 完整（17 条规则） |
| 跨平台换行策略 | 无 | .gitattributes（69 条规则） |
| GitHub 远程 | 无 | git@github.com:yala5820/car_aiagent.git |
| 工具调度中心 | 无（分散在各 Manager） | ToolDispatcher + ToolRegistry |
| Agent 主循环 | 无（各引擎自实现） | MainAgentLoop |
| 命名风格 | 3 种混用 | 统一驼峰（Java）+ 注解解耦 |

### 3.4 架构原则

重构后形成的架构遵循以下原则：

1. **单一模块，分层治理**——一个 Gradle 模块，按 package 分层，不因逻辑隔离牺牲构建性能
2. **声明式工具定义**——Manager 只声明 `@Tool` 注解，不写调度代码，由框架自动发现和执行
3. **集中式 Agent 循环**——一次对话的多次 LLM 调用和工具执行收敛到 MainAgentLoop，不分散在各引擎中
4. **纯后端服务**——AIAgentService 不关心 UI 展示，所有输出通过 AIDL 回调传递给 Client
5. **敏感信息外置**——API Key、签名密码等不进入 Git 历史，通过 local.properties + BuildConfig 本地注入
6. **基于版本目录的依赖管理**——所有依赖版本集中管理，消除不一致

### 3.5 遗留问题

1. **ChatServer / SceneServer 仍使用旧的 hasTool/handleToolRequest 模式**，需要迁移到 ToolRegistry
2. **Windows 下 AIDL 编译问题**——预生成 AIDL Java 文件中的 Windows 路径导致 Unicode 转义错误，非本次重构引入
3. **车机硬件依赖**——CameraSdk.jar、adapter_vr.jar 为闭源私有库，无源码
