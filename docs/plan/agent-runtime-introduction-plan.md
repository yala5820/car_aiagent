# AgentRuntime Introduction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 `com.hirain.aiagent.runtime.AgentRuntime`，让 `AIAgentService` 不再直接负责 `TEXT` 请求的 Agent 主流程，同时不改变现有 `AgentLoopOrchestrator`、Prompt、Memory、Tool、VehicleStateMachine 行为。

**Architecture:** Phase 1 只迁移 `TEXT` 主链路；`AIAgentService` 继续负责 AIDL、Service 生命周期、listener 回调、timeout 调度、`TraceSession` 创建、`TraceResponseDispatcher` 派发、`IMAGE/CONTROL` 原逻辑和 `VOICE` ASR/TTS 包装。`AgentRuntime` 位于 `AIAgentService` 与 `AgentLoopOrchestrator` 之间，接收 `AgentRequest` 和 Service 创建的 `TraceContext`，创建 `RequestSession`，规范化 requestId/userId/sessionId，调用现有 orchestrator，捕获异常并返回 `RuntimeResult`；最终由 `RuntimeResponseMapper` 转成 `AgentResponse`。

**Tech Stack:** Java runtime 模块、Kotlin `AIAgentService` 集成、现有 `AgentRequest`/`AgentResponse` Parcelable、现有 `AgentLoopOrchestrator`、现有 `TraceManager`/`TraceSession`/`TraceContext`/`TraceResponseDispatcher`、JUnit4 JVM 单元测试。

---

## 0. 已确认边界

### 0.1 Phase 1 只迁移 TEXT

- 只迁移 `AIAgentService.handleTextRequest(request)` 中的 Agent 对话主链路。
- `VOICE` 本阶段不迁移；语音 ASR/TTS 包装和当前对话调用继续保留在 `AIAgentService.handleVoiceRequest(request)`。
- `IMAGE` 不迁移，继续保留在 `AIAgentService.handleImageRequest(request)`，行为必须完全不变。
- `CONTROL` 不迁移，继续保留在 `AIAgentService.handleControlRequest(request)`，行为必须完全不变。
- 未来如果要迁移 `VOICE`，只迁移“语音转文本后的 Agent 对话部分”，TTS 状态机仍应留在 Service 或后续 Policy 层中规划。

### 0.2 Service 与 Runtime 分工

`AIAgentService` 继续负责：AIDL Binder、Service 生命周期、listener 回调、`mWorkHandler`/`mainHandler` 调度、TEXT timeout runnable、`TraceSession` 创建与关闭、`TraceResponseDispatcher` 派发、`VOICE/IMAGE/CONTROL` 原逻辑。

`AgentRuntime` 负责：接收 `AgentRequest`、创建 `RequestSession`、规范化 requestId/userId/sessionId、接收 `TraceContext` 并写入 orchestrator context、调用现有 `AgentLoopOrchestrator`、捕获异常、返回统一 `RuntimeResult`、提供 timeout/error result 封装。

### 0.3 本阶段禁止改动

不得改变 `ToolRegistry`、`ToolDispatcher`、`PromptManager`、`MemoryOrchestrator`、`VehicleStateMachine`、`AgentLoopOrchestrator` 主循环行为、AIDL 接口、`AgentRequest`/`AgentResponse` 字段结构、`VOICE/IMAGE/CONTROL` 行为。本阶段不接入 `IntentRouter`、`ToolGroup`、`ContextOrchestrator`、`PolicyEngine`、`Eval`。

### 0.4 ID、session 与 trace 策略

- `requestId`：已有则保留；缺失则由 `IdGenerator` 生成 `req-` 前缀 UUID。
- `sessionId`：已有则保留；缺失时 `RequestSession.sessionId()` 保持 `null`，不创建新的业务会话策略。
- `userId`：已有 `sessionId` 时使用该值；缺失时继续使用 `default_user`，与当前 TEXT context 行为一致。
- `AgentResponse.sessionId`：由 `RuntimeResult.sessionId()` 原样映射；请求缺失 sessionId 时响应继续缺失。
- `TraceSession` 创建、`makeCurrent()`、关闭都保留在 Service；Runtime 只接收 `TraceContext`。
- `TraceResponseDispatcher` 保留在 Service，Runtime 不负责 listener dispatch。

---

## 1. 文件结构

### 1.1 新增生产代码

- `app/src/main/java/com/hirain/aiagent/runtime/AgentExecutor.java`：`AgentRuntime` 调用 `AgentLoopOrchestrator` 的最小抽象。
- `app/src/main/java/com/hirain/aiagent/runtime/IdGenerator.java`：requestId 生成接口。
- `app/src/main/java/com/hirain/aiagent/runtime/UuidIdGenerator.java`：UUID requestId 默认实现。
- `app/src/main/java/com/hirain/aiagent/runtime/TimeProvider.java`：时间来源接口。
- `app/src/main/java/com/hirain/aiagent/runtime/SystemTimeProvider.java`：系统时间默认实现。
- `app/src/main/java/com/hirain/aiagent/runtime/RequestSession.java`：单次请求运行时快照。
- `app/src/main/java/com/hirain/aiagent/runtime/RequestSessionFactory.java`：从 `AgentRequest` 和 `TraceContext` 创建 `RequestSession`。
- `app/src/main/java/com/hirain/aiagent/runtime/RuntimeResult.java`：Runtime 层统一结果。
- `app/src/main/java/com/hirain/aiagent/runtime/RuntimeResponseMapper.java`：将 `RuntimeResult` 转换为 `AgentResponse`。
- `app/src/main/java/com/hirain/aiagent/runtime/AgentRuntime.java`：Runtime 主入口。

### 1.2 修改生产代码

- `app/src/main/java/com/hirain/aiagent/AIAgentService.kt`
  - 新增 `agentRuntime` 与 `runtimeResponseMapper` 字段。
  - 在 `onCreate()` 中初始化 runtime 与 mapper。
  - 仅改造 `handleTextRequest(request)`。
  - 不改造 `handleVoiceRequest(request)`、`handleImageRequest(request)`、`handleControlRequest(request)`。

### 1.3 新增测试代码

- `app/src/test/java/com/hirain/aiagent/runtime/RequestSessionFactoryTest.java`
- `app/src/test/java/com/hirain/aiagent/runtime/RuntimeResultTest.java`
- `app/src/test/java/com/hirain/aiagent/runtime/RuntimeResponseMapperTest.java`
- `app/src/test/java/com/hirain/aiagent/runtime/AgentRuntimeTest.java`

---

## 2. 核心类型设计

### 2.1 AgentExecutor

```java
package com.hirain.aiagent.runtime;

import com.hirain.aiagent.core.AgentResult;
import java.util.Map;

/**
 * AgentRuntime 调用 AgentLoopOrchestrator 的最小抽象。
 * 设计原因：runtime 单元测试不应依赖真实 Android Context、SQLite ChatMemory 或真实 LLM。
 */
@FunctionalInterface
public interface AgentExecutor {
    AgentResult execute(String userInput, Map<String, Object> context);
}
```

### 2.2 RequestSession

字段：`AgentRequest request`、`String requestId`、`String sessionId`、`String userId`、`String sourceApp`、`String inputType`、`String personaId`、`String userInput`、`long startedAtMs`、`TraceContext traceContext`、`Map<String,Object> orchestratorContext`。

约束：不持有 `TraceSession`、不持有 `TraceResponseDispatcher`、不负责关闭 trace；`orchestratorContext` 必须包含 `user_id`；传入 `TraceContext` 时必须包含 `TraceContext.TRACE_CONTEXT_KEY`；`extraContext` 非空时复制进去。

### 2.3 RuntimeResult

字段：`requestId`、`sessionId`、`success`、`output`、`errorType`、`errorDetail`、`timestampMs`、`iterationsUsed`、`durationMs`。

工厂方法：`success(...)`、`failure(...)`、`timeout(...)`、`fromAgentResult(...)`、`fromException(...)`。

### 2.4 RuntimeResponseMapper

映射规则：成功时 `text=output,errorType=null`；`TIMEOUT` 时 `text=系统: 请求超时`；`EXCEPTION` 时 `text=系统: 请求失败 - {errorDetail}`；其他失败时 `text=errorDetail ?: 请求失败`；`requestId/sessionId/timestamp` 从 `RuntimeResult` 原样映射。

### 2.5 AgentRuntime

对外方法：

```java
public RequestSession startSession(AgentRequest request, TraceContext traceContext)
public RuntimeResult execute(RequestSession session)
public RuntimeResult timeoutResult(RequestSession session)
public RuntimeResult errorResult(RequestSession session, Exception exception)
```

约束：不创建 trace；不调度 timeout；不通知 listener；`execute(...)` 捕获 executor 异常并返回 `RuntimeResult`。

---

## 3. 实施任务

### Task 1: 新增 runtime 基础接口

**Files:**
- Create: `app/src/main/java/com/hirain/aiagent/runtime/AgentExecutor.java`
- Create: `app/src/main/java/com/hirain/aiagent/runtime/IdGenerator.java`
- Create: `app/src/main/java/com/hirain/aiagent/runtime/UuidIdGenerator.java`
- Create: `app/src/main/java/com/hirain/aiagent/runtime/TimeProvider.java`
- Create: `app/src/main/java/com/hirain/aiagent/runtime/SystemTimeProvider.java`

- [ ] **Step 1: 新增 AgentExecutor**

使用 2.1 中的完整代码。

- [ ] **Step 2: 新增 IdGenerator**

```java
package com.hirain.aiagent.runtime;

/**
 * requestId 生成接口。
 * 设计原因：生产环境使用 UUID，单元测试使用固定值验证映射行为。
 */
public interface IdGenerator {
    String newRequestId();
}
```

- [ ] **Step 3: 新增 UuidIdGenerator**

```java
package com.hirain.aiagent.runtime;

import java.util.UUID;

public final class UuidIdGenerator implements IdGenerator {
    @Override
    public String newRequestId() {
        return "req-" + UUID.randomUUID();
    }
}
```

- [ ] **Step 4: 新增 TimeProvider 与 SystemTimeProvider**

```java
package com.hirain.aiagent.runtime;

/**
 * 时间来源接口。
 * 设计原因：RuntimeResult.timestampMs 需要在单元测试中可预测。
 */
public interface TimeProvider {
    long nowMillis();
}
```

```java
package com.hirain.aiagent.runtime;

public final class SystemTimeProvider implements TimeProvider {
    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
```

- [ ] **Step 5: 编译检查**

Run:

```powershell
.\gradlew.bat compileDebugJavaWithJavac
```

Expected: `BUILD SUCCESSFUL`。

### Task 2: 创建 RequestSession 和 RequestSessionFactory

**Files:**
- Create: `app/src/main/java/com/hirain/aiagent/runtime/RequestSession.java`
- Create: `app/src/main/java/com/hirain/aiagent/runtime/RequestSessionFactory.java`
- Test: `app/src/test/java/com/hirain/aiagent/runtime/RequestSessionFactoryTest.java`

- [ ] **Step 1: 写失败测试**

测试必须覆盖：

```java
@Test
public void create_generatesRequestIdAndUsesDefaultUserWithoutCreatingSessionId() {
    AgentRequest request = new AgentRequest();
    request.setInputType("TEXT");
    request.setText("打开空调");
    TraceContext traceContext = new TraceContext("trace-1", "span-1", null);
    RequestSessionFactory factory = new RequestSessionFactory(() -> "req-fixed", () -> 1234L);

    RequestSession session = factory.create(request, "chat", traceContext);

    assertEquals("req-fixed", session.requestId());
    assertNull(session.sessionId());
    assertEquals("default_user", session.userId());
    assertEquals("TEXT", session.inputType());
    assertEquals("chat", session.personaId());
    assertEquals("打开空调", session.userInput());
    assertEquals(1234L, session.startedAtMs());
    assertSame(traceContext, session.traceContext());
    assertEquals("default_user", session.orchestratorContext().get("user_id"));
    assertSame(traceContext, session.orchestratorContext().get(TraceContext.TRACE_CONTEXT_KEY));
}
```

并覆盖已有 `requestId/sessionId` 时保留原值：

```java
@Test
public void create_preservesExistingRequestIdAndSessionId() {
    AgentRequest request = new AgentRequest();
    request.setRequestId("req-existing");
    request.setSessionId("session-123");
    request.setInputType("TEXT");
    request.setSourceApp("launcher");
    request.setText("打开车窗");
    RequestSessionFactory factory = new RequestSessionFactory(() -> "req-fixed", () -> 1234L);

    RequestSession session = factory.create(request, "chat", null);

    assertEquals("req-existing", session.requestId());
    assertEquals("session-123", session.sessionId());
    assertEquals("session-123", session.userId());
    assertEquals("launcher", session.sourceApp());
    assertEquals("打开车窗", session.userInput());
    assertEquals("session-123", session.orchestratorContext().get("user_id"));
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.runtime.RequestSessionFactoryTest"
```

Expected: 编译失败，提示 `RequestSessionFactory` 或 `RequestSession` 不存在。

- [ ] **Step 3: 实现 RequestSession**

实现 2.2 中所有字段和 getter；构造函数包内可见；`orchestratorContext` 使用 `Collections.unmodifiableMap(new HashMap<>(orchestratorContext))` 防止外部修改。

- [ ] **Step 4: 实现 RequestSessionFactory**

关键逻辑：

```java
String requestId = nonEmpty(request.getRequestId(), idGenerator.newRequestId());
String sessionId = emptyToNull(request.getSessionId());
String userId = nonEmpty(sessionId, "default_user");
String sourceApp = nonEmpty(request.getSourceApp(), "unknown");
String inputType = nonEmpty(request.getInputType(), "TEXT");
String normalizedPersonaId = nonEmpty(personaId, "chat");
String userInput = nonEmpty(request.getText(), "");
Map<String, Object> context = new HashMap<>();
context.put("user_id", userId);
if (request.getExtraContext() != null) context.putAll(request.getExtraContext());
if (traceContext != null) context.putAll(traceContext.toContextData());
```

- [ ] **Step 5: 运行测试确认通过**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.runtime.RequestSessionFactoryTest"
```

Expected: `BUILD SUCCESSFUL`。

### Task 3: 创建 RuntimeResult

**Files:**
- Create: `app/src/main/java/com/hirain/aiagent/runtime/RuntimeResult.java`
- Test: `app/src/test/java/com/hirain/aiagent/runtime/RuntimeResultTest.java`

- [ ] **Step 1: 写失败测试**

测试覆盖：`fromAgentResult` 成功映射、`fromAgentResult` 错误映射、`fromException` 映射 `EXCEPTION`、`timeout` 映射 `TIMEOUT`。

核心断言：

```java
RuntimeResult success = RuntimeResult.fromAgentResult(
        "req-1", "session-1", AgentResult.success("好的", 2, 30L, List.of()), 2000L);
assertTrue(success.success());
assertEquals("好的", success.output());
assertEquals(2, success.iterationsUsed());
assertEquals(30L, success.durationMs());
assertEquals(2000L, success.timestampMs());

RuntimeResult failure = RuntimeResult.fromAgentResult(
        "req-1", null, AgentResult.error(AgentResult.ErrorType.MODEL_CALL_FAILED, "模型失败"), 2000L);
assertEquals(false, failure.success());
assertEquals("MODEL_CALL_FAILED", failure.errorType());
assertEquals("模型失败", failure.errorDetail());
assertNull(failure.sessionId());

RuntimeResult exception = RuntimeResult.fromException(
        "req-1", "session-1", new IllegalStateException("boom"), 2000L);
assertEquals("EXCEPTION", exception.errorType());
assertEquals("boom", exception.errorDetail());

RuntimeResult timeout = RuntimeResult.timeout("req-1", "session-1", 2000L);
assertEquals("TIMEOUT", timeout.errorType());
assertEquals("请求超时", timeout.errorDetail());
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.runtime.RuntimeResultTest"
```

Expected: 编译失败，提示 `RuntimeResult` 不存在。

- [ ] **Step 3: 实现 RuntimeResult**

实现 2.3 中字段、getter 和工厂方法；`AgentResult.errorType()` 为空时使用 `UNKNOWN`；异常 message 为空时使用 `未知错误`。

- [ ] **Step 4: 运行测试确认通过**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.runtime.RuntimeResultTest"
```

Expected: `BUILD SUCCESSFUL`。

### Task 4: 创建 RuntimeResponseMapper

**Files:**
- Create: `app/src/main/java/com/hirain/aiagent/runtime/RuntimeResponseMapper.java`
- Test: `app/src/test/java/com/hirain/aiagent/runtime/RuntimeResponseMapperTest.java`

- [ ] **Step 1: 写失败测试**

测试覆盖成功、异常、超时三类映射：

```java
RuntimeResult success = RuntimeResult.success("req-1", "session-1", "好的", 2000L, 1, 10L);
AgentResponse successResponse = new RuntimeResponseMapper().toAgentResponse(success);
assertEquals("req-1", successResponse.getRequestId());
assertEquals("session-1", successResponse.getSessionId());
assertTrue(successResponse.isSuccess());
assertEquals("好的", successResponse.getText());
assertNull(successResponse.getErrorType());
assertEquals(2000L, successResponse.getTimestamp());

RuntimeResult exception = RuntimeResult.failure("req-1", null, "EXCEPTION", "boom", 2000L);
AgentResponse exceptionResponse = new RuntimeResponseMapper().toAgentResponse(exception);
assertEquals(false, exceptionResponse.isSuccess());
assertNull(exceptionResponse.getSessionId());
assertEquals("系统: 请求失败 - boom", exceptionResponse.getText());
assertEquals("EXCEPTION", exceptionResponse.getErrorType());

RuntimeResult timeout = RuntimeResult.timeout("req-1", "session-1", 2000L);
AgentResponse timeoutResponse = new RuntimeResponseMapper().toAgentResponse(timeout);
assertEquals("系统: 请求超时", timeoutResponse.getText());
assertEquals("TIMEOUT", timeoutResponse.getErrorType());
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.runtime.RuntimeResponseMapperTest"
```

Expected: 编译失败，提示 `RuntimeResponseMapper` 不存在。

- [ ] **Step 3: 实现 RuntimeResponseMapper**

关键逻辑：

```java
if (result.success()) {
    response.setText(result.output());
    response.setErrorType(null);
} else if ("TIMEOUT".equals(result.errorType())) {
    response.setText("系统: 请求超时");
    response.setErrorType("TIMEOUT");
} else if ("EXCEPTION".equals(result.errorType())) {
    response.setText("系统: 请求失败 - " + (result.errorDetail() != null ? result.errorDetail() : "未知错误"));
    response.setErrorType("EXCEPTION");
} else {
    response.setText(result.errorDetail() != null ? result.errorDetail() : "请求失败");
    response.setErrorType(result.errorType());
}
```

- [ ] **Step 4: 运行测试确认通过**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.runtime.RuntimeResponseMapperTest"
```

Expected: `BUILD SUCCESSFUL`。

### Task 5: 创建 AgentRuntime 主入口

**Files:**
- Create: `app/src/main/java/com/hirain/aiagent/runtime/AgentRuntime.java`
- Test: `app/src/test/java/com/hirain/aiagent/runtime/AgentRuntimeTest.java`

- [ ] **Step 1: 写失败测试**

测试覆盖：`execute` 调用 executor、上下文包含 `user_id` 和 trace context、executor 异常映射为 `EXCEPTION`、`timeoutResult` 使用 `RequestSession` 的 requestId/sessionId。

核心断言：

```java
AtomicReference<String> input = new AtomicReference<>();
AtomicReference<Map<String, Object>> context = new AtomicReference<>();
AgentExecutor executor = (userInput, ctx) -> {
    input.set(userInput);
    context.set(ctx);
    return AgentResult.success("完成", 1, 10L, List.of());
};
AgentRuntime runtime = new AgentRuntime(executor, () -> "req-fixed", () -> 3000L);
AgentRequest request = new AgentRequest();
request.setInputType("TEXT");
request.setText("打开空调");
TraceContext traceContext = new TraceContext("trace-1", "span-1", null);

RequestSession session = runtime.startSession(request, traceContext);
RuntimeResult result = runtime.execute(session);

assertTrue(result.success());
assertEquals("完成", result.output());
assertEquals("打开空调", input.get());
assertEquals("default_user", context.get().get("user_id"));
assertSame(traceContext, context.get().get(TraceContext.TRACE_CONTEXT_KEY));
assertEquals("req-fixed", result.requestId());
assertEquals(3000L, result.timestampMs());
```

异常和 timeout 测试断言：

```java
RuntimeResult exception = runtime.execute(runtime.startSession(request, null));
assertEquals("EXCEPTION", exception.errorType());

RuntimeResult timeout = runtime.timeoutResult(runtime.startSession(new AgentRequest(), null));
assertEquals("req-fixed", timeout.requestId());
assertNull(timeout.sessionId());
assertEquals("TIMEOUT", timeout.errorType());
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.runtime.AgentRuntimeTest"
```

Expected: 编译失败，提示 `AgentRuntime` 不存在。

- [ ] **Step 3: 实现 AgentRuntime**

关键逻辑：

```java
private static final String CHAT_PERSONA = "chat";

public RequestSession startSession(AgentRequest request, TraceContext traceContext) {
    return sessionFactory.create(request, CHAT_PERSONA, traceContext);
}

public RuntimeResult execute(RequestSession session) {
    try {
        AgentResult result = chatExecutor.execute(session.userInput(), session.orchestratorContext());
        return RuntimeResult.fromAgentResult(session.requestId(), session.sessionId(), result, timeProvider.nowMillis());
    } catch (Exception e) {
        return RuntimeResult.fromException(session.requestId(), session.sessionId(), e, timeProvider.nowMillis());
    }
}
```

并实现 `timeoutResult(session)` 与 `errorResult(session, exception)`。

- [ ] **Step 4: 运行 runtime 全部测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.runtime.*"
```

Expected: `BUILD SUCCESSFUL`。

### Task 6: AIAgentService 初始化 AgentRuntime

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/AIAgentService.kt`

- [ ] **Step 1: 增加 import**

```kotlin
import com.hirain.aiagent.runtime.AgentExecutor
import com.hirain.aiagent.runtime.AgentRuntime
import com.hirain.aiagent.runtime.RuntimeResponseMapper
```

- [ ] **Step 2: 增加字段**

放在 `private lateinit var chatOrchestrator: AgentLoopOrchestrator` 附近：

```kotlin
private lateinit var agentRuntime: AgentRuntime
private lateinit var runtimeResponseMapper: RuntimeResponseMapper
```

- [ ] **Step 3: 在 chatOrchestrator 初始化后创建 runtime**

```kotlin
agentRuntime = AgentRuntime(
    AgentExecutor { userInput, context ->
        chatOrchestrator.execute(userInput, context)
    }
)
runtimeResponseMapper = RuntimeResponseMapper()
```

如果 Kotlin SAM 推断失败，改为匿名对象：

```kotlin
agentRuntime = AgentRuntime(
    object : AgentExecutor {
        override fun execute(userInput: String, context: MutableMap<String, Any>): AgentResult {
            return chatOrchestrator.execute(userInput, context)
        }
    }
)
runtimeResponseMapper = RuntimeResponseMapper()
```

- [ ] **Step 4: 编译检查**

Run:

```powershell
.\gradlew.bat compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`。如果任务名不存在，运行 `.\gradlew.bat compileDebugJavaWithJavac` 并记录实际结果。

### Task 7: 仅迁移 handleTextRequest 到 AgentRuntime

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/AIAgentService.kt`

- [ ] **Step 1: 替换 handleTextRequest，保留 Service 侧 TraceSession 和 timeout 调度**

目标结构：

```kotlin
private fun handleTextRequest(request: AgentRequest) {
    val message = request.text ?: ""
    val session = traceManager.startAgentRequest(
        "chat",
        request.sessionId ?: "default_user",
        request.requestId,
        request.sessionId,
        request.sourceApp,
        request.inputType,
        message
    )
    val responseDispatcher = TraceResponseDispatcher(session)
    val runtimeSession = agentRuntime.startSession(request, session.toTraceContext())

    val timeoutRunnable = Runnable {
        Log.e("TAG", "processAgentRequest TEXT timeout")
        val timeoutResponse = runtimeResponseMapper.toAgentResponse(agentRuntime.timeoutResult(runtimeSession))
        responseDispatcher.dispatchAndClose(timeoutResponse, "TIMEOUT") { response ->
            notifyAIAgentListeners(response)
        }
    }
    mainHandler.postDelayed(timeoutRunnable, SENDMESSAGE_TIMEOUT_MS)

    mWorkHandler?.post {
        val traceScope = session.makeCurrent()
        try {
            Log.d("TAG", "handleTextRequest begin")
            val runtimeResult = agentRuntime.execute(runtimeSession)
            val response = runtimeResponseMapper.toAgentResponse(runtimeResult)
            mainHandler.removeCallbacks(timeoutRunnable)
            responseDispatcher.dispatch(response, runtimeResult.errorDetail() ?: runtimeResult.errorType()) { sent ->
                notifyAIAgentListeners(sent)
            }
        } catch (e: Exception) {
            mainHandler.removeCallbacks(timeoutRunnable)
            Log.e("TAG", "handleTextRequest service-level failure", e)
            val runtimeResult = agentRuntime.errorResult(runtimeSession, e)
            val response = runtimeResponseMapper.toAgentResponse(runtimeResult)
            responseDispatcher.dispatch(response, runtimeResult.errorDetail() ?: runtimeResult.errorType()) { sent ->
                notifyAIAgentListeners(sent)
            }
        } finally {
            traceScope.close()
            session.close()
        }
    }
}
```

- [ ] **Step 2: 删除 TEXT 内直接 orchestrator 调用和手写 AgentResponse**

删除 `handleTextRequest` 内的：

```kotlin
val ctx = mapOf("user_id" to (request.sessionId ?: "default_user")) + session.toTraceContext().toContextData()
val result = chatOrchestrator.execute(message, ctx)
AgentResponse().apply { ... }
```

- [ ] **Step 3: 确认其它入口不变**

人工检查以下函数没有被修改：`handleVoiceRequest`、`handleImageRequest`、`handleControlRequest`、`ProcessCaptureGot`、`appendNagativeResponse`、`appendPositiveResponseToChat`。

### Task 8: 验证与回归

**Files:**
- Test: existing tests

- [ ] **Step 1: 运行 runtime 单元测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.runtime.*"
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 2: 运行 TraceResponseDispatcher 相关测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.trace.AIAgentServiceTraceWiringTest"
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 3: 运行 AgentLoop trace context 测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.core.AgentLoopOrchestratorTraceTest"
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 4: 运行全量 JVM 单测**

Run:

```powershell
.\gradlew.bat testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 5: 静态验收检查**

Run:

```powershell
Select-String -Path app\src\main\java\com\hirain\aiagent\AIAgentService.kt -Pattern "private fun handleTextRequest","chatOrchestrator.execute","handleVoiceRequest","handleImageRequest","handleControlRequest"
```

Expected:

- `handleTextRequest` 中不再出现 `chatOrchestrator.execute(...)`。
- `handleVoiceRequest` 中仍保留当前 `chatOrchestrator.execute(...)`，因为本阶段不迁移 VOICE。
- `handleImageRequest` 和 `handleControlRequest` 仍存在且未迁移。

- [ ] **Step 6: 手工回归验收**

使用 Launcher 或现有 AIDL 调用方验证：

- `TEXT` 请求返回内容与迁移前基本一致。
- `TEXT` timeout 仍然 15 秒。
- `TEXT` timeout 和正常响应仍然首响应获胜，不重复通知 listener。
- `VOICE` 文本对话结果与迁移前一致，因为本阶段未改。
- `IMAGE` 行为完全不变。
- `CONTROL` 行为完全不变。

### Task 9: 生成阶段总结文档

**Files:**
- Create: `docs/act_summary/agent-runtime-introduction-summary.md`

- [ ] **Step 1: 写入改动范围**

文档结构：

```markdown
# AgentRuntime 引入阶段总结

## 工作目标

本阶段新增 AgentRuntime，让 AIAgentService 的 TEXT Agent 主流程下沉到 runtime，同时保持 VOICE、IMAGE、CONTROL 原逻辑不变。

## 修改内容

- 新增 runtime 模块类：AgentExecutor、IdGenerator、UuidIdGenerator、TimeProvider、SystemTimeProvider、RequestSession、RequestSessionFactory、RuntimeResult、RuntimeResponseMapper、AgentRuntime。
- AIAgentService 初始化 AgentRuntime 和 RuntimeResponseMapper。
- handleTextRequest 保留 Service 侧 TraceSession、TraceResponseDispatcher 和 timeout 调度，但下沉 RequestSession 创建、orchestrator 调用、异常捕获和 RuntimeResult 封装。
- handleVoiceRequest、handleImageRequest、handleControlRequest 未迁移。

## 验证结果

- `.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.runtime.*"`：填写实际结果。
- `.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.trace.AIAgentServiceTraceWiringTest"`：填写实际结果。
- `.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.core.AgentLoopOrchestratorTraceTest"`：填写实际结果。
- `.\gradlew.bat testDebugUnitTest`：填写实际结果。

## 未迁移范围

- VOICE：本阶段不迁移，仍由 AIAgentService 管理 ASR/TTS 包装和对话调用。
- IMAGE：仍由 AIAgentService 调用 VlManager。
- CONTROL：仍由 AIAgentService 处理控制命令。
- IntentRouter、PolicyEngine、ContextOrchestrator、ToolGroup、Eval：本阶段不接入。

## 后续建议

1. Phase 2：抽取 VOICE 识别文本后的 Agent 对话部分，让其复用 AgentRuntime，但保留 TTS 包装在 Service。
2. Phase 3：引入 IntentRouter，把 inputType 和 intent 路由从 Service 中分离。
3. Phase 4：引入 ContextOrchestrator，把 RequestSession.orchestratorContext 升级为统一 context assembly。
4. Phase 5：引入 PolicyEngine 管理 timeout、并发、TTS 打断和场景触发抑制。
5. Phase 6：引入 Eval，沉淀 TEXT/VOICE/IMAGE/CONTROL 的回归用例。
```

- [ ] **Step 2: 写入实际命令结果**

不要写“应该通过”。只记录实际执行过的命令和真实结果。

---

## 4. 验收标准

### 4.1 功能验收

- `AgentRuntime.startSession(request, traceContext)` 能为缺失 requestId 的请求生成 requestId。
- `AgentRuntime.startSession(request, traceContext)` 在缺失 sessionId 时不创建新的业务 sessionId。
- `AgentRuntime.startSession(request, traceContext)` 在缺失 sessionId 时使用 `default_user` 作为 `userId` 和 orchestrator context 的 `user_id`。
- `RequestSession.orchestratorContext()` 包含 `user_id`。
- `RequestSession.orchestratorContext()` 在传入 `TraceContext` 时包含 `TraceContext.TRACE_CONTEXT_KEY`。
- `AgentRuntime.execute(session)` 能调用现有 `AgentLoopOrchestrator`。
- `AgentRuntime.execute(session)` 能把 `AgentResult.success` 映射为成功 `RuntimeResult`。
- `AgentRuntime.execute(session)` 能把 `AgentResult.error` 映射为失败 `RuntimeResult`。
- `AgentRuntime.execute(session)` 能捕获 executor 异常并返回 `errorType=EXCEPTION` 的 `RuntimeResult`。
- `RuntimeResponseMapper` 能把 `RuntimeResult` 转换为 `AgentResponse`。
- `AIAgentService.handleTextRequest()` 不再直接调用 `chatOrchestrator.execute(...)`。
- `AIAgentService.handleTextRequest()` 仍由 Service 创建 `TraceSession`。
- `AIAgentService.handleTextRequest()` 仍由 Service 调度 timeout。

### 4.2 非功能验收

- 不改变 AIDL 接口。
- 不改变 `AgentRequest` / `AgentResponse` 字段。
- 不改变 `AgentLoopOrchestrator` 主循环行为。
- 不改变 `ToolRegistry` / `ToolDispatcher` 行为。
- 不改变 `PromptManager` 行为。
- 不改变 `MemoryOrchestrator` 行为。
- 不改变 `VehicleStateMachine` 行为。
- `TEXT` timeout 首响应获胜逻辑继续成立。
- `VOICE` 行为保持现状。
- `IMAGE` 行为保持现状。
- `CONTROL` 行为保持现状。

### 4.3 测试验收

必须至少通过：

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.runtime.*"
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.trace.AIAgentServiceTraceWiringTest"
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.core.AgentLoopOrchestratorTraceTest"
```

推荐最终通过：

```powershell
.\gradlew.bat testDebugUnitTest
```

### 4.4 手工验收

- `TEXT` 文本对话迁移前后结果基本一致。
- `VOICE` 文本对话结果保持迁移前行为。
- `IMAGE` 行为完全不变。
- `CONTROL` 行为完全不变。
- `AIAgentService` 中 `TEXT` 主流程已由 `AgentRuntime` 接管。

---

## 5. 后续阶段建议

1. Phase 2：迁移 `VOICE` 中“ASR 文本 -> Agent 对话”的部分到 `AgentRuntime`，但 TTS 状态机继续留在 Service。
2. Phase 3：启用 `intentrouter`，统一 `TEXT/VOICE/IMAGE/CONTROL` 的请求意图分发。
3. Phase 4：启用 `context`，把 prompt/context/memory/vehicle status assembly 从 Runtime 中进一步拆出。
4. Phase 5：启用 `policy`，统一 timeout、并发控制、TTS 打断、场景触发抑制。
5. Phase 6：启用 `toolgroup`，沉淀工具分组和场景可用工具策略。
6. Phase 7：启用 `eval`，建立 AgentRuntime 级别回归评测。


