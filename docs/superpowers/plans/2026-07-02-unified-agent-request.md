# 统一 Agent 入口实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `sendMessage`、`sendMessageWithImage`、`requestAI`（控制命令）三个入口统一为 `processAgentRequest(AgentRequest request)` 单一入口

**Architecture:** 
- 新增 `AgentRequest` Parcelable 承载所有请求参数（requestId/sessionId/sourceApp/text/inputType/sceneType/imagePath/extraContext/timestamp）
- 新增 `AgentResponse` Parcelable 统一回调格式（requestId/sessionId/success/text/errorType/timestamp）
- 改造 AIDL 接口去掉三个旧方法替换为一个新方法，对应改造 listener 回调
- `AIAgentService` 内部按 `inputType`（TEXT/IMAGE/VOICE/CONTROL）路由到对应的处理逻辑

**Tech Stack:** Android AIDL, Parcelable, Kotlin/Java

## Global Constraints

- 图像通过 `imagePath`（文件系统路径）传递，不使用 Base64
- `inputType` 枚举值：`TEXT`、`IMAGE`、`VOICE`、`CONTROL`
- `sceneType` 为场景匹配引擎返回的场景名称（如雨雪天气、工程施工等），非必填
- `extraContext` 为 `Map<String, String>`，非必填
- AIDL 接口是跨进程的，所有数据类型必须支持 AIDL 传输

---

## 文件结构

### 新增文件

| 文件 | 类型 | 职责 |
|------|------|------|
| `java/.../AgentRequest.java` | Parcelable | 统一请求参数体 |
| `aidl/.../AgentRequest.aidl` | AIDL | AgentRequest 的 AIDL 声明 |
| `java/.../AgentResponse.java` | Parcelable | 统一响应体 |
| `aidl/.../AgentResponse.aidl` | AIDL | AgentResponse 的 AIDL 声明 |

### 修改文件

| 文件 | 改动 |
|------|------|
| `aidl/.../IAIAgentAidlInterface.aidl` | 替换 3 个方法（requestAI/sendMessage/sendMessageWithImage）为 `processAgentRequest(in AgentRequest)` |
| `aidl/.../IAIAgentAidlListener.aidl` | 替换 `onAIResponse(int,int,AIAgentData)` 为 `onAIResponse(in AgentResponse)` |
| `java/.../IAIAgentServiceListener.java` | 同步更新 callback 方法签名 |
| `java/.../AIAgent.java` | 客户端 API 改为 `processAgentRequest(AgentRequest)`，去掉旧方法 |
| `kotlin/.../AIAgentService.kt` | 实现 `processAgentRequest`，按 inputType 路由；去掉旧 override 方法 |

### 删除文件

| 文件 | 原因 |
|------|------|
| `aidl/.../AIAgentData.aidl` | 不再通过 AIDL 传输 |
| `java/.../AIAgentData.java` | 被 AgentResponse 替代，无其他使用者 |

---

## Task 1: 创建 AgentRequest Parcelable

**Files:**
- Create: `app/src/main/java/com/hirain/aiagent/AgentRequest.java`
- Create: `app/src/main/aidl/com/hirain/aiagent/AgentRequest.aidl`

**Interfaces:**
- Produces: `AgentRequest` — 包含所有请求字段的 Parcelable 类

- [ ] **Step 1: 编写 AgentRequest.java**

```java
package com.hirain.aiagent;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class AgentRequest implements Parcelable {

    private String requestId;               // REQUIRED, 调用方生成的 UUID
    private String sessionId;               // REQUIRED, 会话标识
    private String sourceApp;               // REQUIRED, 来源应用标识
    private String text;                    // REQUIRED, 用户文本输入
    private String inputType;               // REQUIRED, TEXT / IMAGE / VOICE / CONTROL
    private String sceneType;               // OPTIONAL, 场景名称
    private String imagePath;               // OPTIONAL, 图片文件路径
    private Map<String, String> extraContext; // OPTIONAL, 额外键值对
    private long timestamp;                 // REQUIRED, 请求时间戳 (epoch millis)

    public AgentRequest() {}

    protected AgentRequest(Parcel in) {
        requestId = in.readString();
        sessionId = in.readString();
        sourceApp = in.readString();
        text = in.readString();
        inputType = in.readString();
        sceneType = in.readString();
        imagePath = in.readString();
        timestamp = in.readLong();
        int mapSize = in.readInt();
        if (mapSize > 0) {
            extraContext = new HashMap<>(mapSize);
            for (int i = 0; i < mapSize; i++) {
                extraContext.put(in.readString(), in.readString());
            }
        } else {
            extraContext = null;
        }
    }

    public static final Creator<AgentRequest> CREATOR = new Creator<AgentRequest>() {
        @Override
        public AgentRequest createFromParcel(Parcel in) {
            return new AgentRequest(in);
        }

        @Override
        public AgentRequest[] newArray(int size) {
            return new AgentRequest[size];
        }
    };

    @Override
    public int describeContents() { return 0; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(requestId);
        dest.writeString(sessionId);
        dest.writeString(sourceApp);
        dest.writeString(text);
        dest.writeString(inputType);
        dest.writeString(sceneType);
        dest.writeString(imagePath);
        dest.writeLong(timestamp);
        if (extraContext != null && !extraContext.isEmpty()) {
            dest.writeInt(extraContext.size());
            for (Map.Entry<String, String> entry : extraContext.entrySet()) {
                dest.writeString(entry.getKey());
                dest.writeString(entry.getValue());
            }
        } else {
            dest.writeInt(0);
        }
    }

    // ── Getters & Setters ──

    public String getRequestId() { return requestId; }
    public void setRequestId(String v) { this.requestId = v; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String v) { this.sessionId = v; }

    public String getSourceApp() { return sourceApp; }
    public void setSourceApp(String v) { this.sourceApp = v; }

    public String getText() { return text; }
    public void setText(String v) { this.text = v; }

    public String getInputType() { return inputType; }
    public void setInputType(String v) { this.inputType = v; }

    public String getSceneType() { return sceneType; }
    public void setSceneType(String v) { this.sceneType = v; }

    public String getImagePath() { return imagePath; }
    public void setImagePath(String v) { this.imagePath = v; }

    public Map<String, String> getExtraContext() { return extraContext; }
    public void setExtraContext(Map<String, String> v) { this.extraContext = v; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long v) { this.timestamp = v; }
}
```

- [ ] **Step 2: 编写 AgentRequest.aidl**

```aidl
// AgentRequest.aidl
package com.hirain.aiagent;

parcelable AgentRequest;
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL

---

## Task 2: 创建 AgentResponse Parcelable

**Files:**
- Create: `app/src/main/java/com/hirain/aiagent/AgentResponse.java`
- Create: `app/src/main/aidl/com/hirain/aiagent/AgentResponse.aidl`

**Interfaces:**
- Produces: `AgentResponse` — 统一响应体 Parcelable

- [ ] **Step 1: 编写 AgentResponse.java**

```java
package com.hirain.aiagent;

import android.os.Parcel;
import android.os.Parcelable;

public class AgentResponse implements Parcelable {

    private String requestId;       // 对应 AgentRequest.requestId
    private String sessionId;       // 对应 AgentRequest.sessionId
    private boolean success;
    private String text;            // 输出文本（成功时）或错误描述（失败时）
    private String errorType;       // null 表示成功, 否则为错误类型名
    private long timestamp;         // 响应时间戳

    public AgentResponse() {}

    protected AgentResponse(Parcel in) {
        requestId = in.readString();
        sessionId = in.readString();
        success = in.readByte() != 0;
        text = in.readString();
        errorType = in.readString();
        timestamp = in.readLong();
    }

    public static final Creator<AgentResponse> CREATOR = new Creator<AgentResponse>() {
        @Override
        public AgentResponse createFromParcel(Parcel in) {
            return new AgentResponse(in);
        }

        @Override
        public AgentResponse[] newArray(int size) {
            return new AgentResponse[size];
        }
    };

    @Override
    public int describeContents() { return 0; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(requestId);
        dest.writeString(sessionId);
        dest.writeByte((byte) (success ? 1 : 0));
        dest.writeString(text);
        dest.writeString(errorType);
        dest.writeLong(timestamp);
    }

    // ── Getters & Setters ──

    public String getRequestId() { return requestId; }
    public void setRequestId(String v) { this.requestId = v; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String v) { this.sessionId = v; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean v) { this.success = v; }

    public String getText() { return text; }
    public void setText(String v) { this.text = v; }

    public String getErrorType() { return errorType; }
    public void setErrorType(String v) { this.errorType = v; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long v) { this.timestamp = v; }
}
```

- [ ] **Step 2: 编写 AgentResponse.aidl**

```aidl
// AgentResponse.aidl
package com.hirain.aiagent;

parcelable AgentResponse;
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL

---

## Task 3: 改造 AIDL 接口和 Listener

**Files:**
- Modify: `app/src/main/aidl/com/hirain/aiagent/IAIAgentAidlInterface.aidl`
- Modify: `app/src/main/aidl/com/hirain/aiagent/IAIAgentAidlListener.aidl`
- Modify: `app/src/main/java/com/hirain/aiagent/IAIAgentServiceListener.java`

**Interfaces:**
- Produces: 新的 AIDL 接口定义和服务端 listener 接口
- Consumes: `AgentRequest`, `AgentResponse`

- [ ] **Step 1: 修改 IAIAgentAidlInterface.aidl**

```aidl
// IAIAgentAidlInterface.aidl
package com.hirain.aiagent;

import com.hirain.aiagent.AgentRequest;

interface IAIAgentAidlInterface {
    void processAgentRequest(in AgentRequest request);
    void registerListener(IAIAgentAidlListener listener);
    void unregisterListener(IAIAgentAidlListener listener);
}
```

- [ ] **Step 2: 修改 IAIAgentAidlListener.aidl**

```aidl
// IAIAgentAidlListener.aidl
package com.hirain.aiagent;

import com.hirain.aiagent.AgentResponse;

interface IAIAgentAidlListener {
    void onAIResponse(in AgentResponse response);
}
```

- [ ] **Step 3: 修改 IAIAgentServiceListener.java**

```java
package com.hirain.aiagent;

public interface IAIAgentServiceListener {
    void onAIAgentServiceConnected();
    void onAIAgentServiceDisconnected();
    void onAIResponse(AgentResponse response);
}
```

- [ ] **Step 4: 删除 AIAgentData.aidl 和 AIAgentData.java**

```bash
rm app/src/main/aidl/com/hirain/aiagent/AIAgentData.aidl
```

保留 AIAgentData.java 内容不变（先不删除，等编译验证通过后再清理），因为编译可能仍引用它。

Wait — 更好的做法是：**先不改 AIAgentData.java**，等最后确认没有编译依赖后再删除。这个步骤只删 .aidl 文件。

---

## Task 4: 改造 AIAgentService.kt

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/AIAgentService.kt`

**Consumes:** `AgentRequest`, `AgentResponse`

**核心变更：**
1. 替换 `AIAgentBinder` 中的 `requestAI` / `sendMessage` / `sendMessageWithImage` 为 `processAgentRequest`
2. 删除 `processNagativeRequest` 方法（逻辑合并到 VOICE 处理中）
3. 新增 `handleXxxRequest` 内部方法按 inputType 路由

- [ ] **Step 1: 重写 AIAgentBinder — 去掉旧方法，添加 processAgentRequest**

```kotlin
// 在 inner class AIAgentBinder 中替换现有三个 override 方法为：

@Throws(RemoteException::class)
override fun processAgentRequest(request: AgentRequest?) {
    if (request == null) return
    Log.d(TAG, "processAgentRequest: type=${request.inputType} id=${request.requestId}")

    when (request.inputType) {
        "TEXT" -> handleTextRequest(request)
        "IMAGE" -> handleImageRequest(request)
        "VOICE" -> handleVoiceRequest(request)
        "CONTROL" -> handleControlRequest(request)
        else -> Log.w(TAG, "Unknown inputType: ${request.inputType}")
    }
}
```

- [ ] **Step 2: 实现 handleTextRequest**

```kotlin
private fun handleTextRequest(request: AgentRequest) {
    val timeoutRunnable = Runnable {
        Log.e(TAG, "processAgentRequest TEXT timeout after 15s")
        notifyAIAgentListeners(AgentResponse().apply {
            requestId = request.requestId
            sessionId = request.sessionId
            success = false
            text = "系统: 请求超时"
            errorType = "TIMEOUT"
            timestamp = System.currentTimeMillis()
        })
    }
    mainHandler.postDelayed(timeoutRunnable, SENDMESSAGE_TIMEOUT_MS)

    mWorkHandler?.post {
        val session = traceManager.startSession("chat", request.sessionId ?: "default_user", request.text ?: "")
        try {
            Log.d(TAG, "handleTextRequest begin")
            val ctx = mapOf("user_id" to (request.sessionId ?: "default_user")) + session.toTraceContext().toContextData()
            val result = chatOrchestrator.execute(request.text ?: "", ctx)
            mainHandler.removeCallbacks(timeoutRunnable)
            session.setStatus(result.isSuccess, result.errorDetail())
            notifyAIAgentListeners(AgentResponse().apply {
                requestId = request.requestId
                sessionId = request.sessionId
                success = result.isSuccess
                text = if (result.isSuccess) result.output() else (result.errorDetail() ?: "请求失败")
                errorType = if (!result.isSuccess) result.errorType()?.name else null
                timestamp = System.currentTimeMillis()
            })
        } catch (e: Exception) {
            mainHandler.removeCallbacks(timeoutRunnable)
            Log.e(TAG, "handleTextRequest failed", e)
            session.setStatus(false, e.message)
            notifyAIAgentListeners(AgentResponse().apply {
                requestId = request.requestId
                sessionId = request.sessionId
                success = false
                text = "系统: 请求失败 - ${e.message}"
                errorType = "EXCEPTION"
                timestamp = System.currentTimeMillis()
            })
        } finally {
            session.close()
        }
    }
}
```

- [ ] **Step 3: 实现 handleImageRequest**

```kotlin
private fun handleImageRequest(request: AgentRequest) {
    val timeoutRunnable = Runnable {
        Log.e(TAG, "processAgentRequest IMAGE timeout after 15s")
        notifyAIAgentListeners(AgentResponse().apply {
            requestId = request.requestId
            sessionId = request.sessionId
            success = false
            text = "系统: 请求超时"
            errorType = "TIMEOUT"
            timestamp = System.currentTimeMillis()
        })
    }
    mainHandler.postDelayed(timeoutRunnable, SENDMESSAGE_TIMEOUT_MS)

    mWorkHandler?.post {
        try {
            Log.d(TAG, "handleImageRequest begin")
            if (vl == null) {
                mainHandler.removeCallbacks(timeoutRunnable)
                notifyAIAgentListeners(AgentResponse().apply {
                    requestId = request.requestId
                    sessionId = request.sessionId
                    success = false
                    text = "系统: 多模态模型未初始化"
                    errorType = "VL_NOT_INITIALIZED"
                    timestamp = System.currentTimeMillis()
                })
                return@post
            }
            val imageBytes = if (request.imagePath != null) {
                // 从文件读取
                val file = java.io.File(request.imagePath)
                if (file.exists()) file.readBytes() else throw Exception("图片文件不存在: ${request.imagePath}")
            } else {
                throw Exception("IMAGE 请求缺少 imagePath")
            }
            val res = vl!!.frontCameraInteractionPositive(request.text ?: "", imageBytes)
            mainHandler.removeCallbacks(timeoutRunnable)
            notifyAIAgentListeners(AgentResponse().apply {
                requestId = request.requestId
                sessionId = request.sessionId
                success = true
                text = res
                timestamp = System.currentTimeMillis()
            })
        } catch (e: Exception) {
            mainHandler.removeCallbacks(timeoutRunnable)
            Log.e(TAG, "handleImageRequest failed", e)
            notifyAIAgentListeners(AgentResponse().apply {
                requestId = request.requestId
                sessionId = request.sessionId
                success = false
                text = "系统: 请求失败 - ${e.message}"
                errorType = "EXCEPTION"
                timestamp = System.currentTimeMillis()
            })
        }
    }
}
```

- [ ] **Step 4: 实现 handleVoiceRequest**

将原始的 `processNagativeRequest` 逻辑改造为接收 `AgentRequest`，响应通过 `AgentResponse` 回调 + TTS。

```kotlin
private fun handleVoiceRequest(request: AgentRequest) {
    mNagativeReqExecuting.set(true)
    // 如果之前有 TTS 正在播放，先停掉
    stopTTS()

    val session = traceManager.startSession("chat", request.sessionId ?: "default_user", request.text ?: "")
    mWorkHandler?.post {
        try {
            Log.d(TAG, "handleVoiceRequest begin text=${request.text}")
            val ctx = mapOf("user_id" to (request.sessionId ?: "default_user")) + session.toTraceContext().toContextData()
            val result = chatOrchestrator.execute(request.text ?: "", ctx)
            session.setStatus(result.isSuccess, result.errorDetail())
            val res = if (result.isSuccess) result.output()
                      else "系统: 请求失败 - ${result.errorDetail() ?: "未知错误"}"
            notifyAIAgentListeners(AgentResponse().apply {
                requestId = request.requestId
                sessionId = request.sessionId
                success = result.isSuccess
                text = res
                errorType = if (!result.isSuccess) result.errorType()?.name else null
                timestamp = System.currentTimeMillis()
            })
            // VOICE 请求需要播报 TTS
            mNagativeTTSplaying = true
            mainHandler.post {
                stopTTS()
                mManager?.speak(res)
                mChating = false
                mNagativeReqExecuting.set(false)
            }
        } catch (e: Exception) {
            session.setStatus(false, e.message)
            notifyAIAgentListeners(AgentResponse().apply {
                requestId = request.requestId
                sessionId = request.sessionId
                success = false
                text = "系统: 请求失败 - ${e.message}"
                errorType = "EXCEPTION"
                timestamp = System.currentTimeMillis()
            })
            mNagativeTTSplaying = true
            mainHandler.post {
                stopTTS()
                mManager?.speak("系统: 请求失败")
                mChating = false
                mNagativeReqExecuting.set(false)
            }
        } finally {
            session.close()
        }
    }
}
```

- [ ] **Step 5: 实现 handleControlRequest**

```kotlin
private fun handleControlRequest(request: AgentRequest) {
    val command = request.text ?: ""
    Log.d(TAG, "handleControlRequest command=$command")
    when (command) {
        "StartListen", "@#%^StartListen" -> {
            mainHandler.post {
                mRequestAIStr = ""
                mChating = true
                stopTTS()
            }
        }
        "StopListen", "@#%^StopListen" -> {
            mainHandler.post {
                mChating = false
            }
        }
        "ClearChatMemory", "@#%^ClearChatMemory" -> {
            mainHandler.post {
                memoryOrchestrator.startNewSession(request.sessionId ?: "default_user")
                chatOrchestrator.cleanMemory()
            }
        }
        else -> Log.w(TAG, "Unknown control command: $command")
    }
}
```

- [ ] **Step 6: 更新 notifyAIAgentListeners 方法**

将原有方法改为接收 `AgentResponse`：

```kotlin
private fun notifyAIAgentListeners(response: AgentResponse) {
    mainHandler.post {
        for ((listener) in mIAIAgentAidlListeners) {
            try {
                listener.onAIResponse(response)
            } catch (e: RemoteException) {
                Log.e("TAG", "notify listener failed", e)
            }
        }
    }
}
```

- [ ] **Step 7: 删除不再需要的旧方法**

从 `AIAgentBinder` 中删除 `requestAI`, `sendMessage`, `sendMessageWithImage` 的 override。
删除 `processNagativeRequest` 私有方法（逻辑已合并到 handleVoiceRequest）。

- [ ] **Step 8: 添加 AgentRequest/AgentResponse 导入**

在文件顶部添加：
```kotlin
import com.hirain.aiagent.AgentRequest
import com.hirain.aiagent.AgentResponse
```

- [ ] **Step 9: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

---

## Task 5: 改造 AIAgent.java（客户端 API）

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/AIAgent.java`

**Consumes:** `AgentRequest`, `AgentResponse`

- [ ] **Step 1: 添加 processAgentRequest 方法，删除旧方法**

```java
public int processAgentRequest(AgentRequest request) {
    if (m_service == null) {
        Log.w(TAG, "processAgentRequest: service not connected");
        return -1;
    }
    try {
        m_service.processAgentRequest(request);
        return 0;
    } catch (RemoteException e) {
        Log.e(TAG, "processAgentRequest failed", e);
        return -1;
    }
}
```

删除 `requestAI()`, `sendMessage()`, `sendMessageWithImage()` 方法。

- [ ] **Step 2: 更新 AIAgentAidlCallback**

```java
private class AIAgentAidlCallback extends IAIAgentAidlListener.Stub {
    @Override
    public void onAIResponse(AgentResponse response) throws RemoteException {
        Log.d(TAG, "onAIResponse requestId=" + response.getRequestId()
                + " success=" + response.isSuccess());
        notifyAIResponse(response);
    }
}
```

- [ ] **Step 3: 更新 notifyAIResponse**

```java
private void notifyAIResponse(AgentResponse response) {
    for (IAIAgentServiceListener listener : mIAIAgentServiceListeners) {
        if (listener != null) {
            listener.onAIResponse(response);
        }
    }
}
```

- [ ] **Step 4: 添加导入**

```java
import com.hirain.aiagent.AgentRequest;
import com.hirain.aiagent.AgentResponse;
```

- [ ] **Step 5: 编译验证**

Run: `./gradlew :app:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL

---

## Task 6: 清理和完整编译

**Files:**
- Delete: `app/src/main/aidl/com/hirain/aiagent/AIAgentData.aidl`
- Delete: `app/src/main/java/com/hirain/aiagent/AIAgentData.java`（确认无其他引用后）

- [ ] **Step 1: 检查 AIAgentData 是否有其他引用**

Run: `grep -r "AIAgentData" --include="*.java" --include="*.kt" --include="*.aidl" app/src/main/`
Expected: 只有 build 目录有引用，说明可以安全删除

- [ ] **Step 2: 删除 AIAgentData.aidl 和 AIAgentData.java**

```bash
rm app/src/main/aidl/com/hirain/aiagent/AIAgentData.aidl
rm app/src/main/java/com/hirain/aiagent/AIAgentData.java
```

- [ ] **Step 3: 完整编译**

Run: `./gradlew :app:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL

---

## Verification

1. **编译验证**: `./gradlew :app:compileDebugJavaWithJavac` 通过

2. **接口一致性验证**: 确认 AIDL 接口只暴露 `processAgentRequest` 和 `registerListener`/`unregisterListener`

3. **功能场景验证**（需要在设备/模拟器上运行）:
   - TEXT: 发送 `inputType=TEXT` 请求，确认收到 `AgentResponse` 回调
   - IMAGE: 发送 `inputType=IMAGE` 请求带上 `imagePath`，确认 VL 模型返回结果
   - VOICE: 发送 `inputType=VOICE` 请求，确认 AI 回复后 TTS 播放
   - CONTROL: 分别发送 StartListen / StopListen / ClearChatMemory，确认状态正确切换
