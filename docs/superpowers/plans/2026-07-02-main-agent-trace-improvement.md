# Main Agent Trace Improvement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a safer, standardized, and more complete trace system for the text-based main Agent path, covering AgentLoop, prompt assembly, memory operations, LLM calls, tool calls, and final responses.

**Architecture:** Keep OpenTelemetry Java SDK and the existing `TraceManager` / `TraceSession` facade, but add a thin domain trace layer that owns span names, attribute keys, redaction, and message formatting. The text request entry creates one root trace; `AgentLoopOrchestrator` delegates all prompt/LLM/tool/memory trace writes to that layer instead of scattering raw `span.setAttribute()` calls.

**Tech Stack:** Android app module, Kotlin/Java, LangChain4j, OpenTelemetry Java SDK 1.48.0, JUnit unit tests, Gradle `:app:testDebugUnitTest`.

---

## Scope

Included:

- Text input path through `processAgentRequest(AgentRequest)` and `handleTextRequest()`.
- Main `AgentLoopOrchestrator`.
- Prompt rendering and final assembled `ChatRequest` messages.
- LLM request/response, token usage, tool-call decisions.
- Tool request/arguments/result/safety veto.
- Memory extraction and compression operations.
- Trace safety bugs: missing trace context, missing redaction hookup, error status, span close safety.

Excluded for this phase:

- `IMAGE` / VLM direct image question path.
- VR/TTS lifecycle tracing.
- Scene Agent and camera-triggered proactive service tracing.
- Android UI/RUM trace, ANR, Activity lifecycle, slow frame metrics.

## File Structure

Create:

- `app/src/main/java/com/hirain/aiagent/trace/TraceAttributeKeys.java`
  - Central attribute key constants for request, agent, prompt, LLM, tool, memory, and error data.
- `app/src/main/java/com/hirain/aiagent/trace/TraceSpanNames.java`
  - Central span name constants.
- `app/src/main/java/com/hirain/aiagent/trace/TraceAttributeWriter.java`
  - Redaction-aware attribute writer used by all trace code.
- `app/src/main/java/com/hirain/aiagent/trace/TraceMessageFormatter.java`
  - Converts LangChain4j messages and tool specs into bounded trace strings.
- `app/src/main/java/com/hirain/aiagent/trace/AgentTraceRecorder.java`
  - Main high-level API for AgentLoop trace spans.
- `app/src/test/java/com/hirain/aiagent/trace/TraceRedactorTest.java`
  - Tests for text, phone, base64, and truncation redaction.
- `app/src/test/java/com/hirain/aiagent/trace/TraceMessageFormatterTest.java`
  - Tests for safe message formatting and truncation.

Modify:

- `app/src/main/java/com/hirain/aiagent/trace/TraceConfig.java`
  - Add content capture policy and make redaction default safe.
- `app/src/main/java/com/hirain/aiagent/trace/TraceRedactor.java`
  - Fix phone-number replacement and keep truncation behavior deterministic.
- `app/src/main/java/com/hirain/aiagent/trace/TraceManager.java`
  - Use `TraceAttributeWriter`; include request metadata in root span.
- `app/src/main/java/com/hirain/aiagent/trace/TraceSession.java`
  - Add safe child span helpers, exception recording, and root attributes.
- `app/src/main/java/com/hirain/aiagent/trace/TracingOkHttpInterceptor.java`
  - Use stable HTTP-ish keys and set error status on failures.
- `app/src/main/java/com/hirain/aiagent/AIAgentService.kt`
  - Start text request root trace with `AgentRequest` metadata and close safely.
- `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java`
  - Remove direct low-level span writes; use `AgentTraceRecorder`.
- `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java`
  - Accept optional trace recorder/session for memory operation spans.
- `app/src/main/java/com/hirain/aiagent/memory/MemoryExtractor.java`
  - Trace extraction prompt/result/count/error.
- `app/src/main/java/com/hirain/aiagent/memory/MemoryCompressor.java`
  - Trace compression decision/prompt/result/error.
- `docs/act_summary/trace-system-evaluation-report.md`
  - Update after implementation to record actual fixed status.

---

## Phase 1: Safety Bugs and Trace Policy

### Task 1: Add Trace Constants

**Files:**

- Create: `app/src/main/java/com/hirain/aiagent/trace/TraceSpanNames.java`
- Create: `app/src/main/java/com/hirain/aiagent/trace/TraceAttributeKeys.java`

- [ ] **Step 1: Create span name constants**

Create `TraceSpanNames.java`:

```java
package com.hirain.aiagent.trace;

public final class TraceSpanNames {
    private TraceSpanNames() {}

    public static final String AGENT_REQUEST = "agent.request";
    public static final String AGENT_LOOP = "agent.loop";
    public static final String PROMPT_ASSEMBLY = "prompt.assembly";
    public static final String LLM_CALL = "gen_ai.chat";
    public static final String TOOL_EXECUTE = "tool.execute";
    public static final String MEMORY_EXTRACT = "memory.extract";
    public static final String MEMORY_COMPRESS = "memory.compress";
    public static final String RESPONSE_DISPATCH = "response.dispatch";
}
```

- [ ] **Step 2: Create attribute key constants**

Create `TraceAttributeKeys.java`:

```java
package com.hirain.aiagent.trace;

public final class TraceAttributeKeys {
    private TraceAttributeKeys() {}

    public static final String REQUEST_ID = "request.id";
    public static final String SESSION_ID = "session.id";
    public static final String SOURCE_APP = "source.app";
    public static final String INPUT_TYPE = "input.type";
    public static final String USER_INPUT = "user.input";
    public static final String USER_INPUT_LENGTH = "user.input.length";

    public static final String AGENT_PERSONA = "agent.persona";
    public static final String AGENT_ITERATION = "agent.iteration";
    public static final String AGENT_MAX_ITERATIONS = "agent.max_iterations";

    public static final String PROMPT_SYSTEM = "prompt.system";
    public static final String PROMPT_SYSTEM_LENGTH = "prompt.system.length";
    public static final String PROMPT_TRANSIENT_MESSAGES = "prompt.transient_messages";
    public static final String PROMPT_CHAT_MESSAGES = "prompt.chat_messages";
    public static final String PROMPT_MESSAGE_COUNT = "prompt.message_count";
    public static final String PROMPT_TOOL_SPEC_COUNT = "prompt.tool_spec_count";
    public static final String PROMPT_TOOL_SPECS = "prompt.tool_specs";

    public static final String GEN_AI_PROVIDER = "gen_ai.provider.name";
    public static final String GEN_AI_REQUEST_MODEL = "gen_ai.request.model";
    public static final String GEN_AI_USAGE_INPUT_TOKENS = "gen_ai.usage.input_tokens";
    public static final String GEN_AI_USAGE_OUTPUT_TOKENS = "gen_ai.usage.output_tokens";
    public static final String GEN_AI_USAGE_TOTAL_TOKENS = "gen_ai.usage.total_tokens";
    public static final String GEN_AI_OUTPUT = "gen_ai.output";
    public static final String GEN_AI_TOOL_CALLS = "gen_ai.tool_calls";

    public static final String TOOL_NAME = "tool.name";
    public static final String TOOL_ARGUMENTS = "tool.arguments";
    public static final String TOOL_OUTPUT = "tool.output";
    public static final String TOOL_SUCCESS = "tool.success";
    public static final String TOOL_SAFETY_VETOED = "tool.safety_vetoed";
    public static final String TOOL_SAFETY_REASON = "tool.safety_reason";

    public static final String MEMORY_OPERATION = "memory.operation";
    public static final String MEMORY_INPUT_CHARS = "memory.input_chars";
    public static final String MEMORY_OUTPUT_CHARS = "memory.output_chars";
    public static final String MEMORY_CANDIDATE_COUNT = "memory.candidate_count";
    public static final String MEMORY_COMPRESSED = "memory.compressed";

    public static final String RESPONSE_SUCCESS = "response.success";
    public static final String RESPONSE_ERROR_TYPE = "response.error_type";
    public static final String RESPONSE_TEXT = "response.text";
    public static final String RESPONSE_TEXT_LENGTH = "response.text.length";

    public static final String ERROR_TYPE = "error.type";
    public static final String ERROR_MESSAGE = "error.message";
}
```

- [ ] **Step 3: Run Java compile check**

Run:

```powershell
.\gradlew.bat :app:compileDebugJavaWithJavac
```

Expected: task succeeds or reports only pre-existing unrelated Android/Kotlin errors.

### Task 2: Add Redaction-Aware Attribute Writer

**Files:**

- Modify: `app/src/main/java/com/hirain/aiagent/trace/TraceConfig.java`
- Modify: `app/src/main/java/com/hirain/aiagent/trace/TraceRedactor.java`
- Create: `app/src/main/java/com/hirain/aiagent/trace/TraceAttributeWriter.java`
- Test: `app/src/test/java/com/hirain/aiagent/trace/TraceRedactorTest.java`

- [ ] **Step 1: Fix phone redaction replacement**

Replace the current `redactPhone` implementation with:

```java
private String redactPhone(String text) {
    return text.replaceAll(
            "((?:\\+86)?1[3-9]\\d)\\d{4}(\\d{4})",
            "$1****$2");
}
```

This preserves the first three digits and last four digits, and fixes the current replacement that references a missing capture group.

- [ ] **Step 2: Extend `TraceConfig` with content capture policy**

Add enum and accessors:

```java
public enum ContentCaptureMode {
    OFF,
    REDACTED,
    FULL_DEBUG
}

private final ContentCaptureMode contentCaptureMode;

public ContentCaptureMode contentCaptureMode() { return contentCaptureMode; }
public boolean captureContent() { return contentCaptureMode != ContentCaptureMode.OFF; }
```

Builder defaults:

```java
private ContentCaptureMode contentCaptureMode = ContentCaptureMode.REDACTED;

public Builder contentCaptureMode(ContentCaptureMode v) {
    this.contentCaptureMode = v;
    return this;
}
```

Development preset:

```java
.redactSensitive(true)
.contentCaptureMode(ContentCaptureMode.REDACTED)
```

Production preset:

```java
return builder()
        .enabled(false)
        .redactSensitive(true)
        .contentCaptureMode(ContentCaptureMode.OFF)
        .build();
```

- [ ] **Step 3: Create `TraceAttributeWriter`**

```java
package com.hirain.aiagent.trace;

import io.opentelemetry.api.trace.Span;

public class TraceAttributeWriter {

    private final TraceConfig config;
    private final TraceRedactor redactor;

    public TraceAttributeWriter(TraceConfig config) {
        this.config = config;
        this.redactor = new TraceRedactor();
    }

    public void putText(Span span, String key, String value) {
        if (span == null || value == null) return;
        if (!config.captureContent()) return;
        span.setAttribute(key, normalize(value));
    }

    public void putArgument(Span span, String key, String value) {
        if (span == null || value == null) return;
        if (!config.captureContent()) return;
        if (config.contentCaptureMode() == TraceConfig.ContentCaptureMode.FULL_DEBUG) {
            span.setAttribute(key, value);
        } else {
            span.setAttribute(key, redactor.redactArguments(value));
        }
    }

    public void putResult(Span span, String key, String value) {
        if (span == null || value == null) return;
        if (!config.captureContent()) return;
        if (config.contentCaptureMode() == TraceConfig.ContentCaptureMode.FULL_DEBUG) {
            span.setAttribute(key, value);
        } else {
            span.setAttribute(key, redactor.redactResult(value));
        }
    }

    public void putString(Span span, String key, String value) {
        if (span != null && value != null) span.setAttribute(key, value);
    }

    public void putLong(Span span, String key, long value) {
        if (span != null) span.setAttribute(key, value);
    }

    public void putBoolean(Span span, String key, boolean value) {
        if (span != null) span.setAttribute(key, value);
    }

    private String normalize(String value) {
        if (config.contentCaptureMode() == TraceConfig.ContentCaptureMode.FULL_DEBUG) {
            return value;
        }
        return redactor.redactUserInput(value);
    }
}
```

- [ ] **Step 4: Add unit tests for redaction**

Create `TraceRedactorTest.java`:

```java
package com.hirain.aiagent.trace;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TraceRedactorTest {

    @Test
    public void redactsChinesePhoneNumber() {
        TraceRedactor redactor = new TraceRedactor();
        String out = redactor.redactUserInput("我的手机号是13812345678");
        assertFalse(out.contains("13812345678"));
        assertTrue(out.contains("****"));
    }

    @Test
    public void truncatesLongUserInput() {
        TraceRedactor redactor = new TraceRedactor();
        String input = "a".repeat(700);
        String out = redactor.redactUserInput(input);
        assertTrue(out.length() < input.length());
        assertTrue(out.contains("truncated"));
    }

    @Test
    public void truncatesBase64Arguments() {
        TraceRedactor redactor = new TraceRedactor();
        String args = "{\"image\":\"/9j/" + "a".repeat(300) + "\"}";
        String out = redactor.redactArguments(args);
        assertTrue(out.contains("base64 truncated"));
        assertFalse(out.equals(args));
    }
}
```

- [ ] **Step 5: Run tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.hirain.aiagent.trace.TraceRedactorTest"
```

Expected: PASS.

### Task 3: Fix Optional Trace Context Safety

**Files:**

- Modify: `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java`

- [ ] **Step 1: Add safe extraction helper**

Add private method:

```java
private static TraceSession extractTraceSession(Map<String, Object> extraContext) {
    if (extraContext == null) return null;
    Object value = extraContext.get(TraceContext.TRACE_CONTEXT_KEY);
    if (!(value instanceof TraceContext)) return null;
    TraceContext traceContext = (TraceContext) value;
    return traceContext.isActive() ? traceContext.session() : null;
}
```

- [ ] **Step 2: Replace direct cast**

Replace:

```java
TraceSession traceSession = extraContext != null
        ? ((TraceContext) extraContext.get(TraceContext.TRACE_CONTEXT_KEY)).session()
        : null;
```

With:

```java
TraceSession traceSession = extractTraceSession(extraContext);
```

- [ ] **Step 3: Verify compile**

Run:

```powershell
.\gradlew.bat :app:compileDebugJavaWithJavac
```

Expected: no new Java compile error.

---

## Phase 2: Standard Root Trace for Text Requests

### Task 4: Enrich Root Span With AgentRequest Metadata

**Files:**

- Modify: `app/src/main/java/com/hirain/aiagent/trace/TraceManager.java`
- Modify: `app/src/main/java/com/hirain/aiagent/trace/TraceSession.java`
- Modify: `app/src/main/java/com/hirain/aiagent/AIAgentService.kt`

- [ ] **Step 1: Add request-aware session creation overload**

In `TraceManager.java`, add:

```java
public TraceSession startAgentRequest(
        String personaId,
        String userId,
        String requestId,
        String sessionId,
        String sourceApp,
        String inputType,
        String userInput) {

    TraceSession session = startSession(personaId, userId, userInput);
    session.setAttribute(TraceAttributeKeys.REQUEST_ID, requestId);
    session.setAttribute(TraceAttributeKeys.SESSION_ID, sessionId);
    session.setAttribute(TraceAttributeKeys.SOURCE_APP, sourceApp);
    session.setAttribute(TraceAttributeKeys.INPUT_TYPE, inputType);
    session.setAttribute(TraceAttributeKeys.USER_INPUT_LENGTH,
            userInput != null ? userInput.length() : 0L);
    return session;
}
```

- [ ] **Step 2: Make `TraceSession` expose tracer and null-safe root attributes**

```java
public Tracer tracer() {
    return tracer;
}

public void setAttribute(String key, String value) {
    if (key != null && value != null) {
        rootSpan.setAttribute(key, value);
    }
}
```

- [ ] **Step 3: Use request-aware creation in `handleTextRequest()`**

Replace current `startSession("chat", ...)` with:

```kotlin
val userId = request.sessionId ?: "default_user"
val session = traceManager.startAgentRequest(
    "chat",
    userId,
    request.requestId,
    request.sessionId,
    request.sourceApp,
    request.inputType,
    message
)
```

- [ ] **Step 4: Record response dispatch span and root attributes before callback**

Before `notifyAIAgentListeners(...)`, create and close a response span:

```kotlin
val responseText = if (result.isSuccess) result.output() else (result.errorDetail() ?: "请求失败")
val responseSpan = session.tracer().spanBuilder("response.dispatch").startSpan()
try {
    responseSpan.setAttribute("response.success", result.isSuccess)
    responseSpan.setAttribute("response.text.length", responseText.length.toLong())
    if (!result.isSuccess) {
        responseSpan.setAttribute("response.error_type", result.errorType()?.name ?: "UNKNOWN")
    }
} finally {
    responseSpan.end()
}
```

Also keep root-level summary attributes:

```kotlin
session.setAttribute("response.success", if (result.isSuccess) "true" else "false")
session.setAttribute("response.text.length", (if (result.isSuccess) result.output() else result.errorDetail()).orEmpty().length.toLong())
if (!result.isSuccess) {
    session.setAttribute("response.error_type", result.errorType()?.name)
}
```

- [ ] **Step 5: Compile**

Run:

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

Expected: Kotlin compile succeeds or only reports pre-existing unrelated errors.

---

## Phase 3: AgentLoop, Prompt, LLM, and Tool Trace Recorder

### Task 5: Add Message Formatter

**Files:**

- Create: `app/src/main/java/com/hirain/aiagent/trace/TraceMessageFormatter.java`
- Test: `app/src/test/java/com/hirain/aiagent/trace/TraceMessageFormatterTest.java`

- [ ] **Step 1: Create formatter**

```java
package com.hirain.aiagent.trace;

import java.util.List;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

public final class TraceMessageFormatter {

    private static final int MAX_TEXT = 1200;

    public String formatMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(formatMessage(messages.get(i)));
        }
        sb.append("]");
        return truncate(sb.toString());
    }

    public String formatToolSpecs(List<ToolSpecification> specs) {
        if (specs == null || specs.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < specs.size(); i++) {
            if (i > 0) sb.append(",");
            ToolSpecification spec = specs.get(i);
            sb.append("{\"name\":\"").append(escape(spec.name())).append("\"}");
        }
        sb.append("]");
        return truncate(sb.toString());
    }

    private String formatMessage(ChatMessage msg) {
        if (msg instanceof SystemMessage) {
            return json("system", ((SystemMessage) msg).text());
        }
        if (msg instanceof UserMessage) {
            return json("user", safeUserText((UserMessage) msg));
        }
        if (msg instanceof AiMessage) {
            AiMessage ai = (AiMessage) msg;
            if (ai.hasToolExecutionRequests()) {
                return json("assistant_tool_calls", ai.toolExecutionRequests().toString());
            }
            return json("assistant", ai.text());
        }
        if (msg instanceof ToolExecutionResultMessage) {
            ToolExecutionResultMessage tool = (ToolExecutionResultMessage) msg;
            return json("tool:" + tool.toolName(), tool.text());
        }
        return json(msg.type().toString(), msg.toString());
    }

    private String safeUserText(UserMessage msg) {
        try {
            return msg.singleText();
        } catch (Exception e) {
            return msg.toString();
        }
    }

    private String json(String role, String text) {
        return "{\"role\":\"" + escape(role) + "\",\"content\":\"" + escape(text) + "\"}";
    }

    private String truncate(String text) {
        if (text == null) return "";
        return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) + "...(truncated)" : text;
    }

    private String escape(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
```

- [ ] **Step 2: Add formatter tests**

```java
package com.hirain.aiagent.trace;

import org.junit.Test;

import java.util.List;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

import static org.junit.Assert.assertTrue;

public class TraceMessageFormatterTest {

    @Test
    public void formatsSystemAndUserMessages() {
        TraceMessageFormatter formatter = new TraceMessageFormatter();
        String out = formatter.formatMessages(List.of(
                SystemMessage.from("system prompt"),
                UserMessage.from("hello")
        ));
        assertTrue(out.contains("\"role\":\"system\""));
        assertTrue(out.contains("\"role\":\"user\""));
        assertTrue(out.contains("hello"));
    }

    @Test
    public void truncatesLongMessagePayload() {
        TraceMessageFormatter formatter = new TraceMessageFormatter();
        String out = formatter.formatMessages(List.of(UserMessage.from("a".repeat(3000))));
        assertTrue(out.contains("truncated"));
        assertTrue(out.length() < 1400);
    }
}
```

- [ ] **Step 3: Run tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.hirain.aiagent.trace.TraceMessageFormatterTest"
```

Expected: PASS.

### Task 6: Add AgentTraceRecorder

**Files:**

- Create: `app/src/main/java/com/hirain/aiagent/trace/AgentTraceRecorder.java`
- Modify: `app/src/main/java/com/hirain/aiagent/trace/TraceSession.java`

- [ ] **Step 1: Add writer access to `TraceSession`**

Modify constructor to receive writer:

```java
private final TraceAttributeWriter writer;

TraceSession(Span rootSpan, Tracer tracer, TraceAttributeWriter writer) {
    this.rootSpan = rootSpan;
    this.traceId = rootSpan.getSpanContext().getTraceId();
    this.tracer = tracer;
    this.writer = writer;
    this.scope = rootSpan.makeCurrent();
}

public TraceAttributeWriter writer() { return writer; }
public Tracer tracer() { return tracer; }
```

Update `TraceManager` creation:

```java
TraceAttributeWriter writer = new TraceAttributeWriter(config);
TraceSession session = new TraceSession(rootSpan, tracer, writer);
```

- [ ] **Step 2: Create recorder class**

```java
package com.hirain.aiagent.trace;

import java.util.List;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;

public class AgentTraceRecorder {

    private final TraceSession session;
    private final TraceAttributeWriter writer;
    private final TraceMessageFormatter formatter = new TraceMessageFormatter();

    public AgentTraceRecorder(TraceSession session) {
        this.session = session;
        this.writer = session != null ? session.writer() : null;
    }

    public boolean enabled() {
        return session != null;
    }

    public Span startPromptAssembly(
            String persona,
            int iteration,
            List<ChatMessage> transientMessages,
            List<ChatMessage> chatMessages,
            List<ToolSpecification> toolSpecs) {
        if (!enabled()) return null;
        Span span = session.tracer().spanBuilder(TraceSpanNames.PROMPT_ASSEMBLY).startSpan();
        writer.putString(span, TraceAttributeKeys.AGENT_PERSONA, persona);
        writer.putLong(span, TraceAttributeKeys.AGENT_ITERATION, iteration);
        writer.putLong(span, TraceAttributeKeys.PROMPT_MESSAGE_COUNT,
                (transientMessages == null ? 0 : transientMessages.size()) +
                (chatMessages == null ? 0 : chatMessages.size()));
        writer.putLong(span, TraceAttributeKeys.PROMPT_TOOL_SPEC_COUNT,
                toolSpecs == null ? 0 : toolSpecs.size());
        writer.putText(span, TraceAttributeKeys.PROMPT_TRANSIENT_MESSAGES,
                formatter.formatMessages(transientMessages));
        writer.putText(span, TraceAttributeKeys.PROMPT_CHAT_MESSAGES,
                formatter.formatMessages(chatMessages));
        writer.putText(span, TraceAttributeKeys.PROMPT_TOOL_SPECS,
                formatter.formatToolSpecs(toolSpecs));
        return span;
    }

    public Span startLlmCall(String modelName, int iteration, int inputMessageCount) {
        if (!enabled()) return null;
        Span span = session.tracer().spanBuilder(TraceSpanNames.LLM_CALL).startSpan();
        writer.putString(span, TraceAttributeKeys.GEN_AI_PROVIDER, "dashscope");
        writer.putString(span, TraceAttributeKeys.GEN_AI_REQUEST_MODEL, modelName);
        writer.putLong(span, TraceAttributeKeys.AGENT_ITERATION, iteration);
        writer.putLong(span, TraceAttributeKeys.PROMPT_MESSAGE_COUNT, inputMessageCount);
        return span;
    }

    public void enrichLlmResponse(Span span, ChatResponse response) {
        if (span == null || response == null) return;
        try {
            if (response.aiMessage() != null) {
                writer.putResult(span, TraceAttributeKeys.GEN_AI_OUTPUT,
                        response.aiMessage().text());
                if (response.aiMessage().hasToolExecutionRequests()) {
                    writer.putString(span, TraceAttributeKeys.GEN_AI_TOOL_CALLS,
                            response.aiMessage().toolExecutionRequests().toString());
                }
            }
            if (response.tokenUsage() != null) {
                writer.putLong(span, TraceAttributeKeys.GEN_AI_USAGE_INPUT_TOKENS,
                        response.tokenUsage().inputTokenCount());
                writer.putLong(span, TraceAttributeKeys.GEN_AI_USAGE_OUTPUT_TOKENS,
                        response.tokenUsage().outputTokenCount());
                writer.putLong(span, TraceAttributeKeys.GEN_AI_USAGE_TOTAL_TOKENS,
                        response.tokenUsage().totalTokenCount());
            }
        } catch (Exception e) {
            recordException(span, e);
        }
    }

    public Span startTool(ToolExecutionRequest request, int iteration) {
        if (!enabled()) return null;
        Span span = session.tracer().spanBuilder(TraceSpanNames.TOOL_EXECUTE).startSpan();
        writer.putLong(span, TraceAttributeKeys.AGENT_ITERATION, iteration);
        writer.putString(span, TraceAttributeKeys.TOOL_NAME, request.name());
        writer.putArgument(span, TraceAttributeKeys.TOOL_ARGUMENTS, request.arguments());
        return span;
    }

    public void finishTool(Span span, String output, boolean success, boolean vetoed, String vetoReason) {
        if (span == null) return;
        writer.putBoolean(span, TraceAttributeKeys.TOOL_SUCCESS, success);
        writer.putBoolean(span, TraceAttributeKeys.TOOL_SAFETY_VETOED, vetoed);
        writer.putString(span, TraceAttributeKeys.TOOL_SAFETY_REASON, vetoReason);
        writer.putResult(span, TraceAttributeKeys.TOOL_OUTPUT, output);
    }

    public void recordException(Span span, Exception e) {
        if (span == null || e == null) return;
        span.recordException(e);
        span.setStatus(StatusCode.ERROR, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        writer.putString(span, TraceAttributeKeys.ERROR_TYPE, e.getClass().getName());
        writer.putString(span, TraceAttributeKeys.ERROR_MESSAGE, e.getMessage());
    }
}
```

- [ ] **Step 3: Compile**

Run:

```powershell
.\gradlew.bat :app:compileDebugJavaWithJavac
```

Expected: no new Java compile errors.

### Task 7: Replace Raw AgentLoop Span Code With Recorder

**Files:**

- Modify: `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java`

- [ ] **Step 1: Create recorder near trace session extraction**

```java
TraceSession traceSession = extractTraceSession(extraContext);
AgentTraceRecorder trace = new AgentTraceRecorder(traceSession);
```

Import:

```java
import com.hirain.aiagent.trace.AgentTraceRecorder;
```

- [ ] **Step 2: Wrap prompt assembly**

After `transientMessages` is prepared and before `ChatRequest` build:

```java
Span promptSpan = trace.startPromptAssembly(
        config.personaId(),
        i,
        transientMessages,
        chatMemory.messages(),
        effectiveToolSpecs);
if (promptSpan != null) promptSpan.end();
```

- [ ] **Step 3: Replace `startLlmSpan` call**

Replace:

```java
Span llmSpan = traceSession != null
        ? traceSession.startLlmSpan("qwen", allMessages.size())
        : null;
```

With:

```java
Span llmSpan = trace.startLlmCall(config.modelName(), i, allMessages.size());
```

If `AgentConfig` does not yet expose `modelName()`, add Task 8 before this step.

- [ ] **Step 4: Replace `enrichLlmSpan` use**

Replace:

```java
enrichLlmSpan(llmSpan, aiMessage, response);
```

With:

```java
trace.enrichLlmResponse(llmSpan, response);
```

- [ ] **Step 5: Record model exceptions**

Change LLM call try/finally to:

```java
try {
    response = config.modelCaller().call(request);
    aiMessage = response.aiMessage();
    trace.enrichLlmResponse(llmSpan, response);
} catch (Exception e) {
    trace.recordException(llmSpan, e);
    throw e;
} finally {
    if (llmScope != null) llmScope.close();
    if (llmSpan != null) llmSpan.end();
}
```

- [ ] **Step 6: Replace tool span creation and finish**

Use:

```java
Span toolSpan = trace.startTool(toolReq, i);
Scope toolScope = toolSpan != null ? toolSpan.makeCurrent() : null;
```

After result is computed:

```java
trace.finishTool(
        toolSpan,
        result,
        !verdict.isVetoed(),
        verdict.isVetoed(),
        verdict.isVetoed() ? verdict.reason() : null);
```

In catch blocks inside tool execution:

```java
catch (Exception e) {
    trace.recordException(toolSpan, e);
    throw e;
}
```

- [ ] **Step 7: Remove old `enrichLlmSpan()` method**

Delete the private static `enrichLlmSpan` method after all calls are replaced.

- [ ] **Step 8: Compile**

Run:

```powershell
.\gradlew.bat :app:compileDebugJavaWithJavac
```

Expected: no new Java compile errors.

### Task 8: Add Model Name to AgentConfig

**Files:**

- Modify: `app/src/main/java/com/hirain/aiagent/core/AgentConfig.java`
- Modify: `app/src/main/java/com/hirain/aiagent/core/factory/AgentConfigFactory.java`

- [ ] **Step 1: Add `modelName` field and getter in `AgentConfig`**

Add field:

```java
private final String modelName;
```

Set in constructor from builder:

```java
this.modelName = b.modelName;
```

Add getter:

```java
public String modelName() { return modelName; }
```

Add builder field and method:

```java
private String modelName = "unknown";

public Builder modelName(String modelName) {
    this.modelName = modelName;
    return this;
}
```

- [ ] **Step 2: Set chat model name in factory**

In chat persona builder:

```java
.modelName("qwen-turbo")
```

Do not expand trace implementation into scene or vision paths in this phase. Their builders may keep the default `"unknown"` model name unless compile or lint rules require explicit values.

- [ ] **Step 3: Compile**

Run:

```powershell
.\gradlew.bat :app:compileDebugJavaWithJavac
```

Expected: no new Java compile errors.

- [ ] **Step 4: Optional non-scope cleanup if explicit values are required**

Only if the builder pattern, compile checks, or lint rules require every persona to declare a model name, add:

```java
.modelName("qwen-flash")     // scene persona
.modelName("qwen-vl-max")    // vision persona
```

This does not mean scene/vision tracing is in scope; it only prevents misleading `"unknown"` values if those personas are manually exercised.

---

## Phase 4: Memory Trace Coverage

### Task 9: Pass Trace Recorder Into MemoryOrchestrator

**Files:**

- Modify: `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java`
- Modify: `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java`
- Modify: `app/src/main/java/com/hirain/aiagent/memory/UserMemoryContext.java`

- [ ] **Step 1: Add overload in `MemoryOrchestrator`**

Add:

```java
public void onTurnComplete(String userId, List<ChatMessage> currentMessages,
                           int tokenEstimate, String userMessage, String aiResponse,
                           AgentTraceRecorder trace) {
    UserMemoryContext ctx = getUserContext(userId);
    ctx.extractAndStore(userMessage, aiResponse, trace);
    List<ChatMessage> compressed = ctx.compressIfNeeded(currentMessages, tokenEstimate, trace);
    if (compressed != currentMessages) {
        String memoryId = ctx.currentMemoryId();
        if (memoryId != null) {
            sessionStore.updateMessages(memoryId, compressed);
        }
    }
}
```

Keep existing method and delegate:

```java
public void onTurnComplete(String userId, List<ChatMessage> currentMessages,
                           int tokenEstimate, String userMessage, String aiResponse) {
    onTurnComplete(userId, currentMessages, tokenEstimate, userMessage, aiResponse, null);
}
```

- [ ] **Step 2: Update `UserMemoryContext` methods**

Add overloads:

```java
public void extractAndStore(String userMessage, String aiResponse, AgentTraceRecorder trace) {
    List<MemoryCandidate> candidates = extractor.extract(userMessage, aiResponse, trace);
    for (MemoryCandidate c : candidates) {
        longTermStore.upsert(userId, c);
    }
}

public List<ChatMessage> compressIfNeeded(List<ChatMessage> messages, int tokens, AgentTraceRecorder trace) {
    return compressor.compress(messages, tokens, trace);
}
```

Keep existing methods delegating with `null`.

- [ ] **Step 3: Pass recorder from `AgentLoopOrchestrator`**

Replace both `memoryOrchestrator.onTurnComplete(...)` calls with the overload including `trace`.

- [ ] **Step 4: Compile**

Run:

```powershell
.\gradlew.bat :app:compileDebugJavaWithJavac
```

Expected: no new Java compile errors.

### Task 10: Trace Memory Extraction

**Files:**

- Modify: `app/src/main/java/com/hirain/aiagent/memory/MemoryExtractor.java`
- Modify: `app/src/main/java/com/hirain/aiagent/trace/AgentTraceRecorder.java`

- [ ] **Step 1: Add memory span helpers to recorder**

```java
public Span startMemory(String operation, int inputChars) {
    if (!enabled()) return null;
    Span span = session.tracer().spanBuilder(
            "extract".equals(operation) ? TraceSpanNames.MEMORY_EXTRACT : TraceSpanNames.MEMORY_COMPRESS)
            .startSpan();
    writer.putString(span, TraceAttributeKeys.MEMORY_OPERATION, operation);
    writer.putLong(span, TraceAttributeKeys.MEMORY_INPUT_CHARS, inputChars);
    return span;
}

public void finishMemoryExtract(Span span, String prompt, String output, int candidateCount) {
    if (span == null) return;
    writer.putText(span, "memory.prompt", prompt);
    writer.putResult(span, "memory.output", output);
    writer.putLong(span, TraceAttributeKeys.MEMORY_CANDIDATE_COUNT, candidateCount);
}
```

- [ ] **Step 2: Add overload in `MemoryExtractor`**

```java
public List<MemoryCandidate> extract(String userMessage, String aiResponse, AgentTraceRecorder trace) {
    if (userMessage == null || userMessage.isEmpty()) return List.of();
    String prompt = extractPrompt
            .replace("{{user_message}}", userMessage)
            .replace("{{ai_response}}", aiResponse != null ? aiResponse : "");

    Span span = trace != null ? trace.startMemory("extract", prompt.length()) : null;
    try {
        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from(prompt))
                .build();
        ChatResponse response = extractModel.chat(request);
        String text = response.aiMessage().text().trim();
        List<MemoryCandidate> candidates = parseCandidates(text);
        if (trace != null) trace.finishMemoryExtract(span, prompt, text, candidates.size());
        return candidates;
    } catch (Exception e) {
        if (trace != null) trace.recordException(span, e);
        Log.e(TAG, "Extraction failed", e);
        return List.of();
    } finally {
        if (span != null) span.end();
    }
}
```

Keep existing method:

```java
public List<MemoryCandidate> extract(String userMessage, String aiResponse) {
    return extract(userMessage, aiResponse, null);
}
```

- [ ] **Step 3: Compile**

Run:

```powershell
.\gradlew.bat :app:compileDebugJavaWithJavac
```

Expected: no new Java compile errors.

### Task 11: Trace Memory Compression

**Files:**

- Modify: `app/src/main/java/com/hirain/aiagent/memory/MemoryCompressor.java`
- Modify: `app/src/main/java/com/hirain/aiagent/trace/AgentTraceRecorder.java`

- [ ] **Step 1: Add compression finish helper**

```java
public void finishMemoryCompress(Span span, boolean compressed, String prompt, String summary) {
    if (span == null) return;
    writer.putBoolean(span, TraceAttributeKeys.MEMORY_COMPRESSED, compressed);
    writer.putText(span, "memory.prompt", prompt);
    writer.putResult(span, "memory.summary", summary);
    writer.putLong(span, TraceAttributeKeys.MEMORY_OUTPUT_CHARS,
            summary != null ? summary.length() : 0L);
}
```

- [ ] **Step 2: Add overload to `MemoryCompressor.compress`**

```java
public List<ChatMessage> compress(List<ChatMessage> messages, int currentTokens, AgentTraceRecorder trace) {
    Span span = trace != null ? trace.startMemory("compress", currentTokens) : null;
    try {
        List<ChatMessage> result = compressInternal(messages, currentTokens, trace, span);
        return result;
    } catch (Exception e) {
        if (trace != null) trace.recordException(span, e);
        throw e;
    } finally {
        if (span != null) span.end();
    }
}
```

Refactor existing method:

```java
public List<ChatMessage> compress(List<ChatMessage> messages, int currentTokens) {
    return compress(messages, currentTokens, null);
}
```

Move current compression body to:

```java
private List<ChatMessage> compressInternal(
        List<ChatMessage> messages,
        int currentTokens,
        AgentTraceRecorder trace,
        Span span) {
    // existing body
}
```

- [ ] **Step 3: Trace summary prompt/result in `summarize`**

Change signature:

```java
private String summarize(List<ChatMessage> messages, AgentTraceRecorder trace, Span span)
```

Before return:

```java
if (trace != null) {
    trace.finishMemoryCompress(span, true, prompt, response.aiMessage().text().trim());
}
```

For no-compression exits:

```java
if (trace != null) trace.finishMemoryCompress(span, false, "", "");
```

- [ ] **Step 4: Compile**

Run:

```powershell
.\gradlew.bat :app:compileDebugJavaWithJavac
```

Expected: no new Java compile errors.

---

## Phase 5: HTTP and Error Normalization

### Task 12: Normalize HTTP Trace Attributes

**Files:**

- Modify: `app/src/main/java/com/hirain/aiagent/trace/TracingOkHttpInterceptor.java`

- [ ] **Step 1: Replace attribute names**

Change success attributes to:

```java
currentSpan.setAttribute("http.response.status_code", response.code());
currentSpan.setAttribute("http.request.method", request.method());
currentSpan.setAttribute("url.full", request.url().toString());
currentSpan.setAttribute("server.address", request.url().host());
currentSpan.setAttribute("http.duration_ms", durationMs);
```

- [ ] **Step 2: Set error status for IOException**

```java
currentSpan.recordException(e);
currentSpan.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, e.getMessage());
currentSpan.setAttribute(TraceAttributeKeys.ERROR_TYPE, e.getClass().getName());
```

- [ ] **Step 3: Set error status for 4xx/5xx client responses**

```java
if (response.code() >= 400) {
    currentSpan.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR);
    currentSpan.setAttribute(TraceAttributeKeys.ERROR_TYPE, String.valueOf(response.code()));
}
```

- [ ] **Step 4: Compile**

Run:

```powershell
.\gradlew.bat :app:compileDebugJavaWithJavac
```

Expected: no new Java compile errors.

---

## Phase 6: Verification and Documentation

### Task 13: Run Focused Verification

**Files:**

- No source edits.

- [ ] **Step 1: Run trace unit tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.hirain.aiagent.trace.*"
```

Expected: all trace unit tests pass.

- [ ] **Step 2: Run Java/Kotlin compile**

Run:

```powershell
.\gradlew.bat :app:compileDebugJavaWithJavac :app:compileDebugKotlin
```

Expected: compile succeeds, or failures are documented as pre-existing and unrelated.

- [ ] **Step 3: Manual Phoenix smoke test**

Run app on device with Phoenix available through adb reverse:

```powershell
adb reverse tcp:6006 tcp:6006
```

Send one `TEXT` `AgentRequest` through the existing test app or caller.

Expected Phoenix trace shape:

```text
agent.request
├── prompt.assembly
├── gen_ai.chat
├── tool.execute          only if LLM calls a tool
├── gen_ai.chat           only if tool result is fed back
├── memory.extract
└── response.dispatch
```

Expected root attributes:

```text
request.id
session.id
source.app
input.type = TEXT
response.success
response.text.length
```

Expected content attributes in redacted mode:

```text
user.input
prompt.chat_messages
gen_ai.output
tool.arguments
tool.output
memory.prompt
memory.output
```

### Task 14: Update Trace Documentation

**Files:**

- Modify: `docs/act_summary/trace-system-evaluation-report.md`
- Create: `docs/act_summary/main-agent-trace-improvement-summary.md`

- [ ] **Step 1: Update evaluation report status**

Add an implementation status section:

```markdown
## Implementation Follow-up

The text-based main Agent trace path has been standardized around:

- `agent.request` root span
- `prompt.assembly`
- `gen_ai.chat`
- `tool.execute`
- `memory.extract`
- `memory.compress`

IMAGE, scene Agent, Camera, VR/TTS remain intentionally out of scope for this demo phase.
```

- [ ] **Step 2: Create implementation summary**

Create `main-agent-trace-improvement-summary.md`:

```markdown
# Main Agent Trace Improvement Summary

## Scope

This phase covers the text-based main Agent path only.

## Trace Shape

agent.request -> prompt.assembly -> gen_ai.chat -> tool.execute -> memory.extract/memory.compress -> response.

## Safety Decisions

Content capture defaults to redacted mode. Full debug content is allowed only through explicit debug configuration.

## Verification

- `:app:testDebugUnitTest --tests "com.hirain.aiagent.trace.*"`
- `:app:compileDebugJavaWithJavac`
- `:app:compileDebugKotlin`
- Manual Phoenix smoke test with one TEXT request.
```

- [ ] **Step 3: Check git diff**

Run:

```powershell
git diff -- app/src/main/java/com/hirain/aiagent/trace app/src/main/java/com/hirain/aiagent/core app/src/main/java/com/hirain/aiagent/memory docs/act_summary
```

Expected: only trace-related implementation and documentation changes.

---

## Commit Plan

Use small commits:

1. `fix(trace): make trace context optional and safe`
2. `feat(trace): add standardized trace attributes and redaction writer`
3. `feat(trace): record prompt llm and tool spans through recorder`
4. `feat(trace): add memory extraction and compression spans`
5. `docs(trace): summarize main agent trace coverage`

Do not include unrelated README/AGENTS or virtual state machine changes unless the user explicitly asks.

## Acceptance Criteria

- Text requests always create one root `agent.request` trace.
- Missing trace context never crashes `AgentLoopOrchestrator`.
- Prompt assembly is visible: system/user/context messages and tool specs are recorded with bounded content.
- LLM calls record model name, provider, output, tool calls, token usage, HTTP status, and error status.
- Tool calls record name, arguments, safety veto, success, and output.
- Memory extraction and compression LLM calls are visible and tied to the same request trace.
- Sensitive content is redacted/truncated by default.
- Trace code is centralized in `trace/` helper classes instead of spread across business code.
- Unit tests cover redaction and message formatting.
- Gradle compile and focused trace tests pass or any unrelated pre-existing failures are documented.
