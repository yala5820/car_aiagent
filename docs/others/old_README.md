# AIAgent — AI 智能座舱助手

## 1. 项目概述

AIAgent 是运行于 Android 车机系统上的 **AI 语音助手核心程序**。它不是一个有常规 UI 界面的 App，而是一个持续运行在后台的**前台 Service**，通过悬浮窗（`WindowManager`）在车机屏幕上叠加 AI 交互界面。

**核心业务目标：** 利用大语言模型（LLM）和多模态视觉模型（VLM），为驾驶员提供自然语言对话、车辆控制、场景感知、前向视野问答等智能座舱体验。

**关键特征：**
- 无传统 Activity UI，所有交互通过 WebView 悬浮窗呈现
- 使用阿里云 DashScope 的 OpenAI 兼容 API（qwen-turbo / qwen3-vl-plus / qwen-vl-max）
- 基于 LangChain4j 1.1.0 的 Tool Calling 机制实现"LLM + 车控"联动
- 与 CameraService 进程通过 CameraSdk.jar（AIDL）通信，获取前向摄像头数据
- 通过 SoaService（AIDL）与车辆 SOA 总线通信，控制车身硬件

---

## 2. 项目架构与技术栈

### 架构分层

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
│  (@Tool 注解, LLM 自主决策调用)                           │
├─────────────────────────────────────────────────────────┤
│                  硬件通信层                               │
│  SoaService (SOA总线 AIDL) | CameraSdk.jar (Camera AIDL) │
│  adapter_vr.jar (VR/TTS)                                 │
└─────────────────────────────────────────────────────────┘
```

### 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin + Java 混编 |
| AI 框架 | LangChain4j 1.1.0 |
| LLM 模型 | qwen-turbo（对话）、qwen3-vl-plus（场景识别）、qwen-vl-max（视觉问答） |
| LLM API | 阿里云 DashScope（OpenAI 兼容接口） |
| 通信 | AIDL（SOA 总线、Camera）、ServiceManager（系统服务发现） |
| UI | WindowManager 悬浮窗、WebView（HTML/JS）、ViewBinding |
| 持久化 | SQLite（ChatMemory 持久化） |
| 网络 | OkHttp 4.12 |
| VR/TTS | adapter_vr.jar（闭源） |
| 构建 | Gradle 8.9+ / AGP 8.9.1 / Version Catalog |

---

## 3. 项目目录结构与模块划分

```
AIAgent/
├── app/                                    # 主应用（可独立编译为 APK）
│   ├── src/main/
│   │   ├── AndroidManifest.xml             # 声明 2 个 Activity + 1 个 Service + 1 个 Receiver
│   │   ├── aidl/                           # SOA 总线 AIDL 接口副本
│   │   ├── java/com/hirain/aiagent/
│   │   │   ├── AIAgentService.kt           # [核心] 前台 Service，管理悬浮窗 + Camera + LLM
│   │   │   ├── AIAgentWindowView.kt        # [核心] AI 悬浮窗 WebView 容器
│   │   │   ├── FloatWindowView.kt          # 底部输入悬浮窗
│   │   │   ├── MainActivity.java           # 调试用聊天界面（开发阶段使用）
│   │   │   ├── PermissionActivity.kt       # 悬浮窗权限申请入口
│   │   │   ├── BootCompleteReceiver.kt     # 开机广播 → 启动 Service
│   │   │   ├── data/                       # 数据层
│   │   │   │   ├── CardInfo.kt / CardRepository.kt    # 场景卡片接口
│   │   │   │   ├── CardRepositoryImpl.kt              # 卡片数据实现
│   │   │   │   ├── SoaDataInfo.kt                     # SOA 数据模型
│   │   │   │   └── local/                              # 本地数据源
│   │   │   │       ├── LocalCardDataProvider.kt        # 场景卡片硬编码数据
│   │   │   │       ├── LocalPrompt.kt                  # 40+ 个 SOA 工具的 JSON 定义
│   │   │   │       └── LocalSoaData.kt                 # 26 个 SOA 信号状态定义
│   │   │   ├── soa/                          # SOA 数据获取用例层
│   │   │   │   ├── SoaServiceUseCase.kt      # SOA 服务用例
│   │   │   │   ├── SoaDataUseCase.java        # SOA 数据读写（getValues / setValues）
│   │   │   │   └── SoaCallbackImpl.kt         # SOA 回调处理
│   │   │   ├── JavaScriptInterface.java       # WebView JS 接口（空实现）
│   │   │   ├── CustomAdapter.java             # RecyclerView 适配器（未使用）
│   │   │   └── InputStreamCompat.java          # IO 兼容工具类
│   │   └── res/
│   │       ├── layout/                        # 界面布局
│   │       └── raw/                           # WebView 用 HTML 模板
│   └── libs/
│       ├── CameraSdk.jar                     # CameraService AIDL 接口
│       ├── AIAgentSdk.jar                    # 对外暴露的 AIDL 接口（供 SystemUI 使用）
│       └── adapter_vr.jar                    # VR/TTS 服务闭源 SDK
│
├── ChatServer/                             # [Android Library] LLM 对话引擎
│   └── src/main/java/.../chatserver/
│       └── ChatServer.java                  # LangChain4j 对话管理 + Tool Calling 循环
│
├── SceneMatch/                              # [Android Library] 视觉场景识别
│   └── src/main/java/.../scenematch/
│       └── SceneMatch.java                  # Qwen3-VL-Plus 图片 → 场景分类
│
├── SceneServer/                             # [Android Library] 场景驱动座舱响应
│   └── src/main/java/.../sceneserver/
│       └── SceneServer.java                 # 场景 → LLM → 车控工具调用 → 语音建议
│
├── SoaService/                              # [Android Library] SOA 总线 AIDL 封装
│   └── src/main/
│       ├── aidl/                            # 40+ 个 AIDL 接口定义（ISoaBusService、ISoaService 等）
│       └── java/.../soaservice/
│           └── SoaService.kt                # 单例 SOA 服务，所有方法均为空实现
│
├── VehicleDoorManager/                      # [Android Library] 车门 @Tool
├── VehicleWindowManager/                    # [Android Library] 车窗/天窗/除霜 @Tool
├── VehicleSeatManager/                      # [Android Library] 座椅/加热/通风/按摩 @Tool
├── VehicleAcManager/                        # [Android Library] 空调全功能 @Tool
├── VehicleChassisManager/                   # [Android Library] 底盘驾驶模式 @Tool
├── VehicleFragManager/                      # [Android Library] 香氛 + DMS + 车速 @Tool
├── VlManager/                               # [Android Library] 前向视觉问答 @Tool
├── http-client-ok/                          # [Android Library] OkHttp 适配 langchain4j
├── chat_memory_sqlite/                      # [Android Library] SQLite ChatMemory 持久化
├── weatherutils/                            # [Android Library] 天气查询 @Tool
├── geoutils/                                # [Android Library] 地理工具
├── android_document_loader/                 # [Android Library] 文档加载（未启用）
└── build-logic/                             # 自定义 Gradle 插件
    └── DesugarTransformPlugin.kt            # 字节码反糖插件
```

---

## 4. 核心组件

### 4.1 AIAgentService（主控中枢）

**文件：** `AIAgentService.kt`

这是整个应用的"大脑"，一个前台 Service。职责包括：

- **悬浮窗管理**：创建并管理 `AIAgentWindowView`（WebView 对话区）和 `FloatWindowView`（底部输入区）两个悬浮窗
- **Camera 接入**：通过 `Camera.getInstance().init()` 连接 CameraService 进程，注册 `ICameraServiceListener` 回调，每 1 秒请求一次前向摄像头抓拍
- **场景识别循环**：收到抓拍图片后，调用 `SceneMatch.vl_scene_match()` 识别场景；若场景变化且不在对话中，调用 `SceneServer.scene_server()` 生成主动响应
- **语音对话**：通过 `AIDL Binder`（`IAIAgentAidlInterface`）接收外部（VR 服务）传来的语音识别文本，调用 `ChatServer.chat()` 处理对话，结果通过 TTS 播报
- **AIDL 接口**：对外暴露 `IAIAgentAidlInterface`（`AIAgentBinder`），供其他 App（如 SystemUI）或语音服务调用

**关键时序：**
```
onCreate()
  → 创建通知渠道，启动前台服务
  → 请求悬浮窗权限
  → Camera.getInstance().init()   // 连接 CameraService
  → 启动 1 秒定时器：requestCapture()
  → 初始化 VlManager / VRServiceManager / SceneServer / ChatServer
  → 显示 AI 悬浮窗
```

### 4.2 AIAgentWindowView（悬浮窗 UI）

**文件：** `AIAgentWindowView.kt`

基于 `FrameLayout` 的自定义悬浮视图，内容是一个 WebView：

- 使用 `loadInitialHtml()` 加载一个简易 HTML 页面，通过 JavaScript `appendText()` 函数追加对话内容
- 使用 `evaluateJavascript()` 动态更新 WebView 内容，实现流式逐字显示效果
- 包含"防抖"逻辑：当内容高度变化<200px 时保持原高度，避免频繁重绘
- 触摸悬浮窗可隐藏 AI 界面
- 内置一个完整的导航卡片 HTML 渲染模板（`showHtml()` 方法，当前未激活）

### 4.3 ChatServer（对话引擎）

**文件：** `ChatServer.java`

基于 LangChain4j 的多轮对话引擎：

- **LLM 模型**：qwen-turbo（DashScope），`parallelToolCalls = true`
- **System Prompt**：约 90 行，定义了角色定位、能力边界、对话规范、控车逻辑
- **对话记忆**：`MessageWindowChatMemory` + `PersistentChatMemorySqlite`，最多 50 条消息
- **Tool 列表**：天气、车门、车窗、座椅、空调、香氛、前向摄像头（共 7 类）
- **处理流程**：用户输入 → 拼接车辆状态 JSON → LLM 推理 → 如果 LLM 返回 Tool Call → 执行工具 → 将结果送回 LLM → 递归直到 LLM 返回纯文本

### 4.4 SceneMatch（场景识别）

**文件：** `SceneMatch.java`

基于多模态视觉模型的场景分类器：

- **模型**：qwen3-vl-plus（DashScope）
- **输出格式**：强制 JSON Schema（`ResponseFormat.type(JSON)`），包含 `name` 和 `description` 字段
- **场景分类**：舱外浓烟、雨雪天气、工程施工、乘客休息、驾驶员疲劳、其他
- **调用时机**：AIAgentService 每 1 秒获得 Camera 抓拍后调用

### 4.5 SceneServer（场景响应）

**文件：** `SceneServer.java`

场景驱动的主动座舱服务：

- 根据场景名称选择不同的 Tool 集合（雨雪→车窗+座椅+空调+底盘；施工→车窗+空调；等等）
- 包含场景特异性逻辑："雨雪天禁止开窗"、"疲劳驾驶调低空调温度（19-22°C）"
- 场景特异性操作（如"打开双闪"、"方向盘震动"）目前为 TODO 状态，返回模拟文本
- 最终通过 LLM 合并工具执行结果和场景特异性文本，输出 80 字以内的语音建议

### 4.6 VlManager（前向视野问答）

**文件：** `VlManager.java`

"窗景随问"功能的视觉问答引擎：

- **模型**：qwen-vl-max（DashScope）
- 通过 `front_camera_save()` 接收 CameraService 的实时抓拍图片
- 当收到用户关于前方视野的问题时，将图片 + 问题发送给 VL 模型
- 无实时图片时回退到 assets 中的 `audi.jpg` 静态图片
- 每次回答末尾添加声明，提示结果仅对当次交互有效

### 4.7 Vehicle*Manager 系列（车控工具）

6 个模块，共约 **40+ 个 @Tool 方法**，覆盖：

| 模块 | 工具数 | 覆盖功能 |
|------|-------|---------|
| VehicleDoorManager | 1 | 车门闭锁/解锁 |
| VehicleWindowManager | 11 | 车窗、天窗、遮阳帘、除霜、后视镜加热 |
| VehicleSeatManager | 11 | 座椅加热、通风、按摩、方向盘加热 |
| VehicleAcManager | 15 | 空调开关、温度、风量、ECO、负离子、内外循环、扫风、出风口 |
| VehicleChassisManager | 1 | 底盘模式（普通/越野/雪地） |
| VehicleFragManager | 2 | 香氛类型、浓度 |

所有 Manager 的模式一致：`@Tool` 注解 + `hasTool()` + `handleToolRequest()` + 调用 `SoaService`。

### 4.8 SoaService（SOA 总线封装）

**文件：** `SoaService.kt`

单例对象，通过反射调用 `ServiceManager.getService("SoaBusService")` 获取系统 SOA 总线 Binder 引用。

**关键问题：所有与硬件通信的方法体均为空，仅有 `Log.d` 日志输出。**

SoaService 中的 `getDoorStatus()`、`set_ac_status()`、`setFlWindowStatus()` 等 **40+ 个方法全部为空实现**（`return ""` 或空函数体）。

### 4.9 实验性代码（data/ 目录）

`data/` 目录下的 `CardRepository`、`LocalPrompt`、`LocalSoaData`、`SoaServiceUseCase` 等类构成一个**非活跃的并行架构**：
- `LocalPrompt` 定义了 40+ 个 SOA 工具的完整 JSON 描述（含参数和取值范围）
- `SoaDataUseCase` 使用 AIDL 的 `getValues()` / `setValues()` 方法读写 SOA 信号
- 这套代码**没有被当前的主流程（AIAgentService / ChatServer）引用**，属于开发中的重构/新方案

---

## 5. 初始化流程与运行流程

### 5.1 启动入口

```
系统开机（BOOT_COMPLETED）
    │
    ▼
BootCompleteReceiver.onReceive()
    │
    ▼
startForegroundService(AIAgentService::class.java)
    │
    ▼
AIAgentService.onCreate()
    ├─ 创建前台服务通知渠道
    ├─ 请求悬浮窗权限（SYSTEM_ALERT_WINDOW）
    ├─ Camera.getInstance().init(context, listener)     ← 连接 CameraService
    ├─ 启动 1 秒定时器：requestCapture()                ← 周期性抓拍
    ├─ VlManager(ctx)                                  ← 初始化视觉问答
    ├─ VRServiceManager.getInstance(this).initCallback() ← 初始化语音唤醒/TTS
    ├─ SceneServer(this)                                ← 初始化场景响应引擎
    ├─ ChatServer(this, vl)                             ← 初始化对话引擎
    └─ 显示 AIAgent 悬浮窗
```

### 5.2 核心指令流转路径

#### 路径 A：用户主动语音对话

```
用户说话
    │
    ▼
VR 服务 (adapter_vr.jar) 识别语音
    │
    ▼ 通过 AIDL (IAIAgentAidlInterface)
AIAgentService.requestAI("@#%^StartListen")
    │  → 显示"聆听中..."
    │  → 停止 TTS
    ▼
AIAgentService.requestAI("@#%^StopListen" + "用户说...")
    │
    ▼
AIAgentService.processNagativeRequest(userMessage)
    │
    ▼
ChatServer.chat(userMessage)
    ├─ chatMemory.add(UserMessage)
    ├─ 拼接车辆状态 JSON（getVehicleStatus()）
    ├─ LLM 推理（qwen-turbo）
    ├─ 如果 LLM 返回 Tool Call →
    │   handleTools() → Vehicle*Manager 执行 → SoaService 调用（空实现）
    │   → 工具结果送回 LLM → 递归直到纯文本
    └─ 返回 AI 回答文本
    │
    ▼
AIAgentService.appendNagativeResponse("AI:", response)
    ├─ AIUpdateNagativeResponse(response)  ← 更新悬浮窗 WebView
    ├─ mManager?.speak(response)            ← TTS 播报
    └─ 5 秒后自动隐藏悬浮窗
```

#### 路径 B：场景主动识别与提示

```
CameraService 每 1 秒抓拍一帧
    │
    ▼ 通过 CameraSdk.jar AIDL
AIAgentService.CameraListener.onCaptureGot(seqid, mode, data)
    │
    ▼
VlManager.front_camera_save("", data)  ← 缓存图片供"窗景随问"使用
    │
    ▼ (仅在非对话、非 TTS 播放、无负请求执行时)
SceneMatch.vl_scene_match(base64_img, "image/jpeg")
    │  → Qwen3-VL-Plus 识别场景
    │  → 返回 {name, description}
    │
    ▼ (仅在场景变化时)
SceneServer.scene_server(scene)
    │
    ├─ 根据 scene.name 选择 Tool 集合（雨雪/施工/疲劳/休息/浓烟）
    ├─ 拼接车辆状态 JSON
    ├─ LLM 推理 + Tool Calling 循环
    ├─ handleSceneSpecified(scene)  → 场景特异性操作（当前为模拟文本）
    └─ LLM 合并最终输出（80 字以内）
    │
    ▼
ChatServer.appendPositiveResponseToChat("AI:", res, description)
    ├─ 逐字更新悬浮窗 WebView
    ├─ 最后调用 TTS 播报
    └─ 10 秒后自动隐藏
```

#### 路径 C：MainActivity 调试对话

```
用户打开 AIAgent App（仅开发调试用）
    │
    ▼
MainActivity.onCreate()
    ├─ 创建 LLM 模型（qwen-turbo）
    ├─ 创建 ChatMemory（PersistentChatMemorySqlite）
    ├─ 初始化所有 Vehicle*Manager
    ├─ 绑定 AIAgentService（bindService）
    └─ 显示简单聊天界面（EditText + Button）
    │
    ▼
用户输入文本 → onSendClick()
    │
    ▼
processUserRequest(inputText)
    └─ chatWithVehicleStatus() → LLM + Tool Calling 循环
```

---

## 6. 功能开发进度评估

> 说明：以下评估基于对完整源码的分析。"壳子"指该功能的方法签名已经定义但内部为空或仅为占位。"真实引擎"指已接入实际的 API、硬件或服务。

### 6.1 对话管理

| 功能 | 状态 | 说明 |
|------|------|------|
| LLM 对话（ChatServer） | **真实引擎** | qwen-turbo 通过 DashScope API 已接入，支持多轮对话 |
| 对话记忆持久化 | **真实引擎** | SQLite 持久化（PersistentChatMemorySqlite），应用重启后记忆仍在 |
| Tool Calling 循环 | **真实引擎** | LangChain4j 递归 Tool Calling 已完整实现 |
| 车辆状态注入 | **真实引擎** | 每次对话自动拼接车门/车窗/空调等状态 JSON |
| System Prompt 管理 | **真实引擎** | 包含角色定义、能力边界、交互规范等 |
| 对话窗口 UI | **真实引擎** | WebView 悬浮窗以打字机效果逐字显示对话内容 |

### 6.2 场景识别与主动服务

| 功能 | 状态 | 说明 |
|------|------|------|
| 前向摄像头抓拍 | **真实引擎** | 通过 CameraSdk.jar 每 1 秒请求一次 CameraService 抓拍 |
| 视觉场景识别（SceneMatch） | **真实引擎** | Qwen3-VL-Plus 已接入，JSON Schema 强制结构化输出 |
| 场景主动响应（SceneServer） | **真实引擎** | 根据场景名称选择不同 Tool 集合调用 LLM，含场景特异性规则 |
| 场景特异性操作（开双闪/方向盘震动等） | **壳子** | `handleSceneSpecified()` 中直接返回模拟文本，标注了 TODO |
| 摄像头图片缓存（供窗景随问） | **真实引擎** | VlManager 通过 `front_camera_save()` 缓存最新抓拍 |

### 6.3 车控功能

| 功能 | 状态 | 说明 |
|------|------|------|
| Tool 接口定义（40+ @Tool） | **真实引擎** | 所有 Vehicle*Manager 的 @Tool 方法完全定义，含参数描述和校验 |
| Tool 请求路由（hasTool + handleToolRequest） | **真实引擎** | 完整的字符串匹配 + JSON 参数解析 |
| SoaService 方法调用链 | **壳子** | 所有方法体为空（`Log.d` + `return`），`formalfunc = false` 表示不更新本地状态 |
| SOA 总线 AIDL 绑定 | **真实引擎** | 能成功连接到系统 SoaBusService / s2sservice |
| SOA 数据获取（getValues） | **真实引擎** | SoaDataUseCase 能通过 AIDL 调用 getValues 获取信号值（回调实现可能不全） |
| SOA 数据设置（setValues） | **壳子** | setSoaValue() 中填充的是测试数据，不是真实控制指令 |
| 实验性 CardRepository 架构 | **未集成** | data/ 目录下的 LocalPrompt + CardRepository + SoaDataUseCase 没有被任何主流程引用 |

### 6.4 外部集成

| 功能 | 状态 | 说明 |
|------|------|------|
| 天气查询（WeatherUtils） | **真实引擎** | 已接入，API Key 硬编码 |
| 前向视野问答（VlManager） | **部分真实** | 有摄像头数据时走 Qwen-VL-Max 真实推理，无数据时回退到 assets 静态图片 |
| TTS 语音播报 | **真实引擎** | 通过 adapter_vr.jar 的 VRServiceManager.speak() |
| 语音唤醒/ASR | **真实引擎** | 通过 adapter_vr.jar 的 VRServiceManager 回调 onAsrResult |
| CameraService 通信 | **真实引擎** | CameraSdk.jar AIDL 接口已对接，周期性抓拍可用 |
| WebView 导航卡片 | **壳子** | showHtml() 方法有完整 HTML/CSS 模板，但从未被调用，属于预留 UI |

### 6.5 总结

| 层级 | 完成度 | 说明 |
|------|--------|------|
| LLM 接入层（模型调用、对话管理） | 约 90% | 全线接入 DashScope，多模型使用 |
| 工具定义层（@Tool） | 约 95% | 40+ 工具覆盖全部车控功能，参数描述详尽 |
| 工具路由层（hasTool + handleToolRequest） | 约 95% | 完整实现，JSON 参数解析 |
| 硬件通信层（SoaService） | **约 5%** | 所有方法为空，**这是当前项目最大的功能性断点** |
| SOA 数据获取（getValues） | 约 40% | AIDL 绑定可用，但获取后的数据处理逻辑不完整 |
| 场景识别 | 约 85% | 真实模型推理，但场景特异性操作为模拟 |
| UI 悬浮窗 | 约 80% | WebView 渲染稳定，但高度防抖逻辑复杂，手势交互较基础 |
| 实验性代码（data/） | 约 30% | 架构有设计，未集成 |

**一句话结论：AIAgent 的 LLM 推理链路和工具定义已经完整，但从"LLM 决定关窗"到"车窗真的关闭"之间的硬件通信层（SoaService）是完全断开的。** 当前状态下可以在模拟器中验证 LLM 对话和 Tool Calling 逻辑的正确性，但无法实际控车。
