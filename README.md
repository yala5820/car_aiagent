# AIAgent — AI 智能座舱后台服务引擎

## 1. 项目概述

AIAgent 是运行于 Android 车机系统上的 **AI 语音助手的后台引擎**。它是一个持续运行在后台的**前台 Service**，本身**无任何 UI**，对外通过 AIDL（`AIAgentSdk.jar`）暴露 LLM 对话能力，供 Launcher 或其他 App 调用。

**核心业务目标：** 利用大语言模型（LLM）和多模态视觉模型（VLM），为驾驶员提供自然语言对话、车辆控制、场景感知、前向视野问答等智能座舱能力，以纯后台服务的形式被上层 UI 应用调用。

**关键特征：**
- 纯后台 Service，无传统 UI / 无悬浮窗
- 对外暴露 AIDL 接口（`sendMessage` / `sendMessageWithImage` / `requestAI`），通过 `AIAgentSdk.jar` 供 Launcher、SystemUI 等调用
- 使用阿里云 DashScope 的 OpenAI 兼容 API（`qwen-turbo` / `qwen3-vl-plus` / `qwen-vl-max`）
- 基于 LangChain4j 1.1.0 的 Tool Calling 机制实现"LLM + 车控"联动
- 与 CameraService 进程通过 CameraSdk.jar（AIDL）通信，获取前向摄像头数据
- 通过 SoaService（AIDL）与车辆 SOA 总线通信，控制车身硬件（SoaService 方法体为空，待实现）
- 与 VR Service 通过 adapter_vr.jar（AIDL）通信，支持 TTS 播报和语音识别

### 改造历史

| 阶段 | 说明 |
|------|------|
| **Phase 1** | 从 AIAgentSdk.jar 恢复 AIDL 源文件，新增 `sendMessage` / `sendMessageWithImage` 接口，移除 UI 悬浮窗，重建 SDK JAR |
| **Phase 2** | Launcher 接入 AIAgent 调用链，替换 HTTP AI 端口，实现 AIDL 双向通信 |

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
│   AIAgentSdk.jar   AIAgentSdk.jar                          │
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
│  │               LLM / AI 引擎层                │            │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐     │            │
│  │  │ChatServer│ │SceneMatch│ │SceneServer│     │            │
│  │  └────┬─────┘ └────┬─────┘ └────┬─────┘     │            │
│  │       │             │            │           │            │
│  │       └─────────────┴────────────┘           │            │
│  │                    │                         │            │
│  │           LangChain4j 1.1.0                  │            │
│  │      OpenAI-compatible API (DashScope)       │            │
│  ├─────────────────────────────────────────────┤            │
│  │              Tool 调用层                      │            │
│  │  Vehicle*Manager | WeatherUtils | VlManager  │            │
│  │  (@Tool 注解, LLM 自主决策调用)               │            │
│  ├─────────────────────────────────────────────┤            │
│  │             硬件通信层                        │            │
│  │  SoaService (SOA总线 AIDL) | CameraSdk.jar  │            │
│  │  adapter_vr.jar (VR/TTS)                    │            │
│  └─────────────────────────────────────────────┘            │
│                                                              │
│  进程边界                                                  │
├─────────────────────────────────────────────────────────────┤
│  CameraService (独立进程) | VR Service (独立进程)            │
└─────────────────────────────────────────────────────────────┘
```

### 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin + Java 混编 |
| AI 框架 | LangChain4j 1.1.0 |
| LLM 模型 | qwen-turbo（对话）、qwen3-vl-plus（场景识别）、qwen-vl-max（视觉问答） |
| LLM API | 阿里云 DashScope（OpenAI 兼容接口） |
| 通信 | AIDL（Launcher/SystemUI ↔ AIAgent、SOA 总线、Camera）、ServiceManager（系统服务发现） |
| UI | **无**（纯后台 Service） |
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
│   │   ├── AndroidManifest.xml             # 声明 1 个 Service + 1 个 Receiver
│   │   ├── aidl/
│   │   │   ├── com/hirain/aiagent/         # ← 新增：AIDL 源文件（白盒）
│   │   │   │   ├── IAIAgentAidlInterface.aidl    # sendMessage / sendMessageWithImage / requestAI
│   │   │   │   ├── IAIAgentAidlListener.aidl     # onAIResponse 回调
│   │   │   │   └── AIAgentData.aidl              # Parcelable 数据
│   │   │   ├── com/android/myllmservice/   # 系统 LLM 服务 AIDL（未使用）
│   │   │   └── hirain/carina/              # SOA 总线 AIDL（38 个文件）
│   │   ├── java/com/hirain/aiagent/
│   │   │   ├── AIAgentService.kt           # [核心] 前台 Service，AIDL Binder 实现
│   │   │   ├── AIAgent.java                # [核心] Facade 单例（客户端使用）
│   │   │   ├── AIAgentData.java            # Parcelable 数据类
│   │   │   ├── IAIAgentServiceListener.java # 本地回调接口
│   │   │   ├── AIAgentWindowView.kt        # 废弃保留（UI 已移除）
│   │   │   ├── FloatWindowView.kt          # 废弃保留（UI 已移除）
│   │   │   ├── MainActivity.java           # 调试用聊天界面
│   │   │   ├── BootCompleteReceiver.kt     # 开机广播 → 启动 Service
│   │   │   ├── PermissonActivity.kt        # 悬浮窗权限（UI 移除后保留）
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
│   │   │   │   ├── SoaDataUseCase.java        # SOA 数据读写
│   │   │   │   └── SoaCallbackImpl.kt         # SOA 回调处理
│   │   │   ├── JavaScriptInterface.java       # WebView JS 接口（空实现）
│   │   │   ├── CustomAdapter.java             # RecyclerView 适配器（未使用）
│   │   │   └── InputStreamCompat.java          # IO 兼容工具类
│   │   └── libs/
│   │       ├── CameraSdk.jar                 # CameraService AIDL 接口
│   │       ├── AIAgentSdk.jar                # ← 构建产物：对外暴露的 AIDL 接口 + Facade 单例
│   │       └── adapter_vr.jar                # VR/TTS 服务闭源 SDK
│   │
│   ├── ChatServer/                           # [Android Library] LLM 对话引擎
│   │   └── src/main/java/.../chatserver/
│   │       └── ChatServer.java               # LangChain4j 对话管理 + Tool Calling 循环
│   │
│   ├── SceneMatch/                           # [Android Library] 视觉场景识别
│   │   └── src/main/java/.../scenematch/
│   │       └── SceneMatch.java               # Qwen3-VL-Plus 图片 → 场景分类
│   │
│   ├── SceneServer/                          # [Android Library] 场景驱动座舱响应
│   │   └── src/main/java/.../sceneserver/
│   │       └── SceneServer.java              # 场景 → LLM → 车控工具调用 → 语音建议
│   │
│   ├── SoaService/                           # [Android Library] SOA 总线 AIDL 封装
│   │   ├── src/main/aidl/                    # 40+ 个 AIDL 接口定义
│   │   └── src/main/java/.../soaservice/
│   │       └── SoaService.kt                 # 单例，所有方法为空实现
│   │
│   ├── VehicleDoorManager/                   # [Android Library] 车门 @Tool
│   ├── VehicleWindowManager/                 # [Android Library] 车窗/天窗/除霜 @Tool
│   ├── VehicleSeatManager/                   # [Android Library] 座椅/加热/通风/按摩 @Tool
│   ├── VehicleAcManager/                     # [Android Library] 空调全功能 @Tool
│   ├── VehicleChassisManager/                # [Android Library] 底盘驾驶模式 @Tool
│   ├── VehicleFragManager/                   # [Android Library] 香氛 + DMS + 车速 @Tool
│   ├── VlManager/                            # [Android Library] 前向视觉问答 @Tool
│   ├── http-client-ok/                       # [Android Library] OkHttp 适配 langchain4j
│   ├── chat_memory_sqlite/                   # [Android Library] SQLite ChatMemory 持久化
│   ├── weatherutils/                         # [Android Library] 天气查询 @Tool
│   ├── geoutils/                             # [Android Library] 地理工具
│   ├── android_document_loader/              # [Android Library] 文档加载（未启用）
│   └── build-logic/                          # 自定义 Gradle 插件
│       └── DesugarTransformPlugin.kt         # 字节码反糖插件
```

---

## 4. 核心组件

### 4.1 AIAgentService（主控中枢 - AIDL 服务端）

**文件：** `AIAgentService.kt`

这是整个应用的"大脑"，一个前台 Service。职责包括：

- **AIDL Binder 实现**：通过 `AIAgentBinder`（`IAIAgentAidlInterface.Stub`）对外暴露接口：
  - `sendMessage(String text)` → 纯文字对话请求（Launcher 调用）
  - `sendMessageWithImage(String text, String imageBase64)` → 图文对话请求（Launcher 调用，通过 VlManager）
  - `requestAI(String arg)` → VR 语音协议（SystemUI 调用，保留不改）
  - `registerListener / unregisterListener` → 注册/注销回调监听
- **Camera 接入**：通过 `Camera.getInstance().init()` 连接 CameraService 进程，注册 `ICameraServiceListener` 回调，每 1 秒请求一次前向摄像头抓拍
- **场景识别循环**：收到抓拍图片后，调用 `SceneMatch.vl_scene_match()` 识别场景；若场景变化且不在对话中，调用 `SceneServer.scene_server()` 生成主动响应
- **Listener 回调推送**：`sendMessage()` 获取到 LLM 回复后，遍历所有已注册的 `IAIAgentAidlListener`，通过 AIDL 回调将结果推回调用方
- **15 秒超时保护**：每个 `sendMessage` 请求附带 15 秒超时定时器，超时时通过回调返回错误

**AIDL 双向通信机制：**
```
调用方（Launcher 进程）                    AIAgentService（AIAgent 进程）
     │                                            │
     ├─ registerListener(listener) ──────────────→│  (记录回调代理)
     │                                            │
     ├─ sendMessage(text) ───────────────────────→│
     │                                            ├─ ChatServer.chat(text)
     │                                            ├─ LLM 回复
     │                                            └─ listener.onAIResponse(data)
     │←────────────────────────────────────────────│  (AIDL 跨进程回调)
     │                                            │
     ├─ sendMessageWithImage(text, img) ─────────→│
     │                                            ├─ VlManager 多模态推理
     │                                            └─ listener.onAIResponse(data)
     │←────────────────────────────────────────────│
```

### 4.2 AIAgent.java（Facade 单例 - AIDL 客户端封装）

**文件：** `AIAgent.java`

封装在 `AIAgentSdk.jar` 中，供 Launcher、SystemUI 等调用方使用：

```java
public class AIAgent {
    public static AIAgent getInstance();
    public void init(Context context);                          // 建立 AIDL 连接
    public int sendMessage(String text);                        // 发送文字请求
    public int sendMessageWithImage(String text, String b64);   // 发送图文请求
    public int requestAI(String query);                         // 发送语音指令
    public void registerAIAgentLisener(IAIAgentServiceListener); // 注册回调
}
```

**关键设计：**
- `init()` → `ensureServiceRunning()`（`startForegroundService` 启动 AIAgent）→ `bindService()`（建立 AIDL 连接）
- 绑定失败时通过 `HandlerThread` 后台线程每 2 秒自动重试
- 内部 `AIAgentAidlCallback` 实现 `IAIAgentAidlListener.Stub`，将 AIDL 跨进程回调转为本地 `IAIAgentServiceListener` 回调
- `DeathRecipient` 监听 Binder 连接状态，断开后自动重连

### 4.3 AIAgentSdk.jar（AIDL 通信载体）

**路径：** `app/libs/AIAgentSdk.jar`

编译产物，包含：
- AIDL 生成的 Stub/Proxy 类（`IAIAgentAidlInterface`、`IAIAgentAidlListener`）
- Parcelable 数据类（`AIAgentData`）
- Facade 单例类（`AIAgent`）
- 本地回调接口（`IAIAgentServiceListener`）

**使用方式：** 将 JAR 复制到调用方项目的 `libs/`，在 `build.gradle` 中添加 `implementation files('libs/AIAgentSdk.jar')`，然后通过 `AIAgent.getInstance()` 调用。

**重建方式：** 修改 AIDL 或 AIAgent.java 后，运行 `./gradlew app:assembleDebug`，然后从 `app/build/intermediates/javac/.../classes/com/hirain/aiagent/` 提取 SDK 相关 class 文件打包。

### 4.4 ChatServer（对话引擎）

**文件：** `ChatServer.java`

基于 LangChain4j 的多轮对话引擎：

- **LLM 模型**：`qwen-turbo`（DashScope），`parallelToolCalls = true`
- **System Prompt**：约 90 行，定义角色定位、能力边界、控车逻辑、交互规范
- **对话记忆**：`MessageWindowChatMemory` + `PersistentChatMemorySqlite`，最多 50 条，App 重启不丢失
- **Tool 列表**：天气、车门、车窗、座椅、空调、香氛、前向摄像头（共 7 类，40+ 个方法）
- **处理流程**：用户输入 → 拼接车辆状态 JSON（5 个 Manager 实时采集）→ LLM 推理 → 如果返回 Tool Call → 执行工具 → 结果送回 LLM → 递归直到纯文本

### 4.5 SceneMatch（场景识别）

**文件：** `SceneMatch.java`

基于多模态视觉模型的场景分类器：

- **模型**：`qwen3-vl-plus`（DashScope），强制 JSON Schema 输出
- **场景分类**：舱外浓烟、雨雪天气、工程施工、乘客休息、驾驶员疲劳、其他
- **调用时机**：AIAgentService 每 1 秒获得 Camera 抓拍后调用

### 4.6 SceneServer（场景响应）

**文件：** `SceneServer.java`

场景驱动的主动座舱服务：

- 根据场景名称选择不同 Tool 集合（雨雪→车窗+座椅+空调+底盘；施工→车窗+空调）
- 包含场景特异性逻辑（"雨雪天禁止开窗"、"疲劳驾驶调低空调温度"）
- 最终通过 LLM 合并工具执行结果和场景特异性文本，输出 80 字以内的语音建议

### 4.7 VlManager（多模态视觉问答）

**文件：** `VlManager.java`

提供两个功能：
1. **前向视野问答（"窗景随问"）**：`qwen-vl-max` 模型 + 前向摄像头实时画面
2. **`sendMessageWithImage()` 多模态推理**：被 `AIAgentBinder.sendMessageWithImage()` 调用，接收 Base64 图片 + 文字，返回 VL 模型回复

### 4.8 Vehicle*Manager 系列（车控工具）

6 个模块，共约 **40+ 个 @Tool 方法**：

| 模块 | 工具数 | 覆盖功能 |
|------|-------|---------|
| VehicleDoorManager | 1 | 车门闭锁/解锁 |
| VehicleWindowManager | 11 | 车窗、天窗、遮阳帘、除霜、后视镜加热 |
| VehicleSeatManager | 11 | 座椅加热、通风、按摩、方向盘加热 |
| VehicleAcManager | 15 | 空调开关、温度、风量、ECO、负离子、内外循环、扫风、出风口 |
| VehicleChassisManager | 1 | 底盘模式（普通/越野/雪地） |
| VehicleFragManager | 2 | 香氛类型、浓度 |

### 4.9 SoaService（SOA 总线封装）

**文件：** `SoaService.kt`

单例对象，通过反射调用 `ServiceManager.getService("SoaBusService")` 获取系统 SOA 总线 Binder。

**关键问题：所有与硬件通信的方法体均为空，仅有 `Log.d` 日志输出。**

SoaService 中的 `getDoorStatus()`、`set_ac_status()`、`setFlWindowStatus()` 等 **40+ 个方法全部为空实现**（`return ""` 或空函数体）。这是当前项目**最大的功能性断点**——"LLM 决定关窗"到"车窗真的关闭"之间的链路不通。

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
    ├─ createWorkThreadHandle()                    ← LLM 调用的后台线程
    ├─ Camera.getInstance().init()                 ← 连接 CameraService（try-catch 保护）
    ├─ 启动 1 秒定时器：requestCapture()            ← 周期性抓拍
    ├─ VlManager(ctx)                              ← 初始化多模态模型
    ├─ VRServiceManager.getInstance().initCallback() ← 初始化语音唤醒/TTS
    ├─ SceneServer(this)                            ← 初始化场景响应引擎
    ├─ ChatServer(this, vl)                         ← 初始化对话引擎（try-catch 保护）
    └─ （无悬浮窗创建）
```

### 5.2 AIDL 通信流程

#### 路径 A：Launcher 文字对话（核心流程）

```
Launcher 进程                           AIAgent 进程                      DashScope
    │                                        │                              │
    ├─ AIAgent.getInstance().init()          │                              │
    │   ├─ ensureServiceRunning()            │                              │
    │   ├─ bindService() ──────────────────→│                              │
    │   │                                   ├─ onBind() → Binder           │
    │   ├─ registerListener(callback) ──────→│                             │
    │   │                                   ├─ registerListener()          │
    │   │← onAIAgentServiceConnected()       │                             │
    │                                        │                              │
    │  [用户语音 → ASR 识别 → 角色前缀]     │                              │
    ├─ sendMessage(prefix + text) ──────────→│                             │
    │   (AIDL IPC)                          │                              │
    │                                        ├─ 15s 超时倒计时             │
    │                                        ├─ post 到 mWorkHandler        │
    │                                        │   ├─ chat.chat(text)         │
    │                                        │   │   ├─ 写入 chatMemory     │
    │                                        │   │   ├─ getVehicleStatus()  │
    │                                        │   │   └─ LLM 调用 ──────────→│
    │                                        │   │                          ├─ qwen-turbo
    │                                        │   │       ←── AI 回复 ───────│
    │                                        │   ├─ 取消超时                │
    │                                        │   ├─ AIAgentData(value)      │
    │                                        │   └─ notifyListeners()       │
    │   ←── onAIResponse(data) ──────────────┤                             │
    │   (AIDL 跨进程回调)                    │                             │
    │                                        │                              │
    ├─ handleAIResponse(text)                │                              │
    │   ├─ setContent(当前可见的 View)       │                              │
    │   └─ voicePlaybackAI(text) ← TTS 播报  │                             │
```

#### 路径 B：Launcher 图文对话

```
Launcher                                    AIAgent
    │                                           │
    ├─ sendMessageWithImage(text, base64) ─────→│
    │                                           ├─ Base64.decode → byte[]
    │                                           ├─ VlManager.front_camera_interactionPositive()
    │                                           │   ├─ TextContent + ImageContent
    │                                           │   └─ qwen-vl-max 多模态推理
    │                                           ├─ 回调同路径 A
```

#### 路径 C：VR 语音协议（SystemUI / 保留）

```
VR Service → requestAI("@#%^StartListen") → 开始聆听
VR Service → requestAI("用户说话内容")    → 累积文本
VR Service → requestAI("@#%^StopListen")  → 触发 ChatServer.chat()
  → 回复 → appendNagativeResponse() → TTS 播报
```

#### 路径 D：场景主动识别

```
Camera 每 1 秒抓拍 → SceneMatch 场景识别 → 场景变化 → SceneServer 主动响应
```

---

## 6. 功能开发进度评估

> 说明：完成度反映相对于"可用于量产"的标准。以下评估基于完整源码分析。

### 6.1 对外通信接口

| 功能 | 完成度 | 状态 | 说明 |
|------|--------|------|------|
| sendMessage AIDL | 100% | ✅ 完成 | Binder 实现完整，15s 超时保护，异常回调 |
| sendMessageWithImage AIDL | 100% | ✅ 完成 | Binder 实现完整，调用 VlManager 多模态模型 |
| requestAI 语音协议 | 100% | ✅ 完成 | 保留 StartListen/StopListen/ClearChatMemory |
| registerListener 回调 | 100% | ✅ 完成 | DeathRecipient 自动清理，遍历推送 |
| AIAgentSdk.jar | 100% | ✅ 完成 | 从黑盒 JAR 恢复为白盒源码，可独立重建 |
| Facade 单例（AIAgent.java）| 100% | ✅ 完成 | bindService + 自动重试 + 服务启动 + 回调桥接 |

### 6.2 LLM 对话引擎

| 功能 | 完成度 | 状态 | 说明 |
|------|--------|------|------|
| LLM 对话（ChatServer） | 90% | 🟢 完成 | qwen-turbo 通过 DashScope API 已接入，支持多轮对话 |
| 对话记忆持久化 | 90% | 🟢 完成 | SQLite 持久化（PersistentChatMemorySqlite），重启后记忆仍在 |
| Tool Calling 循环 | 90% | 🟢 完成 | LangChain4j 递归 Tool Calling 完整实现 |
| 车辆状态注入 | 80% | 🟢 完成 | 每次对话自动拼接 5 个 Manager 的车辆状态 JSON |
| System Prompt 管理 | 90% | 🟢 完成 | 包含角色定义、能力边界、交互规范等 |
| 多 Topic 支持 | 10% | ⚪ 待定 | 当前单条对话记忆支持 50 条消息，无 Topic 切换 |

### 6.3 多模态视觉

| 功能 | 完成度 | 状态 | 说明 |
|------|--------|------|------|
| 前向视野问答（VlManager） | 85% | 🟢 完成 | qwen-vl-max 已接入，有摄像头数据时走真实推理 |
| 场景识别（SceneMatch） | 85% | 🟢 完成 | qwen3-vl-plus 已接入，JSON Schema 强制结构化输出 |
| 场景主动响应（SceneServer） | 75% | 🟡 进行中 | Tool 选择 + LLM 调用完整，但场景特异性操作为模拟 |
| 摄像头周期性抓拍 | 80% | 🟢 完成 | CameraSdk.jar AIDL 已对接，每 1 秒请求一次 |
| 摄像头容错 | 90% | 🟢 完成 | 无 CameraService 时 init() 不崩溃（try-catch），requestCapture 返回 -1 |
| 场景特异性操作 | 20% | 🔴 壳子 | `handleSceneSpecified()` 返回模拟文本，标注了 TODO |
| 摄像头图片缓存 | 80% | 🟢 完成 | VlManager 通过 `front_camera_save()` 缓存最新抓拍 |

### 6.4 车控功能

| 功能 | 完成度 | 状态 | 说明 |
|------|--------|------|------|
| Tool 接口定义 | 95% | 🟢 完成 | 40+ @Tool 方法完全定义，含参数描述和校验 |
| Tool 请求路由 | 95% | 🟢 完成 | hasTool / handleToolRequest 完整实现 |
| VehicleDoorManager | 50% | 🟡 壳子 | @Tool 完整，SoaService 调用空实现 |
| VehicleWindowManager | 50% | 🟡 壳子 | 同上 |
| VehicleSeatManager | 50% | 🟡 壳子 | 同上 |
| VehicleAcManager | 50% | 🟡 壳子 | 同上 |
| VehicleChassisManager | 50% | 🟡 壳子 | 同上 |
| VehicleFragManager | 50% | 🟡 壳子 | 同上 |
| SoaService 方法体 | 5% | 🔴 空壳 | 所有方法体为空（`Log.d` + `return`），这是最大功能性断点 |
| SOA 总线 AIDL 绑定 | 60% | 🟡 部分完成 | 能连接系统 SoaBusService，但 setValues 填充的是测试数据 |
| SOA 数据获取（getValues） | 40% | 🟡 部分 | AIDL 调用可用，数据处理逻辑不完整 |
| 实验性 CardRepository 架构 | 30% | 🔴 未集成 | data/ 目录下 LocalPrompt/CardRepository 未被主流程引用 |

### 6.5 外部集成

| 功能 | 完成度 | 状态 | 说明 |
|------|--------|------|------|
| 天气查询（WeatherUtils） | 90% | 🟢 完成 | 真实 API 已接入，Key 硬编码 |
| TTS 语音播报 | 90% | 🟢 完成 | 通过 adapter_vr.jar 的 VRServiceManager.speak() |
| 语音唤醒/ASR | 60% | 🟡 部分 | adapter_vr.jar 提供 onAsrResult 回调，依赖 VR Service 进程 |
| 前向视野问答 - 有摄像头 | 80% | 🟢 完成 | 走 qwen-vl-max 真实推理 |
| 前向视野问答 - 无摄像头 | 60% | 🟡 降级 | 回退到 assets 静态图片（audi.jpg） |
| Launcher 集成 | 95% | 🟢 完成 | AIDL 双向通信已打通，语音→LLM→回复→显示 链路完整 |
| init() 延时加载 | 90% | 🟢 完成 | 自动启动 AIAgent + HandlerThread 重试 + MIUI 适配 |
| WebView 导航卡片 | 20% | 🔴 未使用 | showHtml() 方法有 HTML/CSS 模板，从未被调用（UI 已移除） |

### 6.6 质量保证

| 功能 | 完成度 | 状态 | 说明 |
|------|--------|------|------|
| sendMessage 超时保护 | 100% | ✅ 完成 | 15 秒超时 + 异常回调 |
| Camera init 异常保护 | 100% | ✅ 完成 | try-catch 包裹 |
| ChatServer 异常保护 | 100% | ✅ 完成 | try-catch + null 检查 |
| Binder 死亡自动重连 | 100% | ✅ 完成 | DeathRecipient + HandlerThread 重试 |
| AIAgent 未安装容错 | 100% | ✅ 完成 | bindService 失败后 2 秒循环重试 |
| MIUI 自启动兼容 | 80% | 🟢 完成 | `startForegroundService` + 自启动权限引导 |
| 编译验证 | 100% | ✅ 完成 | AIAgent + Launcher 均 BUILD SUCCESSFUL |
| 语音→AIAgent 完整链路 | 90% | 🟢 通过 | 真人测试验证通过 |
| 多模态图片→AIAgent 链路 | 50% | 🟡 未实测 | 代码完整，需 CameraService 环境实测 |

### 6.7 总结

| 层次 | 完成度 | 关键瓶颈 |
|------|--------|---------|
| 对外 AIDL 接口 | **100%** | 无 |
| LLM 接入层（模型调用、对话管理） | **90%** | 多模型使用，DashScope 全线接入 |
| 工具定义层（@Tool） | **95%** | 40+ 工具覆盖全部车控功能，参数描述详尽 |
| 工具路由层（hasTool + handleToolRequest） | **95%** | 完整实现，JSON 参数解析 |
| 硬件通信层（SoaService） | **5%** | **所有方法为空，最大功能性断点** |
| 场景识别 | **85%** | 真实模型推理，特异性操作为模拟 |
| UI 悬浮窗 | **0%** | **已移除（转为纯后台服务）** |
| 实验性代码（data/） | **30%** | 架构有设计，未集成 |

**一句话结论：** AIAgent 的 LLM 推理链路和工具定义已经完整，AIDL 对外接口已打通 Launcher 调用链。但硬件通信层（SoaService）的 40+ 个方法体全部为空，是从 "LLM 决定关窗" 到 "车窗真的关闭" 之间完全断开的环节。当前状态可以在模拟器和真机上验证 LLM 对话和 Tool Calling 逻辑，但无法实际控车。
