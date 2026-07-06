# IntentRouter Introduction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 AgentRuntime 内引入轻量级 IntentRouter，为进入 Runtime 的文本请求生成粗粒度 `IntentResult`，只用于观测和后续策略准备，不改变当前 AgentLoop 执行路径。

**Architecture:** 新增 `intentrouter` 包中的基础类型、接口和关键词实现；`AgentRuntime.startSession(...)` 在创建 `RequestSession` 前调用 router，得到 `IntentResult` 并写入 `RequestSession`。本阶段不根据 intent 分流 executor、不调用 tool、不限制 LLM 工具范围；当前 Service 仍只把 `TEXT` 送入 Runtime，`VOICE` 的支持仅体现在 Runtime 能处理未来传入的 `sourceInputType=VOICE` 文本请求。

**Tech Stack:** Java、JUnit4、现有 `AgentRuntime` / `RequestSession` / `TraceContext` / `TraceSession`、无新依赖、无 LLM/NLP 模型。

---

## 0. 范围和默认决策

### 0.1 本阶段生效范围

- 对进入 `AgentRuntime` 的文本请求生效。
- 当前 `AIAgentService` 只把 `TEXT` 请求交给 Runtime，因此本阶段实际运行路径只覆盖 TEXT。
- `VOICE` 不迁移、不重构；未来如果 VOICE 文本部分进入 Runtime，IntentRouter 通过 `sourceInputType=VOICE` 自然生效。
- `IMAGE` 和 `CONTROL` 不进入 IntentRouter。

### 0.2 明确不做的事

- 不根据 `IntentResult` 分流到不同 executor。
- 不根据 `IntentResult` 直接调用 tool。
- 不改变 `AgentLoopOrchestrator` 的实际执行路径。
- 不改变 `PromptManager`、`MemoryOrchestrator`、`ToolRegistry`、`ToolDispatcher`、`VehicleStateMachine` 行为。
- 不限制 LLM 可见工具范围。
- 不接入 `ToolGroup`、`PolicyEngine`、`ContextOrchestrator`、`Eval`。

### 0.3 Intent 判断失败策略

- IntentRouter 判断错误不能影响请求执行。
- Router 异常必须被 Runtime 捕获，降级为 `IntentTag.UNKNOWN`。
- 无论 intent 是什么，最终仍按原链路进入 `chatExecutor.execute(...)`。

### 0.4 Trace 和日志策略

- 优先写入 Trace，而不是在 runtime 中引入 Android `Log` 依赖，避免 JVM 单元测试受到 `android.util.Log` 影响。
- 使用 `TraceContext.session().setAttribute(...)` 写入 root span。
- 建议字段：
  - `agent.intent.tag`
  - `agent.intent.confidence`
  - `agent.intent.matched_keywords`
  - `agent.intent.source_input_type`
  - `agent.intent.debug_reason`

---

## 1. 目标文件结构

### 1.1 新增生产代码

- `app/src/main/java/com/hirain/aiagent/intentrouter/IntentTag.java`  
  粗粒度意图枚举。

- `app/src/main/java/com/hirain/aiagent/intentrouter/IntentConfidence.java`  
  置信度枚举。

- `app/src/main/java/com/hirain/aiagent/intentrouter/IntentResult.java`  
  单次意图识别结果。

- `app/src/main/java/com/hirain/aiagent/intentrouter/IntentRouter.java`  
  轻量意图标签器接口。

- `app/src/main/java/com/hirain/aiagent/intentrouter/KeywordIntentRouter.java`  
  基于关键词和少量正则的默认实现。

### 1.2 修改生产代码

- `app/src/main/java/com/hirain/aiagent/runtime/RequestSession.java`  
  新增 `IntentResult intentResult` 字段和 getter。

- `app/src/main/java/com/hirain/aiagent/runtime/RequestSessionFactory.java`  
  `create(...)` 新增 `IntentResult` 参数，并写入 `RequestSession`。

- `app/src/main/java/com/hirain/aiagent/runtime/AgentRuntime.java`  
  增加 `IntentRouter` 成员；`startSession(...)` 中调用 router；写入 trace attributes；执行路径不变。

### 1.3 新增和修改测试

- `app/src/test/java/com/hirain/aiagent/intentrouter/KeywordIntentRouterTest.java`
- 修改 `app/src/test/java/com/hirain/aiagent/runtime/RequestSessionFactoryTest.java`
- 修改 `app/src/test/java/com/hirain/aiagent/runtime/AgentRuntimeTest.java`

### 1.4 阶段总结文档

- `docs/act_summary/intent-router-introduction-summary.md`

---

## 2. 核心设计

### 2.1 IntentTag

```java
package com.hirain.aiagent.intentrouter;

public enum IntentTag {
    CHAT,
    VEHICLE_AC,
    VEHICLE_WINDOW,
    VEHICLE_SEAT,
    VEHICLE_DOOR,
    VEHICLE_CHASSIS,
    VEHICLE_FRAGRANCE,
    VEHICLE_DMS,
    VISION_QA,
    WEATHER,
    UNKNOWN
}
```

### 2.2 IntentConfidence

```java
package com.hirain.aiagent.intentrouter;

public enum IntentConfidence {
    HIGH,
    MEDIUM,
    LOW,
    NONE
}
```

规则：

- `HIGH`：命中强特征关键词或同一意图命中多个关键词。
- `MEDIUM`：命中单个普通业务关键词。
- `LOW`：未命中业务关键词但文本非空，判为 `CHAT`。
- `NONE`：空文本、null 文本或 router 异常，判为 `UNKNOWN`。

### 2.3 IntentResult

字段必须包含：

```java
private final IntentTag intentTag;
private final IntentConfidence confidence;
private final List<String> matchedKeywords;
private final String normalizedText;
private final String sourceInputType;
private final String debugReason;
```

要求：

- `matchedKeywords` 使用不可变 List。
- 提供 `of(...)` 和 `unknown(...)` 工厂方法。
- getter 使用 JavaBean 风格或当前项目常用读取器风格均可，但测试和生产代码必须保持一致。
- `normalizedText` 只用于调试，不回写 `AgentRequest.text`。

### 2.4 IntentRouter

```java
package com.hirain.aiagent.intentrouter;

public interface IntentRouter {
    IntentResult route(String text, String sourceInputType);
}
```

接口不依赖 Android `Context`、LLM、模型、tool registry 或 memory。

### 2.5 KeywordIntentRouter

使用静态关键词表和简单正则。第一版推荐规则：

| IntentTag | 关键词示例 |
|-----------|------------|
| `VEHICLE_AC` | 空调、制冷、制热、风量、除雾、温度调到 |
| `VEHICLE_WINDOW` | 车窗、窗户、开窗、关窗、升窗、降窗 |
| `VEHICLE_SEAT` | 座椅、座位、靠背、腰托、座椅加热、座椅通风 |
| `VEHICLE_DOOR` | 车门、门锁、上锁、解锁、锁车、开门 |
| `VEHICLE_CHASSIS` | 底盘、悬架、驾驶模式、运动模式、舒适模式、越野 |
| `VEHICLE_FRAGRANCE` | 香氛、香味、香薰、空气清新 |
| `VEHICLE_DMS` | 疲劳、分心、驾驶员监测、DMS |
| `VISION_QA` | 看到、看见、前方、摄像头、画面、图片 |
| `WEATHER` | 天气、下雨、下雪、气温、湿度、预报 |

匹配策略：

- 先 normalize：`trim()`、转小写、中文不做分词。
- 第一版同时支持 `contains()` 关键词表和少量 `Pattern` 正则规则；字符串关键词用于主要匹配，正则仅用于“温度.*调到”“调到.*度”等 contains 难以覆盖的表达。
- 对每个 tag 统计命中关键词和命中正则。
- 命中多个 tag 时，选择命中数量最高的 tag。
- 命中数量相同则使用固定优先级，避免 HashMap 顺序导致结果不稳定。
- 空文本返回 `UNKNOWN/NONE`。
- 非空但无业务关键词返回 `CHAT/LOW`。
- `debugReason` 仅允许以下值：
  - `matched:<tag>`：单一最高分命中。
  - `matched:<tag>:priority`：多个 tag 命中数相同，通过固定优先级选出 winner。
  - `fallback_chat`：文本非空但无业务关键词，返回 `CHAT/LOW`。
  - `empty_text`：输入为 `null`、空字符串或 trim 后为空，返回 `UNKNOWN/NONE`。
  - `router_exception`：Runtime 捕获到 router 异常，返回 `UNKNOWN/NONE`。

---

## 3. 实施任务

### Task 1: 新增 Intent 基础类型

**Files:**
- Create: `app/src/main/java/com/hirain/aiagent/intentrouter/IntentTag.java`
- Create: `app/src/main/java/com/hirain/aiagent/intentrouter/IntentConfidence.java`
- Create: `app/src/main/java/com/hirain/aiagent/intentrouter/IntentResult.java`

- [ ] **Step 1: 创建 IntentTag**

使用 2.1 中完整枚举，确保包含 `CHAT`、所有车辆子类、`VISION_QA`、`WEATHER`、`UNKNOWN`。

- [ ] **Step 2: 创建 IntentConfidence**

使用 2.2 中完整枚举。

- [ ] **Step 3: 创建 IntentResult**

实现字段、不可变 `matchedKeywords`、`of(...)` 和 `unknown(...)`。

关键代码：

```java
public static IntentResult unknown(String normalizedText, String sourceInputType, String debugReason) {
    return new IntentResult(IntentTag.UNKNOWN, IntentConfidence.NONE,
            List.of(), normalizedText, sourceInputType, debugReason);
}
```

- [ ] **Step 4: 编译检查**

Run:

```powershell
.\gradlew.bat compileDebugJavaWithJavac
```

Expected: `BUILD SUCCESSFUL`。

### Task 2: 新增 IntentRouter 和 KeywordIntentRouter

**Files:**
- Create: `app/src/main/java/com/hirain/aiagent/intentrouter/IntentRouter.java`
- Create: `app/src/main/java/com/hirain/aiagent/intentrouter/KeywordIntentRouter.java`
- Test: `app/src/test/java/com/hirain/aiagent/intentrouter/KeywordIntentRouterTest.java`

- [ ] **Step 1: 写失败测试**

测试文件包含以下用例：

```java
@Test
public void route_detectsVehicleAc() {
    IntentResult result = new KeywordIntentRouter().route("把空调温度调到二十二度", "TEXT");
    assertEquals(IntentTag.VEHICLE_AC, result.intentTag());
    assertTrue(result.matchedKeywords().contains("空调"));
    assertEquals("TEXT", result.sourceInputType());
}

@Test
public void route_detectsVehicleWindow() {
    IntentResult result = new KeywordIntentRouter().route("帮我打开车窗", "TEXT");
    assertEquals(IntentTag.VEHICLE_WINDOW, result.intentTag());
}

@Test
public void route_detectsWeather() {
    IntentResult result = new KeywordIntentRouter().route("今天北京天气怎么样", "TEXT");
    assertEquals(IntentTag.WEATHER, result.intentTag());
}

@Test
public void route_detectsAllFirstVersionVehicleTags() {
    KeywordIntentRouter router = new KeywordIntentRouter();

    assertEquals(IntentTag.VEHICLE_SEAT, router.route("打开座椅加热", "TEXT").intentTag());
    assertEquals(IntentTag.VEHICLE_DOOR, router.route("帮我锁车门", "TEXT").intentTag());
    assertEquals(IntentTag.VEHICLE_CHASSIS, router.route("切换到运动模式", "TEXT").intentTag());
    assertEquals(IntentTag.VEHICLE_FRAGRANCE, router.route("打开香氛", "TEXT").intentTag());
    assertEquals(IntentTag.VEHICLE_DMS, router.route("驾驶员是不是疲劳了", "TEXT").intentTag());
    assertEquals(IntentTag.VISION_QA, router.route("前方摄像头看到什么", "TEXT").intentTag());
}

@Test
public void route_fallsBackToChatForNormalText() {
    IntentResult result = new KeywordIntentRouter().route("你好，给我讲个笑话", "TEXT");
    assertEquals(IntentTag.CHAT, result.intentTag());
    assertEquals(IntentConfidence.LOW, result.confidence());
}

@Test
public void route_returnsUnknownForBlankText() {
    IntentResult result = new KeywordIntentRouter().route("   ", "TEXT");
    assertEquals(IntentTag.UNKNOWN, result.intentTag());
    assertEquals(IntentConfidence.NONE, result.confidence());
}

@Test
public void route_returnsUnknownForNullText() {
    IntentResult result = new KeywordIntentRouter().route(null, "TEXT");
    assertEquals(IntentTag.UNKNOWN, result.intentTag());
    assertEquals(IntentConfidence.NONE, result.confidence());
    assertEquals("empty_text", result.debugReason());
}

@Test
public void route_marksPriorityTieInDebugReason() {
    IntentResult result = new KeywordIntentRouter().route("车窗看见了吗", "TEXT");

    assertEquals(IntentTag.VEHICLE_WINDOW, result.intentTag());
    assertEquals("matched:VEHICLE_WINDOW:priority", result.debugReason());
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.intentrouter.KeywordIntentRouterTest"
```

Expected: 编译失败，提示 `KeywordIntentRouter` 不存在。

- [ ] **Step 3: 实现 IntentRouter**

```java
package com.hirain.aiagent.intentrouter;

public interface IntentRouter {
    IntentResult route(String text, String sourceInputType);
}
```

- [ ] **Step 4: 实现 KeywordIntentRouter**

实现要求：

- 使用 `LinkedHashMap<IntentTag, List<String>>` 保存规则，保证优先级稳定。
- 使用 `LinkedHashMap<IntentTag, List<Pattern>>` 保存少量正则规则，例如 `温度.*调到|调到.*度` 归入 `VEHICLE_AC`。
- 每条字符串关键词用 `normalizedText.contains(keyword)` 判断。
- 每条正则规则用 `pattern.matcher(normalizedText).find()` 判断。
- 不调用 LLM。
- 不引用 tool registry。
- 不抛出业务异常；内部异常返回 `UNKNOWN/NONE`。

- [ ] **Step 5: 运行测试确认通过**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.intentrouter.KeywordIntentRouterTest"
```

Expected: `BUILD SUCCESSFUL`。

### Task 3: RequestSession 写入 IntentResult

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/runtime/RequestSession.java`
- Modify: `app/src/main/java/com/hirain/aiagent/runtime/RequestSessionFactory.java`
- Modify test: `app/src/test/java/com/hirain/aiagent/runtime/RequestSessionFactoryTest.java`

- [ ] **Step 1: 写失败测试**

在 `RequestSessionFactoryTest` 中新增：

```java
@Test
public void create_storesIntentResult() {
    AgentRequest request = new AgentRequest();
    request.setInputType("TEXT");
    request.setText("打开空调");
    IntentResult intentResult = IntentResult.of(IntentTag.VEHICLE_AC, IntentConfidence.HIGH,
            List.of("空调"), "打开空调", "TEXT", "matched:VEHICLE_AC");
    RequestSessionFactory factory = new RequestSessionFactory(() -> "req-fixed", () -> 1234L);

    RequestSession session = factory.create(request, "chat", null, intentResult);

    assertEquals(IntentTag.VEHICLE_AC, session.intentResult().intentTag());
}
```

- [ ] **Step 2: 修改 RequestSession**

新增：

```java
private final IntentResult intentResult;
public IntentResult intentResult() { return intentResult; }
```

构造函数增加 `IntentResult intentResult` 参数。

- [ ] **Step 3: 修改 RequestSessionFactory**

将签名改为：

```java
public RequestSession create(AgentRequest request, String personaId,
                             TraceContext traceContext, IntentResult intentResult)
```

传入 `RequestSession`。如果 `intentResult == null`，使用：

```java
IntentResult.unknown(nonEmpty(request.getText(), ""), nonEmpty(request.getInputType(), "TEXT"), "missing_intent_result")
```

- [ ] **Step 4: 更新旧测试调用点**

所有旧的 `factory.create(request, "chat", traceContext)` 改为传入明确 `IntentResult`。

当前直接调用点应至少包括：

- `RequestSessionFactoryTest.create_generatesRequestIdAndUsesDefaultUserWithoutCreatingSessionId()`
- `RequestSessionFactoryTest.create_preservesExistingRequestIdAndSessionId()`

`AgentRuntimeTest` 当前通过 `runtime.startSession(...)` 间接调用，不直接修改 `RequestSessionFactory` 调用；但 Task 4 修改 `AgentRuntime.startSession(...)` 后必须重新跑 `AgentRuntimeTest`。

- [ ] **Step 5: 运行 RequestSessionFactoryTest**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.runtime.RequestSessionFactoryTest"
```

Expected: `BUILD SUCCESSFUL`。

### Task 4: AgentRuntime 接入 IntentRouter

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/runtime/AgentRuntime.java`
- Modify test: `app/src/test/java/com/hirain/aiagent/runtime/AgentRuntimeTest.java`

- [ ] **Step 1: 写失败测试：Runtime 调用 router 但不影响 executor**

```java
@Test
public void startSession_routesIntentAndExecuteStillUsesOriginalExecutor() {
    IntentRouter router = (text, sourceInputType) ->
            IntentResult.of(IntentTag.VEHICLE_AC, IntentConfidence.HIGH,
                    List.of("空调"), text, sourceInputType, "matched:VEHICLE_AC");
    AgentRuntime runtime = new AgentRuntime(
            (userInput, ctx) -> AgentResult.success("完成", 1, 10L, List.of()),
            router,
            () -> "req-fixed",
            () -> 3000L);
    AgentRequest request = new AgentRequest();
    request.setInputType("TEXT");
    request.setText("打开空调");

    RequestSession session = runtime.startSession(request, null);
    RuntimeResult result = runtime.execute(session);

    assertEquals(IntentTag.VEHICLE_AC, session.intentResult().intentTag());
    assertTrue(result.success());
    assertEquals("完成", result.output());
}
```

- [ ] **Step 2: 写失败测试：router 异常不影响执行**

```java
@Test
public void startSession_routerExceptionFallsBackToUnknownAndExecuteContinues() {
    IntentRouter router = (text, sourceInputType) -> {
        throw new IllegalStateException("router failed");
    };
    AgentRuntime runtime = new AgentRuntime(
            (userInput, ctx) -> AgentResult.success("继续执行", 1, 10L, List.of()),
            router,
            () -> "req-fixed",
            () -> 3000L);

    RequestSession session = runtime.startSession(new AgentRequest(), null);
    RuntimeResult result = runtime.execute(session);

    assertEquals(IntentTag.UNKNOWN, session.intentResult().intentTag());
    assertTrue(result.success());
    assertEquals("继续执行", result.output());
}
```

- [ ] **Step 3: 修改 AgentRuntime 构造函数**

保留现有构造函数行为：

```java
public AgentRuntime(AgentExecutor chatExecutor) {
    this(chatExecutor, new KeywordIntentRouter(), new UuidIdGenerator(), new SystemTimeProvider());
}
```

保留当前测试构造能力：

```java
public AgentRuntime(AgentExecutor chatExecutor, IdGenerator idGenerator, TimeProvider timeProvider) {
    this(chatExecutor, new KeywordIntentRouter(), idGenerator, timeProvider);
}
```

新增可注入 router 的构造函数：

```java
public AgentRuntime(AgentExecutor chatExecutor, IntentRouter intentRouter,
                    IdGenerator idGenerator, TimeProvider timeProvider)
```

- [ ] **Step 4: 修改 startSession**

核心逻辑：

```java
IntentResult intentResult = routeIntentSafely(request);
writeIntentToTrace(traceContext, intentResult);
return sessionFactory.create(request, CHAT_PERSONA, traceContext, intentResult);
```

`routeIntentSafely(...)` 必须捕获异常，并返回 `UNKNOWN/NONE`。完整实现应按以下形状编写：

```java
private IntentResult routeIntentSafely(AgentRequest request) {
    String text = request != null && request.getText() != null ? request.getText() : "";
    String inputType = request != null && request.getInputType() != null
            ? request.getInputType()
            : "TEXT";
    try {
        IntentResult result = intentRouter.route(text, inputType);
        return result != null
                ? result
                : IntentResult.unknown(text.trim(), inputType, "router_exception");
    } catch (Exception e) {
        return IntentResult.unknown(text.trim(), inputType, "router_exception");
    }
}
```

说明：

- `request == null` 不是当前 Service 主路径，但 Runtime 边界层要防御。
- `request.getText() == null` 不视为 router 异常，进入 `KeywordIntentRouter` 后应按 `empty_text` 处理。
- 只有 router 抛异常或返回 null 时，`debugReason=router_exception`。

- [ ] **Step 5: 写入 Trace attributes**

实现：

```java
private void writeIntentToTrace(TraceContext traceContext, IntentResult intentResult) {
    if (traceContext == null || traceContext.session() == null || intentResult == null) return;
    traceContext.session().setAttribute("agent.intent.tag", intentResult.intentTag().name());
    traceContext.session().setAttribute("agent.intent.confidence", intentResult.confidence().name());
    traceContext.session().setAttribute("agent.intent.matched_keywords",
            String.join(",", intentResult.matchedKeywords()));
    traceContext.session().setAttribute("agent.intent.source_input_type", intentResult.sourceInputType());
    traceContext.session().setAttribute("agent.intent.debug_reason", intentResult.debugReason());
}
```

- [ ] **Step 6: 运行 AgentRuntimeTest**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.runtime.AgentRuntimeTest"
```

Expected: `BUILD SUCCESSFUL`。

### Task 5: 回归验证和不变性检查

**Files:**
- Existing tests only

- [ ] **Step 1: 运行 intentrouter 测试**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.intentrouter.*"
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 2: 运行 runtime 测试**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.runtime.*"
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 3: 运行 trace wiring 测试**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.trace.AIAgentServiceTraceWiringTest"
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 4: 运行全量 JVM 单测**

```powershell
.\gradlew.bat testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 5: 静态检查 Service 不被误改**

Run:

```powershell
git diff -- app/src/main/java/com/hirain/aiagent/AIAgentService.kt
```

Expected:

- 本阶段不需要修改 `AIAgentService.kt`。
- `handleVoiceRequest` 不迁移。
- `handleImageRequest` 不迁移。
- `handleControlRequest` 不迁移。

### Task 6: 阶段总结文档

**Files:**
- Create: `docs/act_summary/intent-router-introduction-summary.md`

- [ ] **Step 1: 写入总结**

必须包含：

- 新增 `IntentTag` / `IntentConfidence` / `IntentResult` / `IntentRouter` / `KeywordIntentRouter`。
- `RequestSession` 新增 `intentResult`。
- `AgentRuntime` 在 `startSession(...)` 中调用 router。
- intent 只写入 `RequestSession` 和 trace，不影响 executor。
- `VOICE/IMAGE/CONTROL` 未迁移。
- 实际执行过的测试命令和结果。

- [ ] **Step 2: 记录后续阶段**

后续建议：

1. `PolicyEngine` 根据 intent 设置 timeout 或安全策略。
2. `ToolGroup` 根据 intent 限制工具可见范围。
3. `ContextOrchestrator` 根据 intent 调整 context assembly。
4. `Eval` 建立 intent 标签回归集。

---

## 4. 验收标准

### 4.1 功能验收

- `KeywordIntentRouter` 能识别 `VEHICLE_AC`、`VEHICLE_WINDOW`、`VEHICLE_SEAT`、`VEHICLE_DOOR`、`VEHICLE_CHASSIS`、`VEHICLE_FRAGRANCE`、`VEHICLE_DMS`、`VISION_QA`、`WEATHER`。
- 空文本返回 `UNKNOWN/NONE`。
- 普通非业务文本返回 `CHAT/LOW`。
- `IntentResult` 包含 `intentTag`、`confidence`、`matchedKeywords`、`normalizedText`、`sourceInputType`、`debugReason`。
- `AgentRuntime.startSession(...)` 将 `IntentResult` 写入 `RequestSession`。
- intent 信息写入 trace attribute。
- Router 异常不会阻断 `AgentRuntime.execute(...)`。

### 4.2 非功能验收

- 不引入 LLM/NLP 模型。
- 不新增外部依赖。
- 不改变 `AgentLoopOrchestrator` 执行路径。
- 不改变 prompt、memory、tool、vehicle state 行为。
- 不限制 LLM 可见工具范围。
- TEXT 用户可见输出与迁移前基本一致。

### 4.3 手动验收建议

在现有 `docs/check_accept/agent-runtime-manual-acceptance-checklist.md` 基础上追加观察项：

- TEXT “打开空调” trace 中 `agent.intent.tag=VEHICLE_AC`。
- TEXT “今天北京天气怎么样” trace 中 `agent.intent.tag=WEATHER`。
- TEXT 普通聊天 trace 中 `agent.intent.tag=CHAT`。
- 即使 intent 判断不准确，用户可见回复仍由原 AgentLoop 生成。

---

## 5. 已明确的设计假设

- 本阶段不修改 `AIAgentService.handleVoiceRequest(...)`，因此当前真实 VOICE 请求不会经过 IntentRouter。
- Runtime 对 `sourceInputType=VOICE` 的支持是为了未来 VOICE 文本链路复用，不代表本阶段迁移 VOICE。
- IntentResult 不写入 `orchestratorContext`，避免影响 prompt、memory 或后续 AgentLoop 行为。
- Trace attribute 是本阶段主要调试出口；不在 runtime 中使用 Android `Log`。
