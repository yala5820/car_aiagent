# Context Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不破坏现有 AIDL、TEXT persona、会话、取消、记忆和工具调度行为的前提下，引入 `context` 模块，由 `RequestSession` 驱动生成 `ContextFrame`，统一承载本轮 TEXT Agent 的上下文元信息和可观测数据。

**Architecture:** 一期采用 `HYBRID_EXTRA_CONTEXT` 兼容模式：`AgentRuntime.startSession()` 仍负责 IntentRouter、ToolGroupSelector 和 RequestSession；`AgentRuntime.execute()` 在调用 AgentLoop 前通过 `ContextOrchestrator.build(session)` 生成 `ContextFrame`，并在 Context 构建后、AgentLoop 前执行可注入取消检查；旧 `AgentExecutor.execute(userInput, context)` 保留，新接口 `execute(session, contextFrame)` 作为默认适配层。`ContextFrame.renderedExtraContext` 只渲染 runtime、intent、toolgroup、selected tool 轻量说明和 debug hints，通过 `ContextExtraPreProcessor` 注入到首轮 `ChatRequest.messages()`，不重复注入 system prompt、persona prompt、memory、vehicle state、time context。

**Tech Stack:** Android Service, Java/Kotlin, LangChain4j 1.16.3, existing PromptManager, existing MemoryOrchestrator, existing ToolGroupRegistry, OpenTelemetry TraceSession, JUnit4, Gradle `testDebugUnitTest`.

---

## 0. 执行边界

本计划基于 `docs/plan/context_demo_plan.md` 制定，实施时必须保持以下边界：

- 不修改 AIDL 接口，不修改 `AgentRequest` / `AgentResponse` Parcelable 字段。
- 不修改 `ConversationManager` 的 create/list/switch/delete/getActive 行为。
- 不修改 `ActiveRequestRegistry` 的 CAS 终态抢占机制；Service 保留线程入口和返回后的取消检查，Runtime 增加只读取消检查器，确保 Context 构建后、AgentLoop 前仍可停止。
- 不修改 `IntentRouter` 关键词规则，不修改 `ToolGroupSelector` 映射规则。
- 不修改 `ToolRegistry` / `ToolDispatcher` 真实 dispatch 机制。
- 不修改 `VehicleStateMachine` 行为。
- 不引入 PolicyEngine、Task、Skill。
- 不重构 IMAGE / CONTROL 链路，不强制迁移 VOICE 旧链路。
- 不调用 LLM 构建 Context，不实现复杂 tokenizer，不调用 LLM 做 context 压缩。
- 不重复注入 system prompt、persona prompt、memory、vehicle state、time context。
- 不破坏 `chat` / `friendly` / `concise` 三种 TEXT persona 的选择与 ChatMemory 隔离。
- 不破坏 `userId + sessionId + personaId` 的短期记忆隔离。
- 不破坏 `cancelAgentRequest`、timeout、late result suppression。
- 一期 selected tool 口径沿用已确认的轻量方案：渲染 selected group 描述 + selected tool names，不渲染 LangChain4j `ToolSpecification.description` 和参数 schema；完整 tool specification 渲染进入后续阶段。
- 不自动提交 git；每个阶段完成后只汇报可提交文件清单，由用户决定是否提交。

### 0.1 与初始目标文档的口径说明

`docs/plan/context_demo_plan.md` 中的 “selected tool descriptions” 在本实施计划的一期中解释为“选中工具组描述 + 选中 toolName 列表”。这沿用已确认的轻量方案，目标是先解决全量工具上下文膨胀和 ContextFrame 可观测问题。若后续要把它升级为每个 `selectedToolName` 对应的真实 `@Tool` 描述和参数 schema，需要单独新增 `ToolSpecification` 只读查询能力，不混入本期最小实现。

## 1. 目标文件结构

### 1.1 新增生产文件

- `app/src/main/java/com/hirain/aiagent/context/ContextMode.java`  
  定义 `OBSERVE_ONLY`、`HYBRID_EXTRA_CONTEXT`、`FULL_CONTEXT`。

- `app/src/main/java/com/hirain/aiagent/context/ContextSectionType.java`  
  定义 `RUNTIME`、`PERSONA`、`USER_INPUT`、`INTENT`、`TOOL_GROUP`、`MEMORY`、`VEHICLE_STATE`、`TIME`、`PROMPT`、`DEBUG`。

- `app/src/main/java/com/hirain/aiagent/context/ContextSection.java`  
  单个上下文片段，包含类型、provider 名称、是否参与渲染、内容、字符数、是否截断和 metadata。

- `app/src/main/java/com/hirain/aiagent/context/ContextProvider.java`  
  provider 接口，输入 `RequestSession` 和 `ContextBuildInput`，输出 `ContextProviderResult`。

- `app/src/main/java/com/hirain/aiagent/context/ContextProviderResult.java`  
  provider 结果，区分 success/fallback/failure，并带诊断字段。

- `app/src/main/java/com/hirain/aiagent/context/ContextBuildInput.java`  
  构建期依赖容器，保存 `ContextMode`、`ToolGroupRegistry`、`PromptManager`、`MemoryOrchestrator`、`VehicleStatusProvider`、`TimeProvider`。

- `app/src/main/java/com/hirain/aiagent/context/VehicleStatusProvider.java`  
  context 包内独立车辆状态快照接口，避免 `context` 反向依赖 `core.preprocessor.VehicleStatusPreProcessor`。

- `app/src/main/java/com/hirain/aiagent/context/ContextBuildResult.java`  
  `ContextOrchestrator.build()` 的结果，包含 `ContextFrame`、fallback 标记、错误信息。

- `app/src/main/java/com/hirain/aiagent/context/ContextBuildException.java`  
  构建失败异常，携带 provider 名称和原因。

- `app/src/main/java/com/hirain/aiagent/context/ContextDebugInfo.java`  
  汇总 provider 状态、fallback、错误、build 耗时、截断信息。

- `app/src/main/java/com/hirain/aiagent/context/ContextFrame.java`  
  不可变上下文快照，字段来自 `RequestSession` 和各 provider。

- `app/src/main/java/com/hirain/aiagent/context/ContextFrameBuilder.java`  
  统一组装 `ContextFrame`，禁止重新生成 request/user/session/persona/clientMessageId。

- `app/src/main/java/com/hirain/aiagent/context/ContextBudgetManager.java`  
  粗粒度预算：section 字符数、memory 摘要字符数、selected tool 轻量说明字符数、总字符数估算。

- `app/src/main/java/com/hirain/aiagent/context/ContextTraceRecorder.java`  
  将 context 构建指标写入当前 `TraceSession` root span。

- `app/src/main/java/com/hirain/aiagent/context/ContextOrchestrator.java`  
  Context 构建总入口，按 provider 顺序构建 section、预算裁剪、渲染 extraContext、记录 trace。

- `app/src/main/java/com/hirain/aiagent/runtime/RuntimeCancelChecker.java`  
  Runtime 内部只读取消检查接口，用于 Context 构建后、AgentLoop 执行前的取消插入点。

- `app/src/main/java/com/hirain/aiagent/context/provider/RuntimeContextProvider.java`
- `app/src/main/java/com/hirain/aiagent/context/provider/PersonaContextProvider.java`
- `app/src/main/java/com/hirain/aiagent/context/provider/UserInputContextProvider.java`
- `app/src/main/java/com/hirain/aiagent/context/provider/IntentContextProvider.java`
- `app/src/main/java/com/hirain/aiagent/context/provider/ToolGroupContextProvider.java`
- `app/src/main/java/com/hirain/aiagent/context/provider/MemoryContextProvider.java`
- `app/src/main/java/com/hirain/aiagent/context/provider/VehicleStateContextProvider.java`
- `app/src/main/java/com/hirain/aiagent/context/provider/TimeContextProvider.java`
- `app/src/main/java/com/hirain/aiagent/context/provider/PromptContextProvider.java`

- `app/src/main/java/com/hirain/aiagent/core/preprocessor/ContextExtraPreProcessor.java`  
  从 `AgentLoopContext.contextData["context_rendered_extra"]` 读取 Context 文本，只在首轮生成一条临时 `UserMessage`。

### 1.2 修改生产文件

- `app/src/main/java/com/hirain/aiagent/runtime/AgentExecutor.java`  
  保留旧抽象方法，增加兼容默认方法 `execute(RequestSession, ContextFrame)`。

- `app/src/main/java/com/hirain/aiagent/runtime/AgentRuntime.java`  
  增加 `ContextOrchestrator` 依赖；`execute(session)` 在调用 executor 前构建 Context。

- `app/src/main/java/com/hirain/aiagent/trace/TraceAttributeKeys.java`  
  增加 context trace key 常量。

- `app/src/main/java/com/hirain/aiagent/prompt/PromptConstants.java`  
  增加 `textPersonaTemplateName(personaId)`，集中维护 TEXT persona 到系统提示词模板的映射。

- `app/src/main/java/com/hirain/aiagent/core/factory/AgentConfigFactory.java`  
  改为复用 `PromptConstants.textPersonaTemplateName(personaId)`，并将 `ContextExtraPreProcessor` 放在 TEXT persona 的 PreProcessor 链首位，避免 ContextFrame 只停留在 Map 中而未进入模型输入。

- `app/src/main/java/com/hirain/aiagent/AIAgentService.kt`  
  初始化 `ContextOrchestrator` 并注入 `AgentRuntime`；提供 `RuntimeCancelChecker`，TEXT worker 在线程入口和 runtime 返回后保留取消检查。

### 1.3 新增测试文件

- `app/src/test/java/com/hirain/aiagent/context/ContextFrameBuilderTest.java`
- `app/src/test/java/com/hirain/aiagent/context/ContextBudgetManagerTest.java`
- `app/src/test/java/com/hirain/aiagent/context/ContextOrchestratorTest.java`
- `app/src/test/java/com/hirain/aiagent/context/ContextProviderFailureTest.java`
- `app/src/test/java/com/hirain/aiagent/context/ContextTraceRecorderTest.java`
- `app/src/test/java/com/hirain/aiagent/context/provider/ToolGroupContextProviderTest.java`
- `app/src/test/java/com/hirain/aiagent/core/ContextExtraPreProcessorTest.java`
- `app/src/test/java/com/hirain/aiagent/core/AgentLoopOrchestratorContextInjectionTest.java`
- `app/src/test/java/com/hirain/aiagent/runtime/AgentRuntimeContextTest.java`
- `app/src/test/java/com/hirain/aiagent/runtime/AgentExecutorCompatibilityTest.java`

---

## Phase 1: 建立 Context 数据模型和预算规则

### Task 1.1: 新增 ContextMode、Section 类型和值对象

**Files:**
- Create: `app/src/main/java/com/hirain/aiagent/context/ContextMode.java`
- Create: `app/src/main/java/com/hirain/aiagent/context/ContextSectionType.java`
- Create: `app/src/main/java/com/hirain/aiagent/context/ContextSection.java`
- Test: `app/src/test/java/com/hirain/aiagent/context/ContextFrameBuilderTest.java`

- [ ] **Step 1: 写失败测试，锁定基础 section 不可变语义**

```java
package com.hirain.aiagent.context;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

public class ContextFrameBuilderTest {
    @Test
    public void contextSection_isImmutableAndKeepsMetadata() {
        ContextSection section = new ContextSection(
                ContextSectionType.RUNTIME,
                "RuntimeContextProvider",
                true,
                "requestId=req-1",
                15,
                false,
                Map.of("request_id", "req-1"));

        assertEquals(ContextSectionType.RUNTIME, section.type());
        assertEquals("RuntimeContextProvider", section.providerName());
        assertFalse(section.truncated());
        assertThrows(UnsupportedOperationException.class,
                () -> section.metadata().put("x", "y"));
    }
}
```

- [ ] **Step 2: 运行测试确认编译失败**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.context.ContextFrameBuilderTest"
```

Expected: 编译失败，提示 `ContextSection`、`ContextSectionType` 不存在。

- [ ] **Step 3: 新增 ContextMode**

```java
package com.hirain.aiagent.context;

public enum ContextMode {
    /** 只构建 ContextFrame 和 trace，不把任何 section 注入 AgentLoop。 */
    OBSERVE_ONLY,

    /** 一期默认模式：只把 runtime、intent、toolgroup、debug 等轻量上下文注入 extraContext。 */
    HYBRID_EXTRA_CONTEXT,

    /**
     * 完整 Context 接管模式。一期不真正启用，当前行为降级为 HYBRID_EXTRA_CONTEXT，
     * 并在 debugInfo 中记录 full_context_deferred=true。
     */
    FULL_CONTEXT
}
```

- [ ] **Step 4: 新增 ContextSectionType**

```java
package com.hirain.aiagent.context;

public enum ContextSectionType {
    RUNTIME,
    PERSONA,
    USER_INPUT,
    INTENT,
    TOOL_GROUP,
    MEMORY,
    VEHICLE_STATE,
    TIME,
    PROMPT,
    DEBUG
}
```

- [ ] **Step 5: 新增 ContextSection**

```java
package com.hirain.aiagent.context;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class ContextSection {
    private final ContextSectionType type;
    private final String providerName;
    private final boolean renderable;
    private final String content;
    private final int charCount;
    private final boolean truncated;
    private final Map<String, Object> metadata;

    public ContextSection(ContextSectionType type, String providerName,
                          boolean renderable, String content,
                          int charCount, boolean truncated,
                          Map<String, Object> metadata) {
        this.type = type;
        this.providerName = providerName;
        this.renderable = renderable;
        this.content = content != null ? content : "";
        this.charCount = charCount;
        this.truncated = truncated;
        this.metadata = Collections.unmodifiableMap(new HashMap<>(
                metadata != null ? metadata : Map.of()));
    }

    public ContextSectionType type() { return type; }
    public String providerName() { return providerName; }
    public boolean renderable() { return renderable; }
    public String content() { return content; }
    public int charCount() { return charCount; }
    public boolean truncated() { return truncated; }
    public Map<String, Object> metadata() { return metadata; }
}
```

- [ ] **Step 6: 运行基础测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.context.ContextFrameBuilderTest"
```

Expected: `BUILD SUCCESSFUL`。

### Task 1.2: 新增 ContextFrame 和 Builder

**Files:**
- Create: `app/src/main/java/com/hirain/aiagent/context/ContextFrame.java`
- Create: `app/src/main/java/com/hirain/aiagent/context/ContextFrameBuilder.java`
- Test: `app/src/test/java/com/hirain/aiagent/context/ContextFrameBuilderTest.java`

- [ ] **Step 1: 增加失败测试，确认 RequestSession 字段原样进入 ContextFrame**

```java
@Test
public void buildFromSession_preservesRequestSessionIds() {
    RequestSession session = TestRequestSessions.textSession(
            "req-1", "conv-1", "user-a", "friendly", "client-9", "打开空调");

    ContextFrame frame = ContextFrameBuilder.fromSession(session)
            .mode(ContextMode.HYBRID_EXTRA_CONTEXT)
            .effectivePersonaId("friendly")
            .renderedExtraContext("【Context】\nintent=VEHICLE_AC")
            .tokenEstimate(12)
            .build();

    assertEquals("req-1", frame.requestId());
    assertEquals("conv-1", frame.sessionId());
    assertEquals("user-a", frame.userId());
    assertEquals("friendly", frame.personaId());
    assertEquals("client-9", frame.clientMessageId());
    assertEquals("打开空调", frame.rawUserInput());
    assertEquals(ContextMode.HYBRID_EXTRA_CONTEXT, frame.mode());
}

@Test
public void toOrchestratorContext_preservesCallerExtraContext() {
    RequestSession session = TestRequestSessions.textSession(
            "req-1", "conv-1", "user-a", "chat", "client-1", "你好");
    ContextFrame frame = ContextFrameBuilder.fromSession(session)
            .mode(ContextMode.HYBRID_EXTRA_CONTEXT)
            .renderedExtraContext("【运行时上下文】")
            .build();
    Map<String, Object> base = new HashMap<>();
    base.put("extra_context", "caller value");

    Map<String, Object> merged = frame.toOrchestratorContext(base);

    assertEquals("caller value", merged.get("extra_context"));
    assertEquals("caller value", merged.get("caller_extra_context"));
    assertEquals("【运行时上下文】", merged.get("context_rendered_extra"));
}
```

- [ ] **Step 2: 新增测试辅助类**

Add to `app/src/test/java/com/hirain/aiagent/context/TestRequestSessions.java`:

```java
package com.hirain.aiagent.context;

import com.hirain.aiagent.AgentRequest;
import com.hirain.aiagent.intentrouter.IntentConfidence;
import com.hirain.aiagent.intentrouter.IntentResult;
import com.hirain.aiagent.intentrouter.IntentTag;
import com.hirain.aiagent.runtime.RequestSession;
import com.hirain.aiagent.runtime.RequestSessionFactory;
import com.hirain.aiagent.toolgroup.ToolGroupId;
import com.hirain.aiagent.toolgroup.ToolGroupSelectionResult;

import java.util.List;

public final class TestRequestSessions {
    private TestRequestSessions() {}

    public static RequestSession textSession(String requestId, String sessionId,
                                             String userId, String personaId,
                                             String clientMessageId, String text) {
        AgentRequest request = new AgentRequest();
        request.setRequestId(requestId);
        request.setSessionId(sessionId);
        request.setUserId(userId);
        request.setPersonaId(personaId);
        request.setClientMessageId(clientMessageId);
        request.setInputType("TEXT");
        request.setText(text);
        return new RequestSessionFactory(() -> "generated", () -> 1000L)
                .create(request, null,
                        IntentResult.of(IntentTag.VEHICLE_AC, IntentConfidence.HIGH,
                                List.of("空调"), text, "TEXT", "matched:VEHICLE_AC"),
                        ToolGroupSelectionResult.of(
                                List.of(ToolGroupId.AC_GROUP, ToolGroupId.BASIC_STATUS_GROUP),
                                List.of("set_ac_status"),
                                "intent:VEHICLE_AC",
                                IntentConfidence.HIGH,
                                false));
    }
}
```

- [ ] **Step 3: 实现 ContextFrame 字段**

`ContextFrame` 必须包含这些读取器：

```java
public String requestId();
public String clientMessageId();
public String userId();
public String sessionId();
public String personaId();
public String inputType();
public String rawUserInput();
public String normalizedUserInput();
public IntentResult intentResult();
public ToolGroupSelectionResult toolGroupSelectionResult();
public List<ToolGroupId> selectedGroupIds();
public List<String> selectedToolNames();
public String effectivePersonaId();
public String memorySummary();
public String vehicleStateSnapshot();
public String timeContext();
public String promptContext();
public String renderedExtraContext();
public ContextMode mode();
public int tokenEstimate();
public ContextDebugInfo debugInfo();
public List<ContextSection> sections();
public Map<String, Object> toOrchestratorContext(Map<String, Object> baseContext);
```

`toOrchestratorContext()` 规则：

```java
Map<String, Object> merged = new HashMap<>(baseContext);
merged.put("context_frame", this);
merged.put("context_mode", mode.name());
merged.put("context_rendered_extra", renderedExtraContext);
merged.put("selected_tool_names", selectedToolNames);
merged.put("selected_group_ids", selectedGroupIds.stream().map(Enum::name).toList());
if (baseContext != null && baseContext.containsKey("extra_context")) {
    merged.put("caller_extra_context", baseContext.get("extra_context"));
}
return Collections.unmodifiableMap(merged);
```

设计要求：

- `context_rendered_extra` 是 Context 模块生成文本的唯一标准 key。
- `ContextExtraPreProcessor` 只读取 `context_rendered_extra`，不读取 `extra_context`。
- 如果调用方原本通过 `AgentRequest.extraContext["extra_context"]` 传入自定义值，`toOrchestratorContext()` 不覆盖它；同时复制一份到 `caller_extra_context` 便于后续排查。
- 本阶段不依赖 `extra_context` 进入 LLM，因为当前 `AgentLoopOrchestrator.execute(userInput, extraContext)` 不会自动把该 key 转为 `ChatMessage`。

- [ ] **Step 4: 实现 ContextFrameBuilder.fromSession(session)**

Builder 只读取 `RequestSession`，不调用 ID 生成器：

```java
public static ContextFrameBuilder fromSession(RequestSession session) {
    return new ContextFrameBuilder()
            .requestId(session.requestId())
            .clientMessageId(session.clientMessageId())
            .userId(session.userId())
            .sessionId(session.sessionId())
            .personaId(session.personaId())
            .inputType(session.inputType())
            .rawUserInput(session.userInput())
            .normalizedUserInput(session.userInput() != null ? session.userInput().trim() : "")
            .intentResult(session.intentResult())
            .toolGroupSelectionResult(session.toolGroupSelectionResult())
            .selectedGroupIds(session.toolGroupSelectionResult().selectedGroupIds())
            .selectedToolNames(session.toolGroupSelectionResult().selectedToolNames());
}
```

- [ ] **Step 5: 运行测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.context.ContextFrameBuilderTest"
```

Expected: `BUILD SUCCESSFUL`。

### Task 1.3: 新增 ContextBudgetManager

**Files:**
- Create: `app/src/main/java/com/hirain/aiagent/context/ContextBudgetManager.java`
- Test: `app/src/test/java/com/hirain/aiagent/context/ContextBudgetManagerTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.hirain.aiagent.context;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ContextBudgetManagerTest {
    @Test
    public void trimSection_limitsCharsAndMarksTruncated() {
        ContextBudgetManager manager = ContextBudgetManager.defaultBudget();

        ContextBudgetManager.TrimmedText trimmed =
                manager.trim("abcdef", 4);

        assertEquals("abcd", trimmed.text());
        assertTrue(trimmed.truncated());
    }

    @Test
    public void estimateTokens_usesCoarseCharBasedEstimate() {
        ContextBudgetManager manager = ContextBudgetManager.defaultBudget();

        assertEquals(5, manager.estimateTokens("0123456789"));
    }
}
```

- [ ] **Step 2: 实现预算规则**

```java
public final class ContextBudgetManager {
    public static final int DEFAULT_SECTION_CHAR_LIMIT = 800;
    public static final int DEFAULT_MEMORY_CHAR_LIMIT = 500;
    public static final int DEFAULT_TOOL_CONTEXT_CHAR_LIMIT = 1200;
    public static final int DEFAULT_TOTAL_CHAR_LIMIT = 3000;

    public static final class TrimmedText {
        private final String text;
        private final boolean truncated;

        public TrimmedText(String text, boolean truncated) {
            this.text = text;
            this.truncated = truncated;
        }

        public String text() { return text; }
        public boolean truncated() { return truncated; }
    }

    public static ContextBudgetManager defaultBudget() {
        return new ContextBudgetManager(DEFAULT_SECTION_CHAR_LIMIT,
                DEFAULT_MEMORY_CHAR_LIMIT,
                DEFAULT_TOOL_CONTEXT_CHAR_LIMIT,
                DEFAULT_TOTAL_CHAR_LIMIT);
    }

    public TrimmedText trim(String value, int limit) {
        String safe = value != null ? value : "";
        if (safe.length() <= limit) return new TrimmedText(safe, false);
        return new TrimmedText(safe.substring(0, limit), true);
    }

    public int estimateTokens(String text) {
        String safe = text != null ? text : "";
        return (safe.length() + 1) / 2;
    }
}
```

- [ ] **Step 3: 运行预算测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.context.ContextBudgetManagerTest"
```

Expected: `BUILD SUCCESSFUL`。

---

## Phase 2: Provider 接口和基础 Provider

### Task 2.1: 建立 Provider 契约和构建输入

**Files:**
- Create: `app/src/main/java/com/hirain/aiagent/context/ContextBuildInput.java`
- Create: `app/src/main/java/com/hirain/aiagent/context/VehicleStatusProvider.java`
- Create: `app/src/main/java/com/hirain/aiagent/context/ContextProvider.java`
- Create: `app/src/main/java/com/hirain/aiagent/context/ContextProviderResult.java`
- Create: `app/src/main/java/com/hirain/aiagent/context/ContextBuildException.java`
- Create: `app/src/main/java/com/hirain/aiagent/context/ContextDebugInfo.java`
- Test: `app/src/test/java/com/hirain/aiagent/context/ContextProviderFailureTest.java`

- [ ] **Step 1: 写失败测试，锁定 provider failure 诊断字段**

```java
package com.hirain.aiagent.context;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class ContextProviderFailureTest {
    @Test
    public void failureResult_keepsProviderAndReason() {
        ContextProviderResult result =
                ContextProviderResult.failure("BrokenProvider", "boom");

        assertFalse(result.success());
        assertEquals("BrokenProvider", result.providerName());
        assertEquals("boom", result.errorReason());
    }
}
```

- [ ] **Step 2: 实现 ContextProvider**

```java
package com.hirain.aiagent.context;

import com.hirain.aiagent.runtime.RequestSession;

public interface ContextProvider {
    String name();
    ContextSectionType type();
    ContextProviderResult provide(RequestSession session, ContextBuildInput input)
            throws ContextBuildException;
}
```

- [ ] **Step 3: 实现 ContextProviderResult**

```java
public final class ContextProviderResult {
    public static ContextProviderResult success(String providerName, ContextSection section)
    public static ContextProviderResult fallback(String providerName, ContextSection section, String reason)
    public static ContextProviderResult failure(String providerName, String reason)

    public boolean success()
    public boolean fallback()
    public String providerName()
    public ContextSection section()
    public String errorReason()
}
```

- [ ] **Step 4: 实现 ContextBuildInput**

先新增 context 包内独立接口：

```java
package com.hirain.aiagent.context;

/**
 * 车辆状态快照提供者。
 * 设计原因：Context 模块只需要读取一份车辆状态字符串，不应直接依赖
 * core.preprocessor.VehicleStatusPreProcessor 的内部接口，避免 context -> core 的包依赖倒挂。
 */
@FunctionalInterface
public interface VehicleStatusProvider {
    String getVehicleStatus();
}
```

字段和读取器：

```java
private final ContextMode mode;
private final ToolGroupRegistry toolGroupRegistry;
private final PromptManager promptManager;
private final MemoryOrchestrator memoryOrchestrator;
private final VehicleStatusProvider vehicleStatusProvider;
private final TimeProvider timeProvider;
private final ContextBudgetManager budgetManager;
```

Builder 必须显式提供缺省值：

```java
public static Builder builder() {
    return new Builder();
}

public static final class Builder {
    private ContextMode mode = ContextMode.HYBRID_EXTRA_CONTEXT;
    private ToolGroupRegistry toolGroupRegistry;
    private PromptManager promptManager;
    private MemoryOrchestrator memoryOrchestrator;
    private VehicleStatusProvider vehicleStatusProvider;
    private TimeProvider timeProvider = new SystemTimeProvider();
    private ContextBudgetManager budgetManager = ContextBudgetManager.defaultBudget();

    public Builder mode(ContextMode value) {
        if (value != null) this.mode = value;
        return this;
    }

    public Builder toolGroupRegistry(ToolGroupRegistry value) {
        this.toolGroupRegistry = value;
        return this;
    }

    public Builder promptManager(PromptManager value) {
        this.promptManager = value;
        return this;
    }

    public Builder memoryOrchestrator(MemoryOrchestrator value) {
        this.memoryOrchestrator = value;
        return this;
    }

    public Builder vehicleStatusProvider(VehicleStatusProvider value) {
        this.vehicleStatusProvider = value;
        return this;
    }

    public Builder timeProvider(TimeProvider value) {
        if (value != null) this.timeProvider = value;
        return this;
    }

    public Builder budgetManager(ContextBudgetManager value) {
        if (value != null) this.budgetManager = value;
        return this;
    }

    public ContextBuildInput build() {
        return new ContextBuildInput(this);
    }
}
```

设计要求：

- `mode` 缺省为 `HYBRID_EXTRA_CONTEXT`。
- `budgetManager` 缺省为 `ContextBudgetManager.defaultBudget()`。
- `vehicleStatusProvider` 使用 `com.hirain.aiagent.context.VehicleStatusProvider`，生产初始化时用 `statusProvider::getVehicleStatus` 或 lambda 适配 `AIAgentService` 中已有的车辆状态提供实例。
- 依赖为空时 provider 必须 fallback，而不是抛出空指针。

- [ ] **Step 5: 实现 ContextDebugInfo**

字段：

```java
private final List<String> providerNames;
private final List<String> fallbackProviders;
private final Map<String, String> providerErrors;
private final int sectionCount;
private final boolean fallbackUsed;
private final long buildMs;
```

- [ ] **Step 6: 运行 failure 测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.context.ContextProviderFailureTest"
```

Expected: `BUILD SUCCESSFUL`。

### Task 2.2: 实现 Runtime / Persona / UserInput Provider

**Files:**
- Create: `app/src/main/java/com/hirain/aiagent/context/provider/RuntimeContextProvider.java`
- Create: `app/src/main/java/com/hirain/aiagent/context/provider/PersonaContextProvider.java`
- Create: `app/src/main/java/com/hirain/aiagent/context/provider/UserInputContextProvider.java`
- Test: `app/src/test/java/com/hirain/aiagent/context/ContextOrchestratorTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test
public void basicProviders_createRuntimePersonaAndInputSections() {
    RequestSession session = TestRequestSessions.textSession(
            "req-1", "conv-1", "user-a", "friendly", "client-9", "打开空调");
    ContextBuildInput input = ContextBuildInput.builder()
            .mode(ContextMode.HYBRID_EXTRA_CONTEXT)
            .build();

    ContextProviderResult runtime =
            new RuntimeContextProvider().provide(session, input);
    ContextProviderResult persona =
            new PersonaContextProvider().provide(session, input);
    ContextProviderResult userInput =
            new UserInputContextProvider().provide(session, input);

    assertTrue(runtime.success());
    assertTrue(persona.success());
    assertTrue(userInput.success());
    assertEquals(ContextSectionType.RUNTIME, runtime.section().type());
    assertEquals("friendly", persona.section().metadata().get("persona_id"));
    assertEquals("打开空调", userInput.section().metadata().get("raw_user_input"));
}
```

- [ ] **Step 2: RuntimeContextProvider 内容规则**

Renderable: `true` in `HYBRID_EXTRA_CONTEXT`.

Content format:

```text
【运行时上下文】
- requestId: req-1
- userId: user-a
- sessionId: conv-1
- personaId: friendly
- clientMessageId: client-9
- inputType: TEXT
```

Metadata keys:

```java
"request_id", "user_id", "session_id", "persona_id", "client_message_id", "input_type"
```

- [ ] **Step 3: PersonaContextProvider 内容规则**

Renderable: `true` in `HYBRID_EXTRA_CONTEXT`.

Content format:

```text
【人格上下文】
- requestedPersonaId: friendly
- effectivePersonaId: friendly
```

注意：provider 只使用 `RequestSession.personaId()`，不重新 normalize personaId。

- [ ] **Step 4: UserInputContextProvider 内容规则**

Renderable: `false` in `HYBRID_EXTRA_CONTEXT`，避免把用户输入重复注入给 LLM。

Metadata keys:

```java
"raw_user_input", "normalized_user_input", "input_length"
```

- [ ] **Step 5: 运行基础 provider 测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.context.ContextOrchestratorTest"
```

Expected: `BUILD SUCCESSFUL`。

### Task 2.3: 实现 Intent / ToolGroup Provider

**Files:**
- Create: `app/src/main/java/com/hirain/aiagent/context/provider/IntentContextProvider.java`
- Create: `app/src/main/java/com/hirain/aiagent/context/provider/ToolGroupContextProvider.java`
- Test: `app/src/test/java/com/hirain/aiagent/context/provider/ToolGroupContextProviderTest.java`

本阶段采用两阶段策略：

- 一期只渲染轻量工具说明：selected group 描述 + selected tool names，不读取完整 `ToolSpecification.description` 或参数 schema。这里的 “selected tool descriptions” 验收口径限定为“选中工具组语义 + 选中 toolName 列表”，不是 LangChain4j `@Tool` 的完整自然语言描述。
- 后续阶段再升级为完整 selected tool specification 渲染；届时需要单独设计 tool spec 查询接口，评估是否把 `ToolRegistry` 或 `List<ToolSpecification>` 纳入 `ContextBuildInput`。

- [ ] **Step 1: 写失败测试，验证只渲染 selected tools**

```java
package com.hirain.aiagent.context.provider;

import com.hirain.aiagent.context.ContextBuildInput;
import com.hirain.aiagent.context.ContextMode;
import com.hirain.aiagent.context.ContextProviderResult;
import com.hirain.aiagent.context.TestRequestSessions;
import com.hirain.aiagent.runtime.RequestSession;
import com.hirain.aiagent.toolgroup.ToolGroupRegistry;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ToolGroupContextProviderTest {
    @Test
    public void provide_rendersOnlySelectedTools() throws Exception {
        RequestSession session = TestRequestSessions.textSession(
                "req-1", "conv-1", "user-a", "chat", "client-1", "打开空调");
        ContextBuildInput input = ContextBuildInput.builder()
                .mode(ContextMode.HYBRID_EXTRA_CONTEXT)
                .toolGroupRegistry(ToolGroupRegistry.defaultRegistry())
                .build();

        ContextProviderResult result =
                new ToolGroupContextProvider().provide(session, input);

        String content = result.section().content();
        assertTrue(content.contains("AC_GROUP"));
        assertTrue(content.contains("set_ac_status"));
        assertFalse(content.contains("set_fl_window_status"));
        assertFalse(content.contains("front_camera_interaction"));
    }
}
```

- [ ] **Step 2: IntentContextProvider 内容规则**

Renderable: `true`.

Content format:

```text
【意图上下文】
- intentTag: VEHICLE_AC
- confidence: HIGH
- matchedKeywords: 空调
- debugReason: matched:VEHICLE_AC
```

- [ ] **Step 3: ToolGroupContextProvider 内容规则**

Renderable: `true`.

Content format:

```text
【工具组上下文】
- selectedGroupIds: AC_GROUP,BASIC_STATUS_GROUP
- selectedToolCount: 1
- selectedToolNames:
  - set_ac_status
- groupDescriptions:
  - AC_GROUP: 空调工具组 / 空调及温控相关控制 / risk=MEDIUM
  - BASIC_STATUS_GROUP: 基础状态组 / 车辆请求所需的基础上下文 / risk=LOW
```

实现要求：

- `selectedToolNames` 必须来自 `session.toolGroupSelectionResult().selectedToolNames()`。
- 不得调用 `ToolGroupRegistry.allGroups()` 渲染全量工具。
- selected group 描述可通过 `ToolGroupRegistry.group(groupId)` 读取。
- 一期不得渲染完整 `ToolSpecification.description` 或参数 schema，避免扩大到真实 tool binding 重构。
- 如果后续要求“每个 selectedToolName 对应真实 @Tool 描述”，必须新增只读查询能力，例如 `ContextBuildInput.toolSpecifications(List<ToolSpecification>)` 或 `SelectedToolSpecProvider`，但不在本阶段实现。
- 如果 `ToolGroupRegistry` 为空，返回 fallback section，内容只包含 selected toolName，不崩溃。

- [ ] **Step 4: 运行 provider 测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.context.provider.ToolGroupContextProviderTest"
```

Expected: `BUILD SUCCESSFUL`。

### Task 2.4: 实现 Memory / Vehicle / Time / Prompt Provider 的兼容模式

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/prompt/PromptConstants.java`
- Modify: `app/src/main/java/com/hirain/aiagent/core/factory/AgentConfigFactory.java`
- Create: `app/src/main/java/com/hirain/aiagent/context/provider/MemoryContextProvider.java`
- Create: `app/src/main/java/com/hirain/aiagent/context/provider/VehicleStateContextProvider.java`
- Create: `app/src/main/java/com/hirain/aiagent/context/provider/TimeContextProvider.java`
- Create: `app/src/main/java/com/hirain/aiagent/context/provider/PromptContextProvider.java`
- Test: `app/src/test/java/com/hirain/aiagent/context/ContextOrchestratorTest.java`

- [ ] **Step 1: 写失败测试，验证 HYBRID 模式不重复渲染 memory/vehicle/time/prompt**

```java
@Test
public void hybridMode_keepsExistingPreprocessorOwnedSectionsNonRenderable() {
    RequestSession session = TestRequestSessions.textSession(
            "req-1", "conv-1", "user-a", "chat", "client-1", "你好");
    ContextBuildInput input = ContextBuildInput.builder()
            .mode(ContextMode.HYBRID_EXTRA_CONTEXT)
            .vehicleStatusProvider(() -> "{\"speed\":0}")
            .timeProvider(() -> 1000L)
            .build();

    assertFalse(new MemoryContextProvider().provide(session, input).section().renderable());
    assertFalse(new VehicleStateContextProvider().provide(session, input).section().renderable());
    assertFalse(new TimeContextProvider().provide(session, input).section().renderable());
    assertFalse(new PromptContextProvider().provide(session, input).section().renderable());
}
```

- [ ] **Step 2: MemoryContextProvider 规则**

HYBRID 模式：

- `renderable=false`
- `memorySummary=""`
- 一期验收允许 `memorySummary` 为空；`ContextFrame` 通过 `MEMORY` section metadata 表示 memory owner 和是否由 Context 注入，不在本阶段读取或生成真实摘要。
- metadata:

```java
"memory_owner" -> "AgentLoopOrchestrator/MemoryOrchestrator"
"memory_injected_by_context" -> false
"session_id" -> session.sessionId()
```

禁止行为：

- 不读取或拼接完整短期 ChatMemory 历史。
- 不修改 `SessionMemoryStore`、`SessionManager`、`MemoryCompressor`、`MemoryExtractor`。

- [ ] **Step 3: VehicleStateContextProvider 规则**

HYBRID 模式：

- 调用 `VehicleStatusProvider.getVehicleStatus()` 获取 snapshot，写入 `ContextFrame.vehicleStateSnapshot`。
- `renderable=false`，避免与 `VehicleStatusPreProcessor` 重复注入。
- provider 异常时 fallback，metadata 写入 `vehicle_snapshot_available=false`。

- [ ] **Step 4: TimeContextProvider 规则**

HYBRID 模式：

- 使用 `TimeProvider.nowMillis()` 生成 `yyyy-MM-dd HH:mm:ss` 调试值。
- `renderable=false`，避免与 `TimeContextPreProcessor` 重复注入。

- [ ] **Step 5: 集中 TEXT persona 到 Prompt 模板映射**

先在 `PromptConstants` 中新增统一方法：

```java
/**
 * 根据 TEXT persona 选择系统提示词模板。
 * 设计原因：AgentConfigFactory 和 Context 的 PromptContextProvider 都需要记录同一映射，
 * 映射必须集中维护，避免新增 persona 时出现两处硬编码不一致。
 */
public static String textPersonaTemplateName(String personaId) {
    if ("friendly".equals(personaId)) return SYSTEM_ASSISTANT_FRIENDLY;
    if ("concise".equals(personaId)) return SYSTEM_ASSISTANT_CONCISE;
    return SYSTEM_ASSISTANT_DEFAULT;
}
```

然后将 `AgentConfigFactory.switchPersonaTemplate(personaId)` 改为：

```java
private static String switchPersonaTemplate(String personaId) {
    return PromptConstants.textPersonaTemplateName(personaId);
}
```

- [ ] **Step 6: PromptContextProvider 规则**

HYBRID 模式：

- 复用 `PromptConstants.textPersonaTemplateName(session.personaId())` 记录 persona prompt source。
- 不调用 `PromptManager.render()` 重复渲染 system prompt。
- 一期验收口径为“复用现有 prompt 命名和 persona 映射规则”，不是由 `PromptContextProvider` 重新调用 `PromptManager.render()` 注入 system prompt。
- metadata:

```java
"prompt_owner" -> "AgentLoopOrchestrator.injectSystemPrompt"
"prompt_template_name" -> "system/assistant_default"
"prompt_injected_by_context" -> false
```

template 映射规则：

```java
String templateName = PromptConstants.textPersonaTemplateName(session.personaId());
```

- [ ] **Step 7: 运行兼容 provider 测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.context.ContextOrchestratorTest"
```

Expected: `BUILD SUCCESSFUL`。

---

## Phase 3: ContextOrchestrator 组装、渲染和 fallback

### Task 3.1: 实现 ContextOrchestrator 默认 provider 链

**Files:**
- Create: `app/src/main/java/com/hirain/aiagent/context/ContextOrchestrator.java`
- Create: `app/src/main/java/com/hirain/aiagent/context/ContextBuildResult.java`
- Test: `app/src/test/java/com/hirain/aiagent/context/ContextOrchestratorTest.java`

- [ ] **Step 1: 写失败测试，验证构建结果含 ContextFrame 和 renderedExtraContext**

```java
@Test
public void build_hybridModeCreatesFrameBeforeAgentLoop() {
    RequestSession session = TestRequestSessions.textSession(
            "req-1", "conv-1", "user-a", "chat", "client-1", "打开空调");
    ContextOrchestrator orchestrator =
            ContextOrchestrator.defaultForText(ContextBuildInput.builder()
                    .mode(ContextMode.HYBRID_EXTRA_CONTEXT)
                    .toolGroupRegistry(ToolGroupRegistry.defaultRegistry())
                    .vehicleStatusProvider(() -> "{\"speed\":0}")
                    .timeProvider(() -> 1000L)
                    .build());

    ContextBuildResult result = orchestrator.build(session);

    assertTrue(result.success());
    assertEquals("req-1", result.frame().requestId());
    assertEquals(ContextMode.HYBRID_EXTRA_CONTEXT, result.frame().mode());
    assertTrue(result.frame().renderedExtraContext().contains("【运行时上下文】"));
    assertTrue(result.frame().renderedExtraContext().contains("【意图上下文】"));
    assertTrue(result.frame().renderedExtraContext().contains("【工具组上下文】"));
    assertFalse(result.frame().renderedExtraContext().contains("当前时间："));
    assertFalse(result.frame().renderedExtraContext().contains("【长期记忆】"));
}
```

- [ ] **Step 2: 实现默认 provider 顺序**

```java
List.of(
        new RuntimeContextProvider(),
        new PersonaContextProvider(),
        new UserInputContextProvider(),
        new IntentContextProvider(),
        new ToolGroupContextProvider(),
        new MemoryContextProvider(),
        new VehicleStateContextProvider(),
        new TimeContextProvider(),
        new PromptContextProvider()
)
```

- [ ] **Step 3: 实现 renderedExtraContext 规则**

规则：

- `OBSERVE_ONLY`: 所有 section 构建并进入 frame，`renderedExtraContext=""`。
- `HYBRID_EXTRA_CONTEXT`: 只拼接 `section.renderable()==true` 的 section。
- `FULL_CONTEXT`: 本阶段不接入 AgentRuntime；可由 enum 支持，但 `ContextOrchestrator` 遇到该模式时仍按 HYBRID 渲染并在 debugInfo 记录 `full_context_deferred=true`。

渲染代码形态：

```java
String rendered = sections.stream()
        .filter(ContextSection::renderable)
        .map(ContextSection::content)
        .filter(text -> !text.isEmpty())
        .collect(Collectors.joining("\n\n"));
```

- [ ] **Step 4: 实现 ContextBuildResult**

读取器：

```java
public boolean success();
public ContextFrame frame();
public boolean fallbackUsed();
public String errorReason();
```

- [ ] **Step 5: 运行 orchestrator 测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.context.ContextOrchestratorTest"
```

Expected: `BUILD SUCCESSFUL`。

### Task 3.2: Provider 异常 fallback

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/context/ContextOrchestrator.java`
- Test: `app/src/test/java/com/hirain/aiagent/context/ContextProviderFailureTest.java`

- [ ] **Step 1: 写失败测试，验证单个 provider 失败不阻断构建**

```java
@Test
public void build_providerExceptionCreatesDebugInfoAndContinues() {
    ContextProvider broken = new ContextProvider() {
        @Override public String name() { return "BrokenProvider"; }
        @Override public ContextSectionType type() { return ContextSectionType.DEBUG; }
        @Override public ContextProviderResult provide(RequestSession session, ContextBuildInput input) {
            throw new IllegalStateException("boom");
        }
    };
    ContextOrchestrator orchestrator = new ContextOrchestrator(
            ContextBuildInput.builder().mode(ContextMode.HYBRID_EXTRA_CONTEXT).build(),
            List.of(new RuntimeContextProvider(), broken));

    ContextBuildResult result = orchestrator.build(TestRequestSessions.textSession(
            "req-1", "conv-1", "user-a", "chat", "client-1", "你好"));

    assertTrue(result.success());
    assertTrue(result.fallbackUsed());
    assertEquals("boom", result.frame().debugInfo().providerErrors().get("BrokenProvider"));
}
```

- [ ] **Step 2: 实现 provider try/catch**

```java
for (ContextProvider provider : providers) {
    try {
        ContextProviderResult result = provider.provide(session, input);
        collectProviderResult(result);
    } catch (Exception e) {
        debugErrors.put(provider.name(), e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        fallbackProviders.add(provider.name());
    }
}
```

- [ ] **Step 3: 构建最小 fallback frame**

如果所有 provider 都失败，仍返回可用 frame：

```java
ContextFrame frame = ContextFrameBuilder.fromSession(session)
        .mode(input.mode())
        .effectivePersonaId(session.personaId())
        .renderedExtraContext("")
        .debugInfo(debugInfo)
        .build();
return ContextBuildResult.fallback(frame, "all_context_providers_failed");
```

- [ ] **Step 4: 运行异常测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.context.ContextProviderFailureTest"
```

Expected: `BUILD SUCCESSFUL`。

---

## Phase 4: Runtime 兼容接入

### Task 4.1: AgentExecutor 增加 ContextFrame 兼容接口

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/runtime/AgentExecutor.java`
- Test: `app/src/test/java/com/hirain/aiagent/runtime/AgentExecutorCompatibilityTest.java`

- [ ] **Step 1: 写失败测试，验证新接口默认调用旧接口**

```java
package com.hirain.aiagent.runtime;

import com.hirain.aiagent.context.ContextFrame;
import com.hirain.aiagent.context.ContextFrameBuilder;
import com.hirain.aiagent.context.ContextMode;
import com.hirain.aiagent.context.TestRequestSessions;
import com.hirain.aiagent.core.AgentResult;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AgentExecutorCompatibilityTest {
    @Test
    public void defaultContextFrameExecuteDelegatesToOldExecute() {
        AtomicReference<Map<String, Object>> contextRef = new AtomicReference<>();
        AgentExecutor executor = (userInput, context) -> {
            contextRef.set(context);
            return AgentResult.success("ok", 1, 1L, List.of());
        };
        RequestSession session = TestRequestSessions.textSession(
                "req-1", "conv-1", "user-a", "chat", "client-1", "打开空调");
        ContextFrame frame = ContextFrameBuilder.fromSession(session)
                .mode(ContextMode.HYBRID_EXTRA_CONTEXT)
                .renderedExtraContext("【Context】")
                .build();

        AgentResult result = executor.execute(session, frame);

        assertTrue(result.isSuccess());
        assertEquals("【Context】", contextRef.get().get("context_rendered_extra"));
    }
}
```

- [ ] **Step 2: 修改 AgentExecutor**

```java
@FunctionalInterface
public interface AgentExecutor {
    AgentResult execute(String userInput, Map<String, Object> context);

    default AgentResult execute(RequestSession session, ContextFrame contextFrame) {
        return execute(session.userInput(),
                contextFrame.toOrchestratorContext(session.orchestratorContext()));
    }
}
```

- [ ] **Step 3: 运行兼容测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.runtime.AgentExecutorCompatibilityTest"
```

Expected: `BUILD SUCCESSFUL`。

### Task 4.2: ContextExtraPreProcessor 将 Context 文本注入 ChatRequest

**Files:**
- Create: `app/src/main/java/com/hirain/aiagent/core/preprocessor/ContextExtraPreProcessor.java`
- Modify: `app/src/main/java/com/hirain/aiagent/core/factory/AgentConfigFactory.java`
- Test: `app/src/test/java/com/hirain/aiagent/core/ContextExtraPreProcessorTest.java`
- Test: `app/src/test/java/com/hirain/aiagent/core/AgentLoopOrchestratorContextInjectionTest.java`

- [ ] **Step 1: 写失败测试，验证 PreProcessor 只在 HYBRID 首轮注入**

```java
package com.hirain.aiagent.core;

import com.hirain.aiagent.core.preprocessor.ContextExtraPreProcessor;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.langchain4j.data.message.ChatMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ContextExtraPreProcessorTest {
    @Test
    public void prepare_hybridFirstIterationReturnsContextMessage() {
        Map<String, Object> data = new HashMap<>();
        data.put("context_mode", "HYBRID_EXTRA_CONTEXT");
        data.put("context_rendered_extra", "【运行时上下文】\n- requestId: req-1");
        AgentLoopContext ctx = new AgentLoopContext("打开空调", "chat", data);

        List<ChatMessage> messages = new ContextExtraPreProcessor().prepare(ctx);

        assertEquals(1, messages.size());
        assertTrue(messages.get(0).toString().contains("【运行时上下文】"));
    }

    @Test
    public void prepare_observeOnlyReturnsNoMessages() {
        Map<String, Object> data = new HashMap<>();
        data.put("context_mode", "OBSERVE_ONLY");
        data.put("context_rendered_extra", "【运行时上下文】");
        AgentLoopContext ctx = new AgentLoopContext("你好", "chat", data);

        assertTrue(new ContextExtraPreProcessor().prepare(ctx).isEmpty());
    }

    @Test
    public void prepare_secondIterationReturnsNoMessages() {
        Map<String, Object> data = new HashMap<>();
        data.put("context_mode", "HYBRID_EXTRA_CONTEXT");
        data.put("context_rendered_extra", "【运行时上下文】");
        AgentLoopContext ctx = new AgentLoopContext("打开空调", "chat", data);
        ctx.setIteration(1);

        assertTrue(new ContextExtraPreProcessor().prepare(ctx).isEmpty());
    }
}
```

- [ ] **Step 2: 实现 ContextExtraPreProcessor**

```java
package com.hirain.aiagent.core.preprocessor;

import com.hirain.aiagent.core.AgentLoopContext;
import com.hirain.aiagent.core.component.PreProcessor;

import java.util.List;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

/**
 * 将 Context 模块生成的轻量上下文注入首轮模型请求。
 * 设计原因：AgentLoopOrchestrator 只会把 PreProcessor 返回值加入 ChatRequest.messages()；
 * 单纯把 context_rendered_extra 放进 extraContext Map 不会进入 LLM 输入。
 */
public class ContextExtraPreProcessor implements PreProcessor {
    public static final String KEY_CONTEXT_RENDERED_EXTRA = "context_rendered_extra";
    public static final String KEY_CONTEXT_MODE = "context_mode";
    private static final String HYBRID_MODE = "HYBRID_EXTRA_CONTEXT";

    @Override
    public List<ChatMessage> prepare(AgentLoopContext ctx) {
        if (ctx.iteration() != 0) return List.of();
        String mode = ctx.getContextData(KEY_CONTEXT_MODE, String.class);
        if (!HYBRID_MODE.equals(mode)) return List.of();
        String text = ctx.getContextData(KEY_CONTEXT_RENDERED_EXTRA, String.class);
        if (text == null || text.trim().isEmpty()) return List.of();
        return List.of(UserMessage.from(text));
    }
}
```

- [ ] **Step 3: 将 ContextExtraPreProcessor 放入 TEXT persona preProcessor 链首位**

`AgentConfigFactory.createChatPersona()` 和 `createTextPersona()` 中的 TEXT 链路都必须放在 `MemoryPreProcessor` 前：

```java
.preProcessors(List.of(
        new ContextExtraPreProcessor(),
        new MemoryPreProcessor(memoryOrchestrator),
        new VehicleStatusPreProcessor(promptManager, statusProvider),
        new TimeContextPreProcessor()))
```

设计原因：Context 轻量运行时说明应在旧 memory/vehicle/time 临时消息之前进入 `transientMessages`，但不替代旧 preprocessor。

- [ ] **Step 4: 写 AgentLoop 级别测试，证明 Context 文本进入 ChatRequest.messages()**

该测试文件必须包含以下 import：

```java
import com.hirain.aiagent.core.AgentConfig;
import com.hirain.aiagent.core.AgentLoopOrchestrator;
import com.hirain.aiagent.core.AgentResult;
import com.hirain.aiagent.core.collector.DirectTextCollector;
import com.hirain.aiagent.core.component.ModelCaller;
import com.hirain.aiagent.core.preprocessor.ContextExtraPreProcessor;
import com.hirain.aiagent.core.safety.AllowAllSafetyGuard;
import com.hirain.aiagent.core.terminator.MaxIterationTerminator;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
```

```java
@Test
public void execute_hybridContextAppearsInChatRequestMessages() {
    AtomicReference<ChatRequest> requestRef = new AtomicReference<>();
    ModelCaller caller = request -> {
        requestRef.set(request);
        return ChatResponse.builder()
                .aiMessage(AiMessage.from("ok"))
                .build();
    };
    AgentConfig config = AgentConfig.builder("chat")
            .modelName("fake")
            .systemPromptTemplateName("system/assistant_default")
            .maxIterations(1)
            .maxMemoryMessages(10)
            .memoryPolicy(AgentConfig.MemoryPolicy.EPHEMERAL)
            .preProcessors(List.of(new ContextExtraPreProcessor()))
            .modelCaller(caller)
            .toolSubset(List.of())
            .toolExecutor(request -> "{}")
            .safetyGuards(List.of(new AllowAllSafetyGuard()))
            .postProcessors(List.of())
            .terminator(new MaxIterationTerminator(1))
            .resultCollector(new DirectTextCollector())
            .build();
    AgentLoopOrchestrator orchestrator = new AgentLoopOrchestrator(config);
    Map<String, Object> context = new HashMap<>();
    context.put("context_mode", "HYBRID_EXTRA_CONTEXT");
    context.put("context_rendered_extra", "【运行时上下文】\n【意图上下文】\n【工具组上下文】");

    AgentResult result = orchestrator.execute("打开空调", context);

    assertTrue(result.isSuccess());
    String messages = requestRef.get().messages().toString();
    assertTrue(messages.contains("【运行时上下文】"));
    assertTrue(messages.contains("【意图上下文】"));
    assertTrue(messages.contains("【工具组上下文】"));
    assertFalse(messages.contains("【长期记忆】"));
    assertFalse(messages.contains("车辆状态："));
    assertFalse(messages.contains("当前时间："));
}
```

- [ ] **Step 5: 运行 Context 注入测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.core.ContextExtraPreProcessorTest" --tests "com.hirain.aiagent.core.AgentLoopOrchestratorContextInjectionTest"
```

Expected: `BUILD SUCCESSFUL`。

### Task 4.3: AgentRuntime 构建 ContextFrame 后再执行 AgentExecutor

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/runtime/AgentRuntime.java`
- Create: `app/src/main/java/com/hirain/aiagent/runtime/RuntimeCancelChecker.java`
- Test: `app/src/test/java/com/hirain/aiagent/runtime/AgentRuntimeContextTest.java`
- Test: `app/src/test/java/com/hirain/aiagent/runtime/AgentRuntimeTest.java`

- [ ] **Step 1: 写失败测试，验证 execute 传入 ContextFrame**

```java
package com.hirain.aiagent.runtime;

import com.hirain.aiagent.AgentRequest;
import com.hirain.aiagent.context.ContextBuildInput;
import com.hirain.aiagent.context.ContextFrame;
import com.hirain.aiagent.context.ContextMode;
import com.hirain.aiagent.context.ContextOrchestrator;
import com.hirain.aiagent.core.AgentResult;
import com.hirain.aiagent.toolgroup.ToolGroupRegistry;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AgentRuntimeContextTest {
    @Test
    public void execute_buildsContextFrameBeforeExecutor() {
        AtomicReference<ContextFrame> frameRef = new AtomicReference<>();
        AgentExecutor executor = new AgentExecutor() {
            @Override
            public AgentResult execute(String userInput, java.util.Map<String, Object> context) {
                return AgentResult.error(AgentResult.ErrorType.INVALID_CONFIG, "old path should not be used");
            }

            @Override
            public AgentResult execute(RequestSession session, ContextFrame contextFrame) {
                frameRef.set(contextFrame);
                return AgentResult.success("完成", 1, 1L, List.of());
            }
        };
        ContextOrchestrator contextOrchestrator =
                ContextOrchestrator.defaultForText(ContextBuildInput.builder()
                        .mode(ContextMode.HYBRID_EXTRA_CONTEXT)
                        .toolGroupRegistry(ToolGroupRegistry.defaultRegistry())
                        .build());
        AgentRuntime runtime = new AgentRuntime(executor, contextOrchestrator,
                () -> "req-1", () -> 1000L);
        AgentRequest request = new AgentRequest();
        request.setInputType("TEXT");
        request.setText("打开空调");

        RuntimeResult result = runtime.execute(runtime.startSession(request, null));

        assertTrue(result.success());
        assertEquals("req-1", frameRef.get().requestId());
        assertEquals(ContextMode.HYBRID_EXTRA_CONTEXT, frameRef.get().mode());
    }
}
```

- [ ] **Step 2: AgentRuntime 增加构造函数**

新增取消检查接口：

```java
package com.hirain.aiagent.runtime;

/**
 * Runtime 内部取消检查器。
 * 设计原因：Context 构建发生在 AgentRuntime.execute(session) 内部，
 * Service 层无法在 Context build 完成后、AgentLoop 启动前插入检查。
 */
@FunctionalInterface
public interface RuntimeCancelChecker {
    boolean isCancelled(RequestSession session);

    static RuntimeCancelChecker neverCancelled() {
        return session -> false;
    }
}
```

新增字段：

```java
private final ContextOrchestrator contextOrchestrator;
private final RuntimeCancelChecker cancelChecker;
```

生产构造函数：

```java
public AgentRuntime(AgentExecutor chatExecutor, ContextOrchestrator contextOrchestrator) {
    this(chatExecutor, contextOrchestrator, RuntimeCancelChecker.neverCancelled());
}

public AgentRuntime(AgentExecutor chatExecutor,
                    ContextOrchestrator contextOrchestrator,
                    RuntimeCancelChecker cancelChecker) {
    this(chatExecutor, contextOrchestrator, new KeywordIntentRouter(),
            new DefaultToolGroupSelector(ToolGroupRegistry.defaultRegistry()),
            new UuidIdGenerator(), new SystemTimeProvider(), cancelChecker);
}

public AgentRuntime(AgentExecutor chatExecutor,
                    ContextOrchestrator contextOrchestrator,
                    IdGenerator idGenerator,
                    TimeProvider timeProvider) {
    this(chatExecutor, contextOrchestrator, new KeywordIntentRouter(),
            new DefaultToolGroupSelector(ToolGroupRegistry.defaultRegistry()),
            idGenerator, timeProvider, RuntimeCancelChecker.neverCancelled());
}
```

测试构造函数保留旧签名，内部使用最小默认 ContextOrchestrator：

```java
this(chatExecutor,
        ContextOrchestrator.defaultForText(ContextBuildInput.builder()
                .mode(ContextMode.HYBRID_EXTRA_CONTEXT)
                .toolGroupRegistry(ToolGroupRegistry.defaultRegistry())
                .build()),
        ...);
```

最终只保留以下构造函数组合，不为每个旧构造函数额外复制一套带 `ContextOrchestrator` 的重载：

```java
public AgentRuntime(AgentExecutor chatExecutor)

public AgentRuntime(AgentExecutor chatExecutor,
                    ContextOrchestrator contextOrchestrator)

public AgentRuntime(AgentExecutor chatExecutor,
                    ContextOrchestrator contextOrchestrator,
                    RuntimeCancelChecker cancelChecker)

public AgentRuntime(AgentExecutor chatExecutor,
                    ContextOrchestrator contextOrchestrator,
                    IdGenerator idGenerator,
                    TimeProvider timeProvider)

public AgentRuntime(AgentExecutor chatExecutor,
                    IdGenerator idGenerator,
                    TimeProvider timeProvider)

public AgentRuntime(AgentExecutor chatExecutor,
                    IntentRouter intentRouter,
                    IdGenerator idGenerator,
                    TimeProvider timeProvider)

public AgentRuntime(AgentExecutor chatExecutor,
                    IntentRouter intentRouter,
                    ToolGroupSelector toolGroupSelector,
                    IdGenerator idGenerator,
                    TimeProvider timeProvider)

public AgentRuntime(AgentExecutor chatExecutor,
                    ContextOrchestrator contextOrchestrator,
                    IntentRouter intentRouter,
                    ToolGroupSelector toolGroupSelector,
                    IdGenerator idGenerator,
                    TimeProvider timeProvider,
                    RuntimeCancelChecker cancelChecker)
```

旧测试构造函数全部委托到最后一个全量构造函数，并使用默认 `ContextOrchestrator` 和 `RuntimeCancelChecker.neverCancelled()`。生产 Service 使用 `AgentRuntime(chatExecutor, contextOrchestrator, cancelChecker)`，保留 `AgentRuntime(chatExecutor, contextOrchestrator)` 供不关心取消插入点的单元测试和简化调用使用。

- [ ] **Step 3: 修改 execute(session)**

```java
public RuntimeResult execute(RequestSession session) {
    try {
        ContextBuildResult contextBuildResult = contextOrchestrator.build(session);
        ContextFrame contextFrame = contextBuildResult.frame();
        if (cancelChecker.isCancelled(session)) {
            return RuntimeResult.cancelled(session.requestId(), session.sessionId(),
                    session.userId(), session.personaId(), session.clientMessageId(),
                    "cancelled_before_agent_loop", timeProvider.nowMillis());
        }
        AgentResult result = chatExecutor.execute(session, contextFrame);
        return RuntimeResult.fromAgentResult(...);
    } catch (Exception e) {
        return RuntimeResult.fromException(...);
    }
}
```

要求：

- `ContextOrchestrator.build()` 抛异常时进入 `fromException`，由 Service 终态抢占逻辑决定是否发送。
- provider 单点失败不应抛到这里，应由 `ContextOrchestrator` fallback。
- `cancelChecker.isCancelled(session)` 必须发生在 `contextOrchestrator.build(session)` 之后、`chatExecutor.execute(session, contextFrame)` 之前。
- 返回 `RuntimeResult.cancelled(...)` 后不得调用 `chatExecutor`，用于满足“Context 构建后、AgentLoop 前取消则不进入 AgentLoop”的目标。

- [ ] **Step 4: 写失败测试，验证 Context build 后取消不会调用 executor**

```java
@Test
public void execute_cancelledAfterContextBuildDoesNotCallExecutor() {
    AtomicBoolean executorCalled = new AtomicBoolean(false);
    AgentExecutor executor = new AgentExecutor() {
        @Override
        public AgentResult execute(String userInput, java.util.Map<String, Object> context) {
            executorCalled.set(true);
            return AgentResult.success("不应执行", 1, 1L, List.of());
        }
    };
    AgentRuntime runtime = new AgentRuntime(
            executor,
            ContextOrchestrator.defaultForText(ContextBuildInput.builder()
                    .mode(ContextMode.HYBRID_EXTRA_CONTEXT)
                    .toolGroupRegistry(ToolGroupRegistry.defaultRegistry())
                    .build()),
            session -> true);
    AgentRequest request = new AgentRequest();
    request.setInputType("TEXT");
    request.setText("打开空调");

    RuntimeResult result = runtime.execute(runtime.startSession(request, null));

    assertEquals("CANCELLED", result.errorType());
    assertFalse(executorCalled.get());
}
```

- [ ] **Step 5: 更新旧 AgentRuntimeTest 断言**

现有测试 `execute_callsExecutorWithNormalizedContext` 仍通过，因为 `AgentExecutor` 默认方法会把 `ContextFrame` 合并回旧 `Map`。新增断言：

```java
assertEquals("HYBRID_EXTRA_CONTEXT", context.get().get("context_mode"));
assertTrue(context.get().containsKey("context_frame"));
assertTrue(context.get().containsKey("context_rendered_extra"));
```

- [ ] **Step 6: 运行 Runtime 相关测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.runtime.*"
```

Expected: `BUILD SUCCESSFUL`。

### Task 4.4: AIAgentService 初始化 ContextOrchestrator

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/AIAgentService.kt`
- Test: `app/src/test/java/com/hirain/aiagent/runtime/AgentRuntimeContextTest.java`

- [ ] **Step 1: 新增字段**

```kotlin
private lateinit var contextOrchestrator: ContextOrchestrator
```

同时新增 import：

```kotlin
import com.hirain.aiagent.context.ContextBuildInput
import com.hirain.aiagent.context.ContextMode
import com.hirain.aiagent.context.ContextOrchestrator
import com.hirain.aiagent.runtime.RuntimeCancelChecker
import com.hirain.aiagent.runtime.SystemTimeProvider
```

- [ ] **Step 2: 在 `onCreate()` 初始化 runtime 前创建 ContextOrchestrator**

```kotlin
contextOrchestrator = ContextOrchestrator.defaultForText(
    ContextBuildInput.builder()
        .mode(ContextMode.HYBRID_EXTRA_CONTEXT)
        .toolGroupRegistry(ToolGroupRegistry.defaultRegistry())
        .promptManager(promptManager!!)
        .memoryOrchestrator(memoryOrchestrator)
        .vehicleStatusProvider { statusProvider.getVehicleStatus() }
        .timeProvider(SystemTimeProvider())
        .build()
)
```

- [ ] **Step 3: 修改 AgentRuntime 初始化**

```kotlin
agentRuntime = AgentRuntime(
    AgentExecutor { userInput, context ->
        val requestedPersona = context["persona_id"] as? String ?: "chat"
        val persona = normalizeTextPersona(requestedPersona)
        if (persona != requestedPersona) {
            Log.w("TAG", "Unsupported TEXT persona=$requestedPersona, fallback to chat")
        }
        val personaContext = HashMap(context)
        personaContext["persona_id"] = persona
        textOrchestrators[persona]?.execute(userInput, personaContext)
            ?: chatOrchestrator.execute(userInput, personaContext)
    },
    contextOrchestrator,
    RuntimeCancelChecker { runtimeSession ->
        activeRequestRegistry.get(runtimeSession.requestId())?.isCancelled == true
    }
)
```

- [ ] **Step 4: 编译检查**

Run:

```powershell
.\gradlew.bat compileDebugUnitTestKotlin
```

Expected: `BUILD SUCCESSFUL`。

---

## Phase 5: Trace、取消检查和失败路径

### Task 5.1: 增加 Context Trace 属性

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/trace/TraceAttributeKeys.java`
- Create: `app/src/main/java/com/hirain/aiagent/context/ContextTraceRecorder.java`
- Test: `app/src/test/java/com/hirain/aiagent/context/ContextTraceRecorderTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.hirain.aiagent.context;

import com.hirain.aiagent.trace.TestTraceSupport;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ContextTraceRecorderTest {
    @Test
    public void record_writesContextAttributesToRootSpan() {
        TestTraceSupport.TestSession testSession = TestTraceSupport.redactedSession();
        ContextTraceRecorder recorder = new ContextTraceRecorder(testSession.traceSession.toTraceContext());

        recorder.record(true, ContextMode.HYBRID_EXTRA_CONTEXT,
                9, "RuntimeContextProvider,IntentContextProvider",
                2, "set_ac_status,set_ac_drive_temp",
                8, 123, false, 17L, null);
        testSession.close();

        SpanData root = testSession.exporter.spans.stream()
                .filter(span -> "agent.request".equals(span.getName()))
                .findFirst()
                .orElseThrow();
        assertEquals("HYBRID_EXTRA_CONTEXT", root.getAttributes()
                .get(AttributeKey.stringKey("agent.context.mode")));
        assertEquals(2L, root.getAttributes()
                .get(AttributeKey.longKey("agent.context.selected_tool_count")).longValue());
    }
}
```

- [ ] **Step 2: 增加 TraceAttributeKeys 常量**

```java
public static final String CONTEXT_ENABLED = "agent.context.enabled";
public static final String CONTEXT_MODE = "agent.context.mode";
public static final String CONTEXT_PROVIDER_COUNT = "agent.context.provider_count";
public static final String CONTEXT_PROVIDERS = "agent.context.providers";
public static final String CONTEXT_SELECTED_TOOL_COUNT = "agent.context.selected_tool_count";
public static final String CONTEXT_SELECTED_TOOL_NAMES = "agent.context.selected_tool_names";
public static final String CONTEXT_SECTION_COUNT = "agent.context.section_count";
public static final String CONTEXT_TOKEN_ESTIMATE = "agent.context.token_estimate";
public static final String CONTEXT_FALLBACK_USED = "agent.context.fallback_used";
public static final String CONTEXT_BUILD_MS = "agent.context.build_ms";
public static final String CONTEXT_ERROR = "agent.context.error";
```

- [ ] **Step 3: 实现 ContextTraceRecorder**

规则：

- `TraceContext` 或 `TraceSession` 为空时 no-op。
- 字符串字段用逗号拼接，不写入超长 JSON。
- provider error 只写摘要，不写堆栈。

- [ ] **Step 4: 在 ContextOrchestrator.build() 末尾记录 trace**

记录字段：

```java
enabled=true
mode=input.mode()
provider_count=providers.size()
providers=providerNames joined by comma
selected_tool_count=frame.selectedToolNames().size()
selected_tool_names=String.join(",", frame.selectedToolNames())
section_count=frame.sections().size()
token_estimate=frame.tokenEstimate()
fallback_used=debugInfo.fallbackUsed()
build_ms=elapsed
error=first provider error or null
```

- [ ] **Step 5: 运行 trace 测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.context.ContextTraceRecorderTest"
```

Expected: `BUILD SUCCESSFUL`。

### Task 5.2: Runtime 内部取消检查与 TEXT worker 终态抢占

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/runtime/AgentRuntime.java`
- Modify: `app/src/main/java/com/hirain/aiagent/runtime/RuntimeCancelChecker.java`
- Modify: `app/src/main/java/com/hirain/aiagent/AIAgentService.kt`
- Test: `app/src/test/java/com/hirain/aiagent/runtime/AgentRuntimeContextTest.java`
- Test: `app/src/test/java/com/hirain/aiagent/runtime/ActiveRequestRegistryTest.java`

- [ ] **Step 1: 保留现有构建前取消检查**

现有代码在 worker 内已有：

```kotlin
if (activeRequest.isCancelled) {
    activeTimeouts.remove(runtimeSession.requestId())?.let {
        mainHandler.removeCallbacks(it)
    }
    return@post
}
```

该逻辑保留。

- [ ] **Step 2: Runtime 内部在 Context build 后、AgentLoop 前检查取消**

`AgentRuntime.execute(session)` 中必须保持此顺序：

```java
ContextBuildResult contextBuildResult = contextOrchestrator.build(session);
ContextFrame contextFrame = contextBuildResult.frame();
if (cancelChecker.isCancelled(session)) {
    return RuntimeResult.cancelled(session.requestId(), session.sessionId(),
            session.userId(), session.personaId(), session.clientMessageId(),
            "cancelled_before_agent_loop", timeProvider.nowMillis());
}
AgentResult result = chatExecutor.execute(session, contextFrame);
```

设计原因：`ContextOrchestrator.build(session)` 在 Runtime 内部执行，Service 无法在这两个动作之间插入检查；因此 Runtime 需要只读取消检查器。

- [ ] **Step 3: 在 runtime.execute 返回后、tryComplete 前再检查取消**

插入位置：`val runtimeResult = agentRuntime.execute(runtimeSession)` 之后。

```kotlin
if (activeRequest.isCancelled) {
    activeTimeouts.remove(runtimeSession.requestId())?.let {
        mainHandler.removeCallbacks(it)
    }
    return@post
}
```

设计原因：Runtime 内部检查负责阻止 AgentLoop 启动；Service 返回后检查负责 suppress late success，取消响应仍由 `cancelAgentRequest()` 抢占并发送。

- [ ] **Step 4: 确认终态抢占仍由 ActiveRequestRegistry 控制**

不修改：

```kotlin
if (!runtimeResult.success() && runtimeResult.errorType() == "CANCELLED") {
    return@post
}
activeRequestRegistry.tryComplete(runtimeSession.requestId(), ActiveRequest.TerminalState.COMPLETED)
activeRequestRegistry.tryComplete(runtimeSession.requestId(), ActiveRequest.TerminalState.FAILED)
activeRequestRegistry.tryComplete(requestIdLocal, ActiveRequest.TerminalState.TIMEOUT)
```

- [ ] **Step 5: 运行 runtime 和 active request 测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.runtime.AgentRuntimeContextTest" --tests "com.hirain.aiagent.runtime.ActiveRequestRegistryTest"
```

Expected: `BUILD SUCCESSFUL`。

---

## Phase 6: 验收用例和非回归测试

### Task 6.1: ContextFrame 元信息验收测试

**Files:**
- Test: `app/src/test/java/com/hirain/aiagent/context/ContextOrchestratorTest.java`

- [ ] **Step 1: 增加 user/session/persona/clientMessageId 测试**

```java
@Test
public void build_preservesUserSessionPersonaAndClientMessageId() {
    RequestSession session = TestRequestSessions.textSession(
            "req-1", "conv-42", "driver-a", "concise", "client-42", "你好");
    ContextBuildResult result = ContextOrchestrator.defaultForText(
            ContextBuildInput.builder().mode(ContextMode.HYBRID_EXTRA_CONTEXT).build())
            .build(session);

    ContextFrame frame = result.frame();
    assertEquals("driver-a", frame.userId());
    assertEquals("conv-42", frame.sessionId());
    assertEquals("concise", frame.personaId());
    assertEquals("client-42", frame.clientMessageId());
}
```

- [ ] **Step 2: 增加 ContextFrame 包含 Intent/ToolGroup 测试**

```java
@Test
public void build_containsIntentToolGroupAndSelectedTools() {
    ContextFrame frame = ContextOrchestrator.defaultForText(
            ContextBuildInput.builder()
                    .mode(ContextMode.HYBRID_EXTRA_CONTEXT)
                    .toolGroupRegistry(ToolGroupRegistry.defaultRegistry())
                    .build())
            .build(TestRequestSessions.textSession(
                    "req-1", "conv-1", "user-a", "chat", "client-1", "打开空调"))
            .frame();

    assertEquals(IntentTag.VEHICLE_AC, frame.intentResult().intentTag());
    assertTrue(frame.selectedGroupIds().contains(ToolGroupId.AC_GROUP));
    assertEquals(List.of("set_ac_status"), frame.selectedToolNames());
}
```

- [ ] **Step 3: 运行 Context 测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.context.*"
```

Expected: `BUILD SUCCESSFUL`。

### Task 6.2: selectedToolNames 非全量工具验收

**Files:**
- Test: `app/src/test/java/com/hirain/aiagent/runtime/AgentRuntimeContextTest.java`

- [ ] **Step 1: 增加 runtimeWithContextCapture 测试辅助方法**

```java
private AgentRuntime runtimeWithContextCapture(AtomicReference<ContextFrame> frameRef) {
    ContextOrchestrator orchestrator = ContextOrchestrator.defaultForText(
            ContextBuildInput.builder()
                    .mode(ContextMode.HYBRID_EXTRA_CONTEXT)
                    .toolGroupRegistry(ToolGroupRegistry.defaultRegistry())
                    .build());
    AgentExecutor executor = new AgentExecutor() {
        @Override
        public AgentResult execute(String userInput, Map<String, Object> context) {
            return AgentResult.success("ok", 1, 1L, List.of());
        }

        @Override
        public AgentResult execute(RequestSession session, ContextFrame contextFrame) {
            frameRef.set(contextFrame);
            return AgentResult.success("ok", 1, 1L, List.of());
        }
    };
    return new AgentRuntime(executor, orchestrator, () -> "req-1", () -> 1000L);
}
```

该 helper 必须配套 import：

```java
import com.hirain.aiagent.context.ContextBuildInput;
import com.hirain.aiagent.context.ContextFrame;
import com.hirain.aiagent.context.ContextMode;
import com.hirain.aiagent.context.ContextOrchestrator;
import com.hirain.aiagent.core.AgentResult;
import com.hirain.aiagent.toolgroup.ToolGroupRegistry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
```

- [ ] **Step 2: 增加空调请求测试**

```java
@Test
public void acRequest_selectedToolsAreAcAndBasicOnly() {
    AtomicReference<ContextFrame> frameRef = new AtomicReference<>();
    AgentRuntime runtime = runtimeWithContextCapture(frameRef);
    AgentRequest request = new AgentRequest();
    request.setInputType("TEXT");
    request.setText("把空调打开");

    RequestSession session = runtime.startSession(request, null);
    RuntimeResult result = runtime.execute(session);

    assertTrue(result.success());
    assertTrue(session.toolGroupSelectionResult().selectedGroupIds().contains(ToolGroupId.AC_GROUP));
    assertFalse(session.toolGroupSelectionResult().selectedToolNames().contains("set_fl_window_status"));
    assertEquals(session.toolGroupSelectionResult().selectedToolNames(), frameRef.get().selectedToolNames());
}
```

- [ ] **Step 3: 增加车窗请求测试**

```java
@Test
public void windowRequest_selectedToolsAreWindowAndBasicOnly() {
    AtomicReference<ContextFrame> frameRef = new AtomicReference<>();
    AgentRuntime runtime = runtimeWithContextCapture(frameRef);
    AgentRequest request = new AgentRequest();
    request.setInputType("TEXT");
    request.setText("打开车窗");

    RequestSession session = runtime.startSession(request, null);
    RuntimeResult result = runtime.execute(session);

    assertTrue(result.success());
    assertTrue(session.toolGroupSelectionResult().selectedGroupIds().contains(ToolGroupId.WINDOW_GROUP));
    assertFalse(session.toolGroupSelectionResult().selectedToolNames().contains("set_ac_status"));
    assertEquals(session.toolGroupSelectionResult().selectedToolNames(), frameRef.get().selectedToolNames());
}
```

- [ ] **Step 4: 增加座椅请求测试**

```java
@Test
public void seatRequest_selectedToolsAreSeatAndBasicOnly() {
    AtomicReference<ContextFrame> frameRef = new AtomicReference<>();
    AgentRuntime runtime = runtimeWithContextCapture(frameRef);
    AgentRequest request = new AgentRequest();
    request.setInputType("TEXT");
    request.setText("打开座椅加热");

    RequestSession session = runtime.startSession(request, null);
    RuntimeResult result = runtime.execute(session);

    assertTrue(result.success());
    assertTrue(session.toolGroupSelectionResult().selectedGroupIds().contains(ToolGroupId.SEAT_GROUP));
    assertFalse(session.toolGroupSelectionResult().selectedToolNames().contains("set_ac_status"));
    assertEquals(session.toolGroupSelectionResult().selectedToolNames(), frameRef.get().selectedToolNames());
}
```

- [ ] **Step 5: 增加普通聊天不是全量工具测试**

```java
@Test
public void chatRequest_selectedToolsAreNotAllTools() {
    AtomicReference<ContextFrame> frameRef = new AtomicReference<>();
    AgentRuntime runtime = runtimeWithContextCapture(frameRef);
    AgentRequest request = new AgentRequest();
    request.setInputType("TEXT");
    request.setText("你好，今天心情不错");

    RequestSession session = runtime.startSession(request, null);
    RuntimeResult result = runtime.execute(session);

    assertTrue(result.success());
    assertTrue(session.toolGroupSelectionResult().selectedToolNames().size() < 47);
    assertEquals(session.toolGroupSelectionResult().selectedToolNames(), frameRef.get().selectedToolNames());
}
```

- [ ] **Step 6: 运行 runtime context 测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.runtime.AgentRuntimeContextTest"
```

Expected: `BUILD SUCCESSFUL`。

### Task 6.3: 不重复注入验收

**Files:**
- Test: `app/src/test/java/com/hirain/aiagent/context/ContextOrchestratorTest.java`
- Test: `app/src/test/java/com/hirain/aiagent/core/AgentLoopOrchestratorContextInjectionTest.java`

- [ ] **Step 1: 验证 renderedExtraContext 不包含重复上下文标记**

```java
@Test
public void renderedExtraContext_doesNotDuplicateExistingAgentLoopContexts() {
    ContextFrame frame = ContextOrchestrator.defaultForText(
            ContextBuildInput.builder()
                    .mode(ContextMode.HYBRID_EXTRA_CONTEXT)
                    .toolGroupRegistry(ToolGroupRegistry.defaultRegistry())
                    .vehicleStatusProvider(() -> "{\"speed\":0}")
                    .timeProvider(() -> 1000L)
                    .build())
            .build(TestRequestSessions.textSession(
                    "req-1", "conv-1", "user-a", "chat", "client-1", "你好"))
            .frame();

    String rendered = frame.renderedExtraContext();
    assertFalse(rendered.contains("当前时间："));
    assertFalse(rendered.contains("【长期记忆】"));
    assertFalse(rendered.contains("车辆状态："));
    assertFalse(rendered.contains("你是"));
}
```

- [ ] **Step 2: 运行测试**

同时复用 Phase 4 的 `AgentLoopOrchestratorContextInjectionTest`，确认进入 `ChatRequest.messages()` 的 Context 临时消息也不包含重复的长期记忆、车辆状态、当前时间或 system prompt 文本。

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.context.ContextOrchestratorTest" --tests "com.hirain.aiagent.core.AgentLoopOrchestratorContextInjectionTest"
```

Expected: `BUILD SUCCESSFUL`。

---

## Phase 7: 文档、阶段测试和人工验收

### Task 7.1: 更新阶段总结文档

**Files:**
- Create: `docs/act_summary/context-module-introduction-summary.md`

- [ ] **Step 1: 写入架构摘要**

文档必须包含：

```markdown
# Context 模块引入总结

## 本阶段目标

## 已完成范围

## 未进入本阶段的范围

## Runtime 接入链路

## HYBRID_EXTRA_CONTEXT 渲染边界

## 行为变更说明

- `ContextFrame.toOrchestratorContext()` 会把 `renderedExtraContext` 写入 `context_rendered_extra`。
- `ContextExtraPreProcessor` 读取 `context_rendered_extra`，并在 `HYBRID_EXTRA_CONTEXT` 首轮生成临时 `UserMessage`，使 Context 文本进入 `ChatRequest.messages()`。
- 如果调用方原本通过 `AgentRequest.extraContext["extra_context"]` 传入自定义值，本阶段不覆盖原值；同时保留一份到 `caller_extra_context` 便于排查。
- `RuntimeCancelChecker` 会在 Context build 后、AgentLoop 前检查取消状态；已取消请求不会调用 `chatExecutor.execute(...)`。

## 后续阶段候选项

- 完整 selected tool specification 渲染：在一期轻量工具说明稳定后，再评估是否将 `ToolRegistry` 或 `List<ToolSpecification>` 纳入 ContextBuildInput，渲染 selected tool 的 description 和参数 schema。

## Trace 字段

## 自动化测试结果

## 需要 App/车机手动验证的项目
```

- [ ] **Step 2: 记录真实测试命令和结果**

填写实际执行结果，不写“应该通过”。

### Task 7.2: 分阶段自动化测试

**Files:**
- No code changes.

- [ ] **Step 1: Context 单测**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.context.*"
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 2: Runtime 单测**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.runtime.*"
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 3: Trace 相关回归测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.trace.*" --tests "com.hirain.aiagent.core.AgentLoopOrchestratorTraceTest" --tests "com.hirain.aiagent.core.AgentLoopOrchestratorContextInjectionTest"
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 4: 全量 JVM 单测**

Run:

```powershell
.\gradlew.bat testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`。

如果失败：

- 编译失败：优先检查新增 context API 的 import、构造函数和 Kotlin 调用点。
- Runtime 旧测试失败：优先检查 `AgentExecutor` 默认方法是否保留旧 `execute(String, Map)` 行为。
- Trace 测试失败：优先检查新增 attribute key 是否写在 root span，而不是子 span。
- 网络或依赖下载失败：记录为环境问题，不把它归类为 context 代码失败。

### Task 7.3: App/手动验收清单

**Files:**
- Create: `docs/check_accept/context-module-manual-acceptance-checklist.md`

- [ ] **Step 1: 写入手动用例**

必须覆盖：

- TEXT 普通聊天：响应正常，Trace 有 `agent.context.enabled=true`。
- TEXT `chat` persona：响应正常，ChatMemory 不串到 `friendly` / `concise`。
- TEXT `friendly` persona：`ContextFrame.personaId=friendly`，响应风格仍来自原 persona prompt。
- TEXT `concise` persona：`ContextFrame.personaId=concise`，响应风格仍来自原 persona prompt。
- 会话切换：切换前后 `ContextFrame.sessionId` 正确变化。
- 空调请求：selected tools 只包含 AC 相关工具和基础状态组语义。
- 车窗请求：selected tools 只包含 WINDOW 相关工具和基础状态组语义。
- 座椅请求：selected tools 只包含 SEAT 相关工具和基础状态组语义。
- 普通聊天：selected tools 不是全量 47 个工具。
- Context 注入：TEXT 请求的模型消息中包含 `【运行时上下文】` / `【意图上下文】` / `【工具组上下文】`，不包含重复的长期记忆、车辆状态、当前时间或 system prompt 文本。
- 调用方 `extra_context`：如测试 App 传入 `AgentRequest.extraContext["extra_context"]`，Context 模块不覆盖原值，Trace/debug 中可见 `caller_extra_context`。
- 取消请求：`cancelAgentRequest` 能取消运行中 TEXT 请求；若取消发生在 Context build 后、AgentLoop 前，不调用 executor；若取消发生在 Runtime 返回后，worker 不发送 late success。
- timeout：仍只发送一次最终 TIMEOUT 响应。
- IMAGE：行为不因 context 模块改变。
- VOICE：旧链路行为不因 context 模块改变。
- CONTROL：行为不因 context 模块改变。

- [ ] **Step 2: 写入 Trace 期望字段**

```text
agent.context.enabled=true
agent.context.mode=HYBRID_EXTRA_CONTEXT
agent.context.provider_count=9
agent.context.providers=RuntimeContextProvider,...
agent.context.selected_tool_count=<本轮选中工具数>
agent.context.selected_tool_names=<逗号分隔 selectedToolNames>
agent.context.section_count=<section 数>
agent.context.token_estimate=<粗估 token>
agent.context.fallback_used=false 或 true
agent.context.build_ms=<正整数或 0>
agent.context.error=<无错误时为空>
```

---

## 8. 阶段验收标准

### Phase 1 验收

- `ContextMode`、`ContextSection`、`ContextFrame`、`ContextFrameBuilder`、`ContextBudgetManager` 单测通过。
- `ContextFrame` 中 requestId、userId、sessionId、personaId、clientMessageId 均来自 `RequestSession`。
- 不存在重新生成 ID 的逻辑。

### Phase 2 验收

- 9 个 provider 均可独立构建结果。
- ToolGroup provider 只渲染 selected group / selected tool，不渲染全量 47 个工具。
- Memory、Vehicle、Time、Prompt provider 在 HYBRID 模式下不参与 renderedExtraContext。

### Phase 3 验收

- `ContextOrchestrator.build(session)` 能返回 `ContextBuildResult.success`。
- provider 异常时可诊断 fallback，不无信息崩溃。
- `renderedExtraContext` 只包含 runtime、persona、intent、toolgroup 等兼容上下文。

### Phase 4 验收

- `AgentExecutor` 旧接口兼容。
- `AgentRuntime.execute(session)` 在 AgentLoop 前生成 `ContextFrame`。
- `ContextExtraPreProcessor` 能把 `context_rendered_extra` 注入首轮 `ChatRequest.messages()`。
- `AgentRuntime(AgentExecutor, ContextOrchestrator, IdGenerator, TimeProvider)` 四参构造函数存在，测试 helper 不需要使用六参全量构造函数。
- TEXT 请求执行结果和旧测试保持通过。

### Phase 5 验收

- Trace 可见 context enabled/mode/provider/tool/build/fallback/error 字段。
- Context 构建前已取消时 worker 不进入 Runtime；Context build 后、AgentLoop 前取消时 Runtime 不调用 executor；Runtime 返回后取消时 worker 不发送 late success。
- timeout、cancel、failure 仍由 `ActiveRequestRegistry.tryComplete` 统一抢占。

### Phase 6 验收

- chat/friendly/concise 三种 persona 均正常。
- 会话切换后 `ContextFrame.sessionId` 正确变化。
- 空调、车窗、座椅、普通聊天的 selected tools 符合 `ToolGroupSelectionResult`。
- `renderedExtraContext` 和实际 `ChatRequest.messages()` 都不重复注入 system prompt、persona prompt、memory、vehicle、time。

### Phase 7 验收

- 阶段总结文档和手动验收清单落盘。
- 相关 context/runtime/trace 测试和全量 JVM 测试有真实结果记录。
- IMAGE / VOICE / CONTROL 非回归需要通过 App/车机手动验收确认。

## 9. 推荐执行顺序

推荐严格按 Phase 1 -> Phase 2 -> Phase 3 -> Phase 4 -> Phase 5 -> Phase 6 -> Phase 7 执行。不要先改 `AIAgentService.kt`，因为 runtime 接入前如果没有 ContextFrame/provider/fallback 测试，失败时很难区分是 context 构建问题、executor 兼容问题，还是 Service 终态抢占问题。

最小可交付版本是：

1. Phase 1: Context 数据模型和预算。
2. Phase 2.2 + 2.3: runtime/persona/userInput/intent/toolgroup provider。
3. Phase 3: ContextOrchestrator HYBRID 渲染和 fallback。
4. Phase 4: AgentRuntime 兼容接入。
5. Phase 5.1: Context Trace 字段。
6. Phase 6.1 + 6.3: 元信息和不重复注入测试。

动态裁剪 LangChain4j 真实 `toolSpecifications` 不进入一期最小版本。当前 `AgentLoopOrchestrator` 在构造时确定 `effectiveToolSpecs`，若要做到本轮动态 tool spec 绑定，需要单独设计 `AgentExecutionInput` 或 per-request tool spec 传递机制，并评估对 `ModelCaller`、trace、tool execution record 的影响。

完整 selected tool specification 渲染也不进入一期最小版本。一期只渲染 selected group 描述和 selected tool names；后续阶段再决定是否把完整 `ToolSpecification.description` 和参数 schema 纳入 `renderedExtraContext`。
