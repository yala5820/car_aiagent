# Agent 统一入口实现总结

## 概述

将原本分散的 `sendMessage`、`sendMessageWithImage`、`requestAI`（控制命令）三个入口统一为 `processAgentRequest(AgentRequest request)` 单一入口。同时统一了回调出口为 `AgentResponse`，取代旧的 `AIAgentData`。改造后 AIAgent 对外只暴露一个方法，调用方只需构造一个 `AgentRequest` 结构体即可完成所有类型请求。

---

## 一、做了什么

### 1.1 新增 4 个文件

| 文件 | 包 | 说明 |
|------|-----|------|
| `AgentRequest.java` | `com.hirain.aiagent` | 统一请求体 Parcelable，所有请求类型共用 |
| `AgentRequest.aidl` | `com.hirain.aiagent` | AIDL 对 AgentRequest 的 parcelable 声明 |
| `AgentResponse.java` | `com.hirain.aiagent` | 统一响应体 Parcelable，所有回调统一格式 |
| `AgentResponse.aidl` | `com.hirain.aiagent` | AIDL 对 AgentResponse 的 parcelable 声明 |

### 1.2 修改 5 个文件

| 文件 | 改动内容 |
|------|----------|
| `IAIAgentAidlInterface.aidl` | 删除 `requestAI(String)`、`sendMessage(String)`、`sendMessageWithImage(String, String)`，替换为 `processAgentRequest(in AgentRequest)` |
| `IAIAgentAidlListener.aidl` | 删除 `onAIResponse(int, int, AIAgentData)`，替换为 `onAIResponse(in AgentResponse)` |
| `IAIAgentServiceListener.java` | 接口方法签名同步更新为 `onAIResponse(AgentResponse)` |
| `AIAgentService.kt` | 删除 3 个旧 override 方法和 `processNagativeRequest`，实现 `processAgentRequest` + 4 个按 inputType 路由的内部方法 |
| `AIAgent.java` | 客户端 API 删除 `requestAI()` / `sendMessage()` / `sendMessageWithImage()`，新增 `processAgentRequest(AgentRequest)`，更新回调处理 |

### 1.3 删除 2 个文件

| 文件 | 原因 |
|------|------|
| `AIAgentData.aidl` | 被 AgentResponse 替代，不再通过 AIDL 传输 |
| `AIAgentData.java` | 被 AgentResponse 替代，无其他引用 |

---

## 二、Agent 入口与出口设计

### 2.1 AgentRequest — 统一入口结构体

```
AgentRequest (Parcelable, 跨进程传输)
│
├── requestId    : String  (REQUIRED)  调用方生成的 UUID，用于关联请求与响应
├── sessionId    : String  (REQUIRED)  会话标识，服务端用于路由到对应的 ChatMemory
├── sourceApp    : String  (REQUIRED)  来源应用标识，用于日志和溯源
├── text         : String  (REQUIRED)  用户文本输入
├── inputType    : String  (REQUIRED)  请求类型：TEXT / IMAGE / VOICE / CONTROL
├── sceneType    : String  (OPTIONAL)  场景识别结果（雨雪天气/工程施工等）
├── imagePath    : String  (OPTIONAL)  图片文件路径（取代旧的 Base64 传输）
├── extraContext : Map<String, String>  (OPTIONAL)  扩展键值对
└── timestamp    : long    (REQUIRED)  请求时间戳（epoch millis）
```

### 2.2 AgentResponse — 统一出口结构体

```
AgentResponse (Parcelable, 跨进程传输)
│
├── requestId    : String    关联的请求 ID
├── sessionId    : String    关联的会话 ID
├── success      : boolean   是否成功
├── text         : String    成功时 = 输出文本，失败时 = 错误描述
├── errorType    : String    失败时的错误类型（NONE = 成功）
└── timestamp    : long      响应时间戳
```

### 2.3 inputType 路由 — 四种请求的分发逻辑

```
processAgentRequest(request)
  │
  ├─ inputType = "TEXT" ──────────────────────────────────────────
  │   └─ handleTextRequest()
  │       ├─ 启动 15s 超时定时器（超时返回 AgentResponse.errorType=TIMEOUT）
  │       ├─ traceManager.startSession("chat", sessionId, text)
  │       ├─ chatOrchestrator.execute(text, ctx)
  │       ├─ session.setStatus(result)
  │       └─ notifyAIAgentListeners(AgentResponse)
  │           ├─ success=true  → response.text = result.output()
  │           └─ success=false → response.text = errorDetail, errorType = ErrorType.name
  │
  ├─ inputType = "IMAGE" ─────────────────────────────────────────
  │   └─ handleImageRequest()
  │       ├─ 启动 15s 超时定时器
  │       ├─ 从 imagePath 读取图片文件（File.readBytes()）
  │       ├─ vl.frontCameraInteractionPositive(text, imageBytes)
  │       └─ notifyAIAgentListeners(AgentResponse)
  │           └─ success=true → response.text = VL 模型输出
  │
  ├─ inputType = "VOICE" ─────────────────────────────────────────
  │   └─ handleVoiceRequest()
  │       ├─ 设置 mNagativeReqExecuting = true, stopTTS()
  │       ├─ traceManager.startSession → chatOrchestrator.execute
  │       └─ notifyAIAgentListeners(AgentResponse)
  │           └─ + TTS 播报: mManager.speak(response.text)
  │
  └─ inputType = "CONTROL" ──────────────────────────────────────
      └─ handleControlRequest()
          ├─ "StartListen" / "@#%^StartListen"
          │   └─ mChating = true, mRequestAIStr = "", stopTTS()
          ├─ "StopListen" / "@#%^StopListen"
          │   └─ mChating = false
          ├─ "ClearChatMemory" / "@#%^ClearChatMemory"
          │   └─ memoryOrchestrator.startNewSession(), chatOrchestrator.cleanMemory()
          └─ 其他 → Log.w("Unknown control command")
              └─ CONTROL 请求不产生 AgentResponse 回调
```

### 2.4 改造前后接口对比

| 维度 | 改造前 | 改造后 |
|------|--------|--------|
| AIDL 方法数 | 5（requestAI + sendMessage + sendMessageWithImage + registerListener + unregisterListener） | 3（processAgentRequest + registerListener + unregisterListener） |
| 请求参数 | 离散参数（String text, String imageBase64） | 结构化 AgentRequest |
| 图片传输 | Base64 字符串 | 文件路径（imagePath） |
| 响应格式 | `onAIResponse(int seqId, int captureMode, AIAgentData)` — 固定参数，信息有限 | `onAIResponse(AgentResponse)` — requestId 关联，含 success/errorType |
| 控制命令 | `requestAI("@#%^xxx")` 特殊字符串编码 | `inputType=CONTROL, text="StartListen"` 结构化字段 |
| Parcelable | 1 个（AIAgentData） | 2 个（AgentRequest + AgentResponse） |

---

## 三、改造后的 AIAgent 中枢与外界的联系方式

### 3.1 整体通信架构

```
┌────────────────────────────────────────────────────────┐
│                    外部 App/模块                         │
│  AIAgent.getInstance().processAgentRequest(request)     │
└────────────────────┬───────────────────────────────────┘
                     │ Binder IPC (AIDL)
                     ▼
┌────────────────────────────────────────────────────────┐
│  IAIAgentAidlInterface.Stub (AIAgentService)            │
│  processAgentRequest(AgentRequest)                      │
│    ├─ TEXT    → handleTextRequest()                     │
│    ├─ IMAGE   → handleImageRequest()                    │
│    ├─ VOICE   → handleVoiceRequest()                    │
│    └─ CONTROL → handleControlRequest()                  │
└────────────────────┬───────────────────────────────────┘
                     │ 回调 Binder IPC
                     ▼
┌────────────────────────────────────────────────────────┐
│  IAIAgentAidlListener.onAIResponse(AgentResponse)       │
│  → AIAgent.notifyAIResponse()                           │
│  → IAIAgentServiceListener.onAIResponse()               │
└────────────────────────────────────────────────────────┘
```

### 3.2 AIDL 接口定义（跨进程边界）

```
// IAIAgentAidlInterface.aidl — 服务端暴露的方法
interface IAIAgentAidlInterface {
    void processAgentRequest(in AgentRequest request);
    void registerListener(IAIAgentAidlListener listener);
    void unregisterListener(IAIAgentAidlListener listener);
}

// IAIAgentAidlListener.aidl — 客户端回调接口
interface IAIAgentAidlListener {
    void onAIResponse(in AgentResponse response);
}
```

### 3.3 客户端调用示例

```java
// TEXT 请求
AgentRequest req = new AgentRequest();
req.setRequestId(UUID.randomUUID().toString());
req.setSessionId("user_001");
req.setSourceApp("voice_assistant");
req.setText("打开空调");
req.setInputType("TEXT");
req.setTimestamp(System.currentTimeMillis());
AIAgent.getInstance().processAgentRequest(req);

// IMAGE 请求
AgentRequest imgReq = new AgentRequest();
imgReq.setRequestId(UUID.randomUUID().toString());
imgReq.setSessionId("user_001");
imgReq.setSourceApp("front_camera");
imgReq.setText("前面是什么？");
imgReq.setInputType("IMAGE");
imgReq.setImagePath("/data/local/tmp/capture.jpg");
imgReq.setTimestamp(System.currentTimeMillis());
AIAgent.getInstance().processAgentRequest(imgReq);

// VOICE 请求（语音完整输入后触发 AI + TTS）
AIAgent.getInstance().processAgentRequest(new AgentRequest() {{
    setRequestId(UUID.randomUUID().toString());
    setSessionId("user_001");
    setSourceApp("voice_assistant");
    setText("导航去火车站");
    setInputType("VOICE");
    setTimestamp(System.currentTimeMillis());
}});
```

### 3.4 响应回调示例

```java
AIAgent.getInstance().registerAIAgentLisener(new IAIAgentServiceListener() {
    @Override
    public void onAIAgentServiceConnected() { }

    @Override
    public void onAIAgentServiceDisconnected() { }

    @Override
    public void onAIResponse(AgentResponse response) {
        if (response.isSuccess()) {
            // response.getText() = "空调已开启"
            // response.getRequestId() 可关联到原请求
        } else {
            // response.getErrorType() = "TIMEOUT" / "MODEL_CALL_FAILED" / ...
            // response.getText() = "系统: 请求超时"
        }
    }
});
```

### 3.5 兼容性说明

- **AIDL 接口不向后兼容**：外部模块如果直接绑定 `IAIAgentAidlInterface` 的旧方法（`sendMessage`/`requestAI`），需要同步更新到新签名
- **Java 客户端 API 不向后兼容**：`AIAgent.sendMessage()` / `AIAgent.requestAI()` 已移除，调用方需改为 `AIAgent.processAgentRequest()`
- **`IAIAgentServiceListener` 接口不向后兼容**：`onAIResponse(int, int, AIAgentData)` 已改为 `onAIResponse(AgentResponse)`
