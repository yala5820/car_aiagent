# AIAgent 收到消息后的内部处理流程

> 本文档详细描述 Launcher 通过 AIDL 发来文字/图片后，AIAgent 内部经过的完整处理链路。

---

## 一、整体架构概览

```
Launcher 进程                          AIAgent 进程
    │                                      │
    ├─ sendMessage(text)                    │
    │   └─ AIDL IPC ─────────────────────→ AIAgentBinder.sendMessage()
    │                                      │
    ├─ sendMessageWithImage(text, img)      │
    │   └─ AIDL IPC ─────────────────────→ AIAgentBinder.sendMessageWithImage()
    │                                      │
    │                                      ├─ mWorkHandler（后台工作线程）
    │                                      │   ├─ ChatServer.chat() → qwen-turbo
    │                                      │   └─ VlManager.front_camera_interactionPositive() → qwen-vl-max
    │                                      │
    │                                      └─ mainHandler（主线程）
    │                                          └─ notifyAIAgentListeners()
    │                                              │
    │   ← AIDL 回调 ────────────────────────── listener.onAIResponse(data)
    │                                      │
    ▼                                      ▼
 MainActivity.handleAIResponse()           （处理完成）
```

**两个关键 Handler**：
| Handler | 线程 | 用途 |
|---------|------|------|
| `mainHandler` | 主线程（UI 线程） | 投递超时任务、遍历 listeners 推送回调 |
| `mWorkHandler` | 后台线程（`work_thread` HandlerThread） | 执行 LLM 调用（网络请求不能阻塞主线程） |

---

## 二、纯文字请求：sendMessage(text) 的完整处理流程

### 第 1 步：AIDL IPC 到达服务端

`AIAgentBinder.sendMessage(text)` 被调用（第 477 行）。

```kotlin
override fun sendMessage(text: String?) {
    val message = text ?: ""
    // ...
}
```

形如 `"[角色:助理] 今天天气多少度？"` 的字符串（角色前缀由 Launcher 在调用前拼接）。

### 第 2 步：启动 15 秒超时定时器

```kotlin
val timeoutRunnable = Runnable {
    Log.e("TAG", "sendMessage timeout after 15s")
    val errorData = AIAgentData().apply {
        value = "系统: 请求超时".toByteArray(Charsets.UTF_8)
    }
    notifyAIAgentListeners(0, 0, errorData)
}
mainHandler.postDelayed(timeoutRunnable, SENDMESSAGE_TIMEOUT_MS)  // 15000ms
```

如果 15 秒内没有收到 LLM 回复，这个 Runnable 会被主线程执行，向 Launcher 返回"系统: 请求超时"。

### 第 3 步：切换到后台线程执行

```kotlin
mWorkHandler?.post {
    // 以下所有 LLM 调用都在 work_thread 执行
}
```

`mWorkHandler` 是 `HandlerThread("work_thread")` 的 Handler。网络请求（DashScope API）必须在后台线程执行。

### 第 4 步：调用 ChatServer.chat(message)

```kotlin
if (chat == null) {
    throw Exception("ChatServer 未初始化")
}
val res = chat!!.chat(message)
```

进入 **ChatServer.java** 的 `chat()` 方法（第 203 行）：

```java
public String chat(String userMessage) {
    chatMemory.add(UserMessage.userMessage(userMessage));   // ① 写入对话历史
    String resp = chatWithVehicleStatus();                   // ② 构建车辆上下文 + 调 LLM
    return resp;
}
```

#### 4.1 写入对话记忆

```java
chatMemory.add(UserMessage.userMessage(userMessage));
```

`chatMemory` 是 `MessageWindowChatMemory`，最大保留最近 **50 条消息**，底层使用 **SQLite** 持久化（`PersistentChatMemorySqlite`）。即使 App 重启，之前的对话历史也不会丢失。

#### 4.2 构建车辆状态上下文

`chatWithVehicleStatus()` 方法（第 173 行）做了两件事：

**4.2.1 获取当前车辆状态**

调用 `getVehicleStatus()`，遍历 5 个 Vehicle Manager：

| Manager | 查询内容 | 示例 |
|---------|---------|------|
| `VehicleDoorManager` | 车门状态 | 左前门：关闭，右后门：关闭... |
| `VehicleWindowManager` | 车窗状态 | 左前窗：0%，天窗：关闭... |
| `VehicleSeatManager` | 座椅/方向盘 | 主驾座椅位置，方向盘加热... |
| `VehicleAcManager` | 空调状态 | 温度 22°C，模式 AUTO，风量 3... |
| `VehicleFragManager` | 香氛状态 | 香氛类型：海洋，浓度：低... |

拼接成一个 JSON，并附加当前位置信息（目前硬编码为 `"天津市西青区"`）：

```json
{
  "车门": {...}, "车窗": {...}, "座椅、方向盘": {...},
  "空调": {...}, "香氛": {...}, "当前地址": "天津市西青区"
}
```

**4.2.2 构建 LLM 请求**

将车辆状态作为一条 UserMessage 前置到对话历史最前面：

```java
List<ChatMessage> tmp = new ArrayList<>();
tmp.add(UserMessage.userMessage("车辆状态", getVehicleStatus()));
tmp.addAll(chatMemory.messages());   // 包含系统提示 + 历史对话

ChatRequest request = ChatRequest.builder()
    .messages(tmp)
    .toolSpecifications(mergedTools)  // 注册所有 Tool
    .build();

ChatResponse aiResponse = model.chat(request);  // 发起 HTTP 请求
```

#### 4.3 LLM 的配置参数

```java
model = OpenAiChatModel.builder()
    .apiKey("sk-11129fb7941f49dbb083039a93a160bc")
    .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
    .modelName("qwen-turbo")
    .parallelToolCalls(true)   // 允许并行调用多个 Tool
    .build();
```

- **模型**：阿里云 DashScope 的 `qwen-turbo`
- **API 格式**：OpenAI 兼容（`/compatible-mode/v1`）
- **超时**：连接 30s，读取 120s

#### 4.4 System Prompt（系统提示词）

ChatServer 构造函数中包含一份约 90 行的中文 system prompt（第 48-90 行），定义：

- **角色**：车载 AI 助手，专注安全驾驶
- **能力**：自然聊天、推荐、前向视野互动、精准/模糊控车
- **原则**：认知友好、上下文感知、主动智能、严谨准确
- **功能规范**：天气查询、音乐推荐、控车指令拆解等
- **约束**：禁止承诺无法完成的功能

#### 4.5 注册的 Tool 清单

构造时向 LangChain4j 注册了 7 个工具组，共 40+ 个 `@Tool` 方法：

| 工具类 | 数量 | 功能 |
|--------|------|------|
| `WeatherUtils` | ~3 | 天气查询（真实 API，key: `c9af807ed...`） |
| `VehicleDoorManager` | ~5 | 车门状态查询 + 控制 |
| `VehicleWindowManager` | ~11 | 车窗/天窗控制 |
| `VehicleSeatManager` | ~11 | 座椅/方向盘调节 |
| `VehicleAcManager` | ~15 | 空调控制（温度/风量/模式等） |
| `VehicleFragManager` | ~5 | 香氛控制 |
| `VlManager` | 1 | 前向摄像头视觉识别 |

### 第 5 步：处理 LLM 回复（含 Tool Calling 循环）

`processAiResponse(aiResponse)` 方法（第 152 行）：

```java
private String processAiResponse(ChatResponse aiResponse) {
    AiMessage aiMessage = aiResponse.aiMessage();
    chatMemory.add(aiMessage);   // 将 AI 回复写入记忆

    if (aiMessage.hasToolExecutionRequests()) {
        // — 需要调用工具 —
        List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
        for (ToolExecutionRequest toolrequest : requests) {
            String result = handleTools(toolrequest);   // 执行工具
            // 将工具执行结果写入对话记忆
            chatMemory.add(ToolExecutionResultMessage.from(toolrequest, result));
        }
        // 再次调用 LLM，让它基于工具结果生成最终回复
        ChatRequest request_with_tool = ChatRequest.builder()
            .messages(chatMemory.messages())
            .toolSpecifications(mergedTools)
            .build();
        ChatResponse aiResponse_with_tool = model.chat(request_with_tool);
        return processAiResponse(aiResponse_with_tool);  // 递归，LLM 可能还要调工具
    } else {
        // — 不需要工具，直接返回 LLM 文本 —
        return aiResponse.aiMessage().text();
    }
}
```

**示例**：用户说"空调调到 25 度"

1. LLM 返回 `ToolExecutionRequest(name="set_ac_temperature", arguments={"temperature": 25})`
2. `handleTools()` → `acManager.set_ac_temperature(25)` → SoaService（空实现，返回 OK）
3. 工具结果 `"空调已设置为25度"` 写回 chatMemory
4. 再次调 LLM → LLM 生成最终回复 `"好的，已为您将空调温度调到 25 度"`
5. 返回纯文本给用户

### 第 6 步：取消超时 + 封装结果

```kotlin
mainHandler.removeCallbacks(timeoutRunnable)  // 取消 15 秒超时
val resultData = AIAgentData().apply {
    value = res.toByteArray(Charsets.UTF_8)    // LLM 回复文本 → byte[]
}
notifyAIAgentListeners(0, 0, resultData)
```

异常情况下（网络超时、LLM 错误等），catch 块会生成 `"系统: 请求失败 - <异常信息>"` 并同样通知 listeners。

### 第 7 步：推送回复给所有已注册的 Launcher

```kotlin
private fun notifyAIAgentListeners(seqId: Int, captureMode: Int, data: AIAgentData) {
    mainHandler.post {
        for ((listener) in mIAIAgentAidlListeners) {
            try {
                listener.onAIResponse(seqId, captureMode, data)
            } catch (e: RemoteException) {
                Log.e("TAG", "notify listener failed", e)
            }
        }
    }
}
```

`mIAIAgentAidlListeners` 是一个 `MutableMap<IAIAgentAidlListener, DeathRecipient>`，在 `registerListener()` 时由 Launcher 的 `AIAgent.java` Facade 注册。

`IAIAgentAidlListener` 是 AIDL 回调接口。`onAIResponse()` 调用会通过 AIDL IPC 跨进程传回 Launcher，最终到达 `AIAgent.java` 的 `AIAgentAidlCallback` → `IAIAgentServiceListener.onAIResponse()` → `MainActivity.handleAIResponse()`。

### 文字请求完整流程时序

```
Launcher                 AIAgent                         DashScope
   │                        │                                │
   ├── sendMessage ────────→│                                │
   │                        ├── 启动 15s 超时                │
   │                        ├── post 到 mWorkHandler          │
   │                        │   ├── chat.chat(message)       │
   │                        │   │   ├── 写入 chatMemory(SQLite)
   │                        │   │   ├── getVehicleStatus()   │
   │                        │   │   └── chatWithVehicleStatus()
   │                        │   │       └── model.chat() ────→│
   │                        │   │                            │── LLM 推理
   │                        │   │              ←── 返回 ChatResponse
   │                        │   │   ├── processAiResponse()  │
   │                        │   │   │   ├── hasTools?        │
   │                        │   │   │   │   ├── handleTools() │
   │                        │   │   │   │   └── model.chat()─→│ （第二轮）
   │                        │   │   │   └── return text      │
   │                        │   ├── 取消超时                  │
   │                        │   ├── AIAgentData(value)       │
   │                        │   └── notifyAIAgentListeners() │
   │   ← onAIResponse ───────┤                                │
   │                        │                                │
   ▼                        ▼                                ▼
 handleAIResponse()
```

---

## 三、图文请求：sendMessageWithImage(text, imageBase64) 的完整处理流程

### 与纯文字请求的差异

图文请求的前 3 步（AIDL → 超时 → 切换到 work_thread）与纯文字相同。差异从第 4 步开始：

### 第 4 步：Base64 解码图片

```kotlin
val imageBytes = Base64.decode(imgB64, Base64.DEFAULT)
```

`imgB64` 是由 Launcher 的 `PictureTextView.getVideoBase64Code()` 生成的 JPEG 格式 Base64 字符串。解码后得到原始 JPEG 字节数组。

### 第 5 步：调用 VlManager 多模态视觉模型

```kotlin
if (vl != null) {
    val res = vl!!.front_camera_interactionPositive(message, imageBytes)
}
```

进入 **VlManager.java** 的 `front_camera_interactionPositive()` 方法（第 183 行）：

#### 5.1 构建多模态消息

```java
String img_b64 = getBase64(ctx, byteArray);   // byte[] → Base64 字符串

SystemMessage systemmsg = SystemMessage.from(front_camera_system_msg);
UserMessage usrmsg = UserMessage.from(
    TextContent.from(text),                    // 用户的文字问题
    ImageContent.from(img_b64, "image/jpeg")   // 图片（JPEG 格式，Base64 编码）
);
```

系统提示词（`front_camera_system_msg`）将 AI 定位为"窗景随问"——前向摄像头多模态视觉问答助手。

#### 5.2 调用多模态 LLM

```java
ChatResponse aiResponse = vlModel.chat(systemmsg, usrmsg);
return aiResponse.aiMessage().text() + warning_msg;
```

`vlModel` 配置：

```java
this.vlModel = OpenAiChatModel.builder()
    .apiKey("sk-11129fb7941f49dbb083039a93a160bc")
    .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
    .modelName("qwen-vl-max")
    .build();
```

- **模型**：阿里云 DashScope 的 `qwen-vl-max`（通义千问多模态大模型）
- **输入**：系统提示 + 用户文本 + JPEG 图片
- **输出**：对图片内容的描述 / 回答用户关于图片的问题

**注意**：`qwen-vl-max` 与文字链路的 `qwen-turbo` 是**不同的模型**，VlManager 有自己独立的 `ChatModel` 实例。多模态模型的回复中会附带一行警告信息：`"(该响应仅对本次前向视野互动有效...)"`。

### 第 6 步：取消超时 + 封装 + 回调

与纯文字请求的第 6、7 步完全相同。

### 图文请求完整流程

```
Launcher                              AIAgent                         DashScope
   │                                     │                                │
   ├── sendMessageWithImage(text, b64) ──→│                                │
   │                                     ├── 启动 15s 超时                │
   │                                     ├── post 到 mWorkHandler          │
   │                                     │   ├── Base64.decode(b64)→byte[]
   │                                     │   ├── vl.front_camera_interactionPositive()
   │                                     │   │   ├── 构建 SystemMessage    │
   │                                     │   │   ├── 构建 UserMessage      │
   │                                     │   │   │   ├── TextContent(text) │
   │                                     │   │   │   └── ImageContent(jpg) │
   │                                     │   │   └── vlModel.chat() ──────→│
   │                                     │   │                            │── qwen-vl-max
   │                                     │   │                 ←── 返回 ChatResponse
   │                                     │   ├── 取消超时                  │
   │                                     │   ├── AIAgentData(value)       │
   │                                     │   └── notifyAIAgentListeners() │
   │   ← onAIResponse ─────────────────────┤                                │
   │                                     │                                │
   ▼                                     ▼                                ▼
 handleAIResponse()
```

---

## 四、两条路线的关键差异对比

| 对比维度 | sendMessage | sendMessageWithImage |
|---------|-------------|---------------------|
| **使用的 LLM 模型** | `qwen-turbo`（ChatServer） | `qwen-vl-max`（VlManager） |
| **输入格式** | 纯文本 | 文本 + JPEG 图片（Base64） |
| **Tool Calling** | ✅ 支持（40+ 个车辆控制 Tool） | ❌ 不支持（直接视觉问答，单轮） |
| **对话记忆** | ✅ 50 轮 SQLite 持久化 | ❌ 无记忆（独立交互） |
| **车辆状态注入** | ✅ 每次请求附加实时车辆 JSON | ❌ 不注入 |
| **System Prompt** | 约 90 行中文（车载助手） | 约 10 行（视觉问答助手） |
| **超时** | 15 秒 | 15 秒 |
| **回复格式** | LLM 纯文本 | LLM 文本 + 警告后缀 |
| **ChatModel 实例** | ChatServer 内部创建 | VlManager 内部创建（独立） |

---

## 五、错误处理汇总

| 阶段 | 可能错误 | 返回给 Launcher 的内容 |
|------|---------|----------------------|
| chat 为 null | ChatServer 构造函数抛异常未被捕获 | `"系统: 请求失败 - ChatServer 未初始化"` |
| 15 秒超时 | 网络不通 / LLM 服务过载 | `"系统: 请求超时"` |
| chat.chat() 异常 | DashScope API 返回错误 / 网络断开 | `"系统: 请求失败 - <异常信息>"` |
| vl 为 null | VlManager 构造函数失败 | `"系统: 多模态模型未初始化"` |
| vl 调用异常 | qwen-vl-max API 错误 | `"系统: 请求失败 - <异常信息>"` |

所有错误都以 `"系统:"` 开头，Launcher 的 `handleAIResponse()` 可以据此区分错误消息和正常回复（错误消息显示但不播报 TTS）。

---

## 六、AIAgentService 启动时的一整套初始化

理解消息处理流程前，需要知道 `onCreate()` 里初始化了什么：

```
AIAgentService.onCreate()
├── createWorkThreadHandle()          → mWorkHandler（后台线程，LLM 调用在此执行）
├── startForeground()                 → 前台服务通知
├── Camera.getInstance().init()        → 前向摄像头（可能不可用，try-catch 保护）
├── scheduleAtFixedRate(1s)           → 每秒调用 requestCapture()
├── vl = VlManager(this)              → 视觉模型 qwen-vl-max（独立 ChatModel）
├── mManager = VRServiceManager()     → 语音识别/合成服务（TTS）
├── scene_server = SceneServer(this)  → 场景感知服务
└── chat = ChatServer(this, vl)       → 对话引擎 qwen-turbo + 50 轮记忆 + 40+ Tool
```

---

## 七、关键类文件索引

| 文件 | 核心职责 |
|------|---------|
| `AIAgentService.kt:477` | `sendMessage()` — 文字入口 |
| `AIAgentService.kt:514` | `sendMessageWithImage()` — 图文入口 |
| `AIAgentService.kt:569` | `notifyAIAgentListeners()` — 回调推送 |
| `ChatServer.java:203` | `chat()` — 对话主入口 |
| `ChatServer.java:173` | `chatWithVehicleStatus()` — 车辆状态注入 + LLM 调用 |
| `ChatServer.java:152` | `processAiResponse()` — Tool Calling 递归处理 |
| `ChatServer.java:134` | `handleTools()` — 7 个工具组路由 |
| `ChatServer.java:99` | 构造函数 — 模型/记忆/工具初始化 |
| `VlManager.java:183` | `front_camera_interactionPositive()` — 多模态模型调用 |
| `VlManager.java:47` | 构造函数 — qwen-vl-max 模型初始化 |
