# sendMessage(text) 完整处理流程 — UML 时序图

```mermaid
sequenceDiagram
    autonumber
    participant L as Launcher 进程
    participant F as AIAgent.java<br>Facade 单例
    participant A as AIAgentService<br>AIDL Binder
    participant MH as mainHandler<br>(主线程)
    participant WH as mWorkHandler<br>(work_thread)
    participant CS as ChatServer
    participant MM as ChatMemory<br>(SQLite 持久化)
    participant VM as Vehicle*Manager<br>(5个)
    participant DS as DashScope LLM<br>(qwen-turbo)
    participant NL as notifyListeners

    Note over L,NL: ====== 第 1 阶段：AIDL 跨进程调用 ======

    L->>F: AIAgent.getInstance().sendMessage(text)
    Note right of L: Launcher 调用 Facade 单例<br>（JAR 中的统一入口）

    F->>F: m_service == null?
    Note right of F: 检查 AIDL 连接是否建立

    F->>A: m_service.sendMessage(text)
    Note right of F: AIDL IPC 跨进程调用<br>（同步，不阻塞 Launcher UI）

    Note over L,A: ====== 第 2 阶段：超时保护 ======

    A->>MH: postDelayed(timeoutRunnable, 15000ms)
    Note right of A: 启动 15 秒超时定时器<br>（主线程，到期自动推送超时错误）

    Note over A,NL: ====== 第 3 阶段：切换到后台线程 ======

    A->>WH: post { chat.chat(message) }
    Note right of A: 异步投递到 work_thread<br>（网络请求不能在主线程执行）

    Note over WH,NL: ====== 第 4 阶段：ChatServer 对话处理 ======

    WH->>CS: chat(userMessage)

    CS->>MM: chatMemory.add(UserMessage)
    Note right of CS: 写入对话记忆<br>SQLite 持久化，最大 50 条

    CS->>CS: chatWithVehicleStatus()
    Note right of CS: 构建车辆状态上下文

    CS->>VM: getDoorStatus()
    VM-->>CS: 车门 JSON
    CS->>VM: getWindowStatus()
    VM-->>CS: 车窗 JSON
    CS->>VM: getSeatStatus()
    VM-->>CS: 座椅 JSON
    CS->>VM: getAcStatus()
    VM-->>CS: 空调 JSON
    CS->>VM: getFragStatus()
    VM-->>CS: 香氛 JSON
    Note over CS,VM: 5 个 Manager 分别采集车辆实时状态<br>→ 合并为一个 JSON 字符串<br>→ 作为 UserMessage("车辆状态", JSON)<br>   前置追加到对话历史

    Note over CS,NL: ====== 第 5 阶段：首次 LLM 调用 ======

    CS->>DS: model.chat(request)
    Note right of CS: 构建 ChatRequest：<br>  messages = 车辆状态 + 历史对话<br>  toolSpecifications = 7 类工具

    Note over CS,DS: ====== 第 6 阶段：LLM 推理 & Tool Calling 循环 ======

    DS-->>CS: ChatResponse (aiMessage)
    Note right of DS: qwen-turbo 返回结果

    CS->>CS: processAiResponse(aiResponse)
    Note right of CS: 核心处理逻辑

    CS->>MM: chatMemory.add(aiMessage)
    Note right of CS: 先将 AI 回复写入记忆

    rect rgb(240, 248, 255)
    Note over CS,DS: *** Tool Calling 循环（递归） ***

    alt 有 ToolExecutionRequest
        CS->>CS: handleTools(toolrequest)
        Note right of CS: 工具路由：根据 request.name()<br>  匹配到对应 Manager
        CS->>VM: doorManager.handleToolRequest()
        VM-->>CS: 工具执行结果
        CS->>MM: 写入工具结果到记忆
        Note right of CS: ToolExecutionResultMessage

        CS->>DS: model.chat(request_with_tool)
        Note right of CS: 将工具执行结果送回 LLM<br>让 LLM 基于结果生成回复

        DS-->>CS: ChatResponse (tool result)
        CS->>CS: processAiResponse() ← 递归
        Note right of CS: 可能又有新的 Tool Call<br>  递归直到 LLM 返回纯文本
    else 无 Tool Call（纯文本）
        CS-->>CS: return aiMessage.text()
        Note right of CS: 最终结果
    end
    end

    CS-->>WH: 返回回复文本
    Note left of CS: 纯文字 LLM 回复

    Note over WH,NL: ====== 第 7 阶段：超时取消 & 封装结果 ======

    WH->>MH: removeCallbacks(timeoutRunnable)
    Note right of WH: 回复已收到，取消 15 秒超时

    WH->>WH: AIAgentData(value = 回复文本)
    Note right of WH: LLM 回复 String → byte[]<br>  封装为 Parcelable 数据

    Note over WH,NL: ====== 第 8 阶段：异常处理 ======

    alt chat 为 null
        WH->>WH: throw Exception("ChatServer 未初始化")
        WH->>WH: catch → AIAgentData("系统: 请求失败...")
    else chat.chat() 抛异常
        WH->>WH: catch → AIAgentData("系统: 请求超时" 或 "系统: 请求失败...")
    else 15 秒超时
        MH->>MH: timeoutRunnable 触发
        MH->>MH: AIAgentData("系统: 请求超时")
        MH->>NL: notifyAIAgentListeners()
    end

    Note over WH,NL: ====== 第 9 阶段：回调推送 ======

    WH->>NL: notifyAIAgentListeners(0, 0, resultData)
    Note right of WH: 参数：seqId=0, captureMode=0, data

    NL->>MH: mainHandler.post { 遍历监听器 }
    Note right of NL: 切换到主线程执行<br>避免并发修改 mIAIAgentAidlListeners

    loop 遍历 mIAIAgentAidlListeners
        NL->>NL: listener.onAIResponse(seqId, mode, data)
        Note right of NL: AIDL 跨进程回调<br>DeathRecipient 自动清理已断开 listener
    end

    Note over NL,L: ====== 第 10 阶段：跨进程传输回 Launcher ======

    NL-->>L: onAIResponse → AIAgent.java
    Note right of NL: AIDL IPC 回调<br>到达 Launcher 进程

    L->>L: AIAgentAidlCallback.onAIResponse()
    Note right of L: Facade 内部 AIDL 回调桥接

    L->>L: notifyAIResponse()
    Note right of L: 遍历 IAIAgentServiceListener 列表

    L->>L: onAIResponse(data)
    Note right of L: IAIAgentServiceListener<br>→ MainActivity 实现

    L->>L: handleAIResponse(text)
    Note right of L: 解析 byte[] → String<br>判断是否以"系统:"开头

    alt 正常回复
        L->>L: voicePlaybackAI(text) → TTS 播报
    else 错误消息（"系统:..."）
        L->>L: 仅显示，不播报 TTS
    end

    L->>L: 路由到当前可见 View
    Note right of L: DataAnalysisView setContent<br>PictureTextView setContent<br>VoiceInteractionView setContent
```

---

## 流程说明

### 整体阶段划分

| 阶段 | 步骤（编号） | 线程/进程 | 说明 |
|------|------------|----------|------|
| AIDL 跨进程调用 | ①-③ | Launcher 进程 → AIAgent 进程 | Launcher 通过 AIAgentSdk.jar 的 Facade 调用 AIDL 接口，跨进程到 AIAgentService |
| 超时保护 | ④ | 主线程 | 启动 15 秒定时器，到期自动返回"系统: 请求超时" |
| 后台线程分发 | ⑤ | work_thread | AIDL Binder 线程把任务投递到后台 HandlerThread |
| ChatServer 处理 | ⑥-⑨ | work_thread | 写入对话历史 + 采集车辆状态 + 构建 LLM 请求 |
| 首次 LLM 调用 | ⑩ | work_thread | 调用 DashScope qwen-turbo，传入消息 + 工具定义 |
| Tool Calling 递归 | ⑪-⑳ | work_thread | LLM 可能要求调用工具，执行后结果送回 LLM 再推理 |
| 结果封装与异常处理 | ㉑-㉖ | work_thread / 主线程 | 取消超时 + 封装 AIAgentData + 处理各类异常 |
| 回调推送 | ㉗-㉚ | 主线程 | 遍历注册的 AIDL 回调监听器，跨进程推回 Launcher |
| Launcher 接收 | ㉛-㉟ | Launcher 进程 | Facade 桥接 AIDL 回调 → IAIAgentServiceListener → handleAIResponse → UI 更新 + TTS |

### 关键设计点

**15 秒超时**：`mainHandler.postDelayed(timeoutRunnable, 15000)`。若 ChatServer 在 15 秒内未返回，自动通过 `notifyAIAgentListeners` 推送"系统: 请求超时"。若正常返回，通过 `removeCallbacks` 取消定时器。

**双重线程安全**：
- ChatServer 调用在 `mWorkHandler`（`work_thread`）上执行，不阻塞主线程
- 回调推送通过 `mainHandler.post {}` 回到主线程，避免并发修改监听器列表

**Tool Calling 递归**：LangChain4j 的模型调用返回后，`processAiResponse()` 检测是否有 `ToolExecutionRequest`。如果有，执行工具 → 写入对话记忆 → 再次调用模型 → 递归直到 LLM 返回纯文本。每次递归都可能产生新的 Tool Call。

**三级异常处理**：
| 层 | 捕获目标 | 结果 |
|----|---------|------|
| chat == null | 提前检查抛出 | "系统: 请求失败 - ChatServer 未初始化" |
| sendMessage try-catch | chat.chat() 异常 | "系统: 请求失败 - <异常信息>" |
| 15 秒超时 | 异步定时器 | "系统: 请求超时" |

### 关键类索引

| 步骤 | 类 | 方法 | 文件路径 |
|------|-----|------|---------|
| ① | `AIAgent` | `sendMessage()` | `app/.../AIAgent.java:112` |
| ③ | `AIAgentBinder` | `sendMessage()` | `app/.../AIAgentService.kt:477` |
| ⑥ | `ChatServer` | `chat()` | `ChatServer/.../ChatServer.java:203` |
| ⑦ | `` | `chatWithVehicleStatus()` | `ChatServer/.../ChatServer.java:173` |
| ⑨ | `` | `getVehicleStatus()` | `ChatServer/.../ChatServer.java:184` |
| ⑪ | `` | `processAiResponse()` | `ChatServer/.../ChatServer.java:152` |
| ⑫ | `` | `handleTools()` | `ChatServer/.../ChatServer.java:134` |
| ㉕ | `AIAgentService` | `notifyAIAgentListeners()` | `app/.../AIAgentService.kt:569` |
| ㉛ | `AIAgent.AIAgentAidlCallback` | `onAIResponse()` | `app/.../AIAgent.java:253` |
| ㉟ | `MainActivity` | `onAIResponse()` → `handleAIResponse()` | `Launcher/.../MainActivity.java` |
