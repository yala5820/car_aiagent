# Memory Session ChatMemory 最终验收审查报告

**审查日期：** 2026-07-09  
**审查对象：** 下属基于最新修改意见完成的 Memory Session ChatMemory 改造修复结果  
**审查方式：** 源码链路复查 + 上轮问题回归确认 + JVM 单测 + Debug 构建  
**验收结论：** 按当前 Demo 阶段仅验收 TEXT / memory 主路径的边界，本轮没有剩余 P0。最新修复关闭了上一轮两个核心问题；VOICE / SCENE 路径当前不纳入本轮 memory 主路径验收，且未来预计删除，因此不再作为阻塞项统计。当前仍建议处理 2 个 P1 和 1 个 P2 后再关闭本轮改造。

---

## 一、已确认修复的内容

### 1. `extraContext.session_id` 覆盖 canonical sessionId 的问题已修复

**位置：**

- `app/src/main/java/com/hirain/aiagent/runtime/RequestSessionFactory.java:83`
- `app/src/main/java/com/hirain/aiagent/runtime/RequestSessionFactory.java:88`
- `app/src/main/java/com/hirain/aiagent/runtime/RequestSessionFactory.java:94`
- `app/src/main/java/com/hirain/aiagent/runtime/RequestSessionFactory.java:100`

当前写入顺序为：

1. 先合并 caller `extraContext`
2. 再合并 trace context
3. 最后写入 `user_id`、`persona_id`、`client_message_id`、`session_id`

这保证 Runtime 解析出的 canonical `sessionId` 不会再被调用方传入的 `extraContext["session_id"]` 覆盖。对应测试 `AgentRuntimeResolvedSessionTest.extraContextSessionIdDoesNotOverrideCanonicalResolvedSessionId()` 已存在，并在本轮 JVM 单测中通过。

### 2. scene 的 EPHEMERAL memory policy 不再被 sessionId 强校验直接拦截

**位置：**

- `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java:132`
- `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java:133`
- `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java:147`
- `app/src/main/java/com/hirain/aiagent/core/factory/AgentConfigFactory.java:108`

当前 `AgentLoopOrchestrator` 只在 `MemoryPolicy.PERSISTENT` 时要求 `extraContext["session_id"]`，`EPHEMERAL` 和 `NONE` 会继续使用构造时的 fallback ChatMemory。这关闭了上一轮指出的 scene 被 sessionId 强校验误伤的问题。

### 3. 删除会话时 cache/store 顺序已有改善

**位置：**

- `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java:224`
- `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java:230`
- `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java:231`

当前 `deleteSession()` 先调用 `sessionStore.deleteSession(...)`，成功后再清理 `SessionChatMemoryProvider` 缓存。相比上一轮“先清缓存再删 store”的实现，已经避免了 store 删除失败但缓存先被清掉的主要不一致风险。

---

## 二、本轮不纳入阻塞验收的问题

### 1. VOICE 路径缺少 `session_id`

**位置：**

- `app/src/main/java/com/hirain/aiagent/AIAgentService.kt:738`
- `app/src/main/java/com/hirain/aiagent/AIAgentService.kt:742`
- `app/src/main/java/com/hirain/aiagent/AIAgentService.kt:743`
- `app/src/main/java/com/hirain/aiagent/core/factory/AgentConfigFactory.java:60`
- `app/src/main/java/com/hirain/aiagent/core/factory/AgentConfigFactory.java:71`
- `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java:132`
- `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java:137`
- `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java:140`

**问题说明：**

`handleVoiceRequest()` 手动构造的 `ctx` 只有：

```kotlin
"user_id" to userId
"persona_id" to personaId
trace context
```

没有 `session_id`。但它调用的是：

```kotlin
chatOrchestrator.execute(request.text ?: "", ctx)
```

`chatOrchestrator` 来自 `AgentConfigFactory.createChatPersona(...)`，其 `memoryPolicy` 是 `PERSISTENT`。因此进入 `AgentLoopOrchestrator` 后会走 session-scoped ChatMemory 主路径，并在缺少 `session_id` 时返回：

```text
resolved sessionId is required before AgentLoop execution
```

**影响：**

- VOICE 请求会稳定失败，无法进入模型调用。
- 该问题不会被当前 `testDebugUnitTest` 暴露，因为没有真实测试覆盖 `handleVoiceRequest()` 或 VOICE 直连 `chatOrchestrator` 的路径。

**本轮判断：**

当前项目处于 Demo 阶段，本轮 memory 改造验收范围聚焦 TEXT 主路径；VOICE 暂不参与当前 Demo，未来预计删除。因此该问题不再作为本轮 memory 主路径验收的 P0，但如果后续重新启用 VOICE，必须在启用前修复。

**建议：**

- 方案 A：VOICE 与 TEXT 一样统一走 `AgentRuntime.startSession()` 和 `AgentRuntime.execute()`，让 Runtime 负责解析 sessionId。
- 方案 B：若暂时不迁移 Runtime，则在 `handleVoiceRequest()` 中调用 `memoryOrchestrator.resolveSessionId(...)`，并把解析结果写入 `ctx["session_id"]`。
- 无论选择哪种方案，都应补充回归测试：VOICE 的 PERSISTENT chat persona 必须拿到 resolved sessionId 后再进入 AgentLoop。

### 2. SCENE 路径会受到 system prompt 选择问题影响

**位置：**

- `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java:111`
- `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java:117`
- `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java:177`
- `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java:375`
- `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java:376`
- `app/src/main/java/com/hirain/aiagent/core/factory/AgentConfigFactory.java:103`
- `app/src/main/java/com/hirain/aiagent/core/factory/AgentConfigFactory.java:105`
- `app/src/main/java/com/hirain/aiagent/prompt/PromptConstants.java:58`
- `app/src/main/java/com/hirain/aiagent/prompt/PromptConstants.java:61`

**问题说明：**

`AgentConfig` 本身已经保存了每个人格的 `systemPromptTemplateName()`，例如 scene persona 配置的是：

```java
.systemPromptTemplateName(PromptConstants.SYSTEM_ASSISTANT_SCENE)
```

但当前 `AgentLoopOrchestrator.buildSystemPromptMessage(...)` 没有使用 `config.systemPromptTemplateName()`，而是执行：

```java
String templateName = PromptConstants.textPersonaTemplateName(personaId);
```

scene 调用处是：

```kotlin
sceneOrchestrator.execute("", mapOf("scene" to scene))
```

这个 `extraContext` 没有 `persona_id`，所以 `personaId` 会默认成 `"chat"`，最终 `textPersonaTemplateName("chat")` 返回默认对话提示词，而不是 `system/assistant_scene`。

**影响：**

- scene Agent 会使用默认聊天助手 system prompt，而不是场景服务专用 prompt。
- `AgentConfig.systemPromptTemplateName()` 在 AgentLoop 主路径中被绕过，后续新增 persona 时也可能被错误 prompt 驱动。
- 这属于真实运行语义回归，不是单纯文档或测试覆盖问题。

**本轮判断：**

SCENE 当前不参与 Demo 主验收，未来预计删除，因此该问题不再作为本轮阻塞项。但这里暴露出的通用设计问题仍建议保留为 P2：`AgentLoopOrchestrator` 不应绕过 `AgentConfig.systemPromptTemplateName()`。

**建议：**

- `buildSystemPromptMessage()` 应优先使用 `config.systemPromptTemplateName()`。
- 如果需要根据 TEXT 的发言人格动态切换 prompt，应在 `AgentConfigFactory.createTextPersona(...)` 或 Runtime 选择 config 时完成，而不是在 AgentLoop 内绕过 config。
- 补充测试：scene config 的 system prompt 模板必须来自 `SYSTEM_ASSISTANT_SCENE`，不得因 `extraContext` 缺少 `persona_id` 退回默认 chat prompt。

---

## 三、剩余需要处理的问题

### P1-1：显式 sessionId 创建 metadata 时仍忽略 `createActiveSession()` 返回值

**位置：**

- `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java:176`
- `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java:185`
- `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java:187`
- `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java:189`
- `app/src/main/java/com/hirain/aiagent/memory/SessionMemoryStore.java:148`
- `app/src/main/java/com/hirain/aiagent/memory/SessionMemoryStore.java:165`

**问题说明：**

当请求带显式 `requestedSessionId` 且当前 user 下没有 metadata 时，`resolveSessionId()` 会调用：

```java
sessionStore.createActiveSession(normalizedUserId, trimmed, title, personaId, sourceApp);
sessionManager.cacheSession(normalizedUserId, trimmed);
return trimmed;
```

但 `SessionMemoryStore.createActiveSession(...)` 失败时会返回 `false`。当前代码没有检查这个返回值，仍然缓存并返回该 sessionId。

**影响：**

SQLite 写入失败时会出现“Runtime 认为 session 已解析并激活，但 metadata 实际不存在”的分裂状态。后续 `listConversations()`、`switchConversation()`、删除和 active session 查询可能与 AgentLoop 使用的短期消息不一致。

**建议：**

- 检查 `createActiveSession()` 返回值。
- 失败时不要 `cacheSession()`，并返回明确错误或抛出可映射的异常。
- 补充 FakeStore/可替身测试，模拟 `createActiveSession=false` 时不得返回成功 sessionId。

### P1-2：关键 TEXT / memory 主路径约束测试仍是空测试，`testDebugUnitTest` 通过不能证明主路径正确

**位置：**

- `app/src/test/java/com/hirain/aiagent/core/AgentLoopOrchestratorSessionMemoryTest.java:42`
- `app/src/test/java/com/hirain/aiagent/core/AgentLoopOrchestratorSessionMemoryTest.java:49`
- `app/src/test/java/com/hirain/aiagent/memory/SessionMemoryStoreDeleteTest.java:16`

**问题说明：**

以下测试方法只有注释，没有任何断言：

- `textPathFailsWhenSessionIdMissing()`
- `textPathUsesSessionMemoryNotFallback()`

同时 `SessionMemoryStoreDeleteTest` 整个类仍为 `@Ignore`。

**影响：**

本轮 `testDebugUnitTest` 能通过，但无法证明：

- PERSISTENT 缺少 sessionId 时真的返回预期错误。
- PERSISTENT 有 sessionId 时真的走 `memoryOrchestrator.chatMemoryForSession(...)`。
- SQLite 全局删除真的删除所有 user metadata 和共享短期消息。

**建议：**

- 对无法在 JVM 构造完整 `AgentLoopOrchestrator` 的路径，迁移到 `androidTest` 或拆出可测试的纯 Java 策略函数。
- 空测试不应保留为 `@Test`，否则会制造“测试已覆盖”的假象。

### P2-1：`AgentLoopOrchestrator` 构建 system prompt 时没有优先使用 `AgentConfig.systemPromptTemplateName()`

**位置：**

- `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java:375`
- `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java:376`
- `app/src/main/java/com/hirain/aiagent/core/AgentConfig.java:77`

**问题说明：**

`AgentConfig` 已经保存了 `systemPromptTemplateName()`，但 `AgentLoopOrchestrator.buildSystemPromptMessage(...)` 当前仍通过 `PromptConstants.textPersonaTemplateName(personaId)` 自行推导模板。对当前 TEXT demo 来说，这不一定立即产生错误；但从架构上看，AgentLoop 应该尊重配置对象，而不是绕过配置重新推导。

**影响：**

- 当前 TEXT 主路径影响有限，因此不作为 P0/P1。
- 后续如果新增或保留非 TEXT persona，可能再次出现配置已声明但运行时没有使用的偏差。
- 这会削弱 `AgentConfigFactory` 作为 persona 配置集中入口的可信度。

**建议：**

- `buildSystemPromptMessage()` 优先使用 `config.systemPromptTemplateName()`。
- 若 TEXT 需要按 `personaId` 动态切换模板，应在创建或选择 `AgentConfig` 时完成，而不是在 AgentLoop 内二次推导。

---

## 四、验证结果

本轮已实际执行以下命令。

### JVM 单元测试

```powershell
.\gradlew.bat testDebugUnitTest --rerun-tasks
```

结果：

```text
BUILD SUCCESSFUL in 16s
24 actionable tasks: 24 executed
```

说明：命令通过，但存在上文所述空测试和 `@Ignore` 覆盖缺口。

### Debug 构建

```powershell
.\gradlew.bat assembleDebug --rerun-tasks
```

结果：

```text
BUILD SUCCESSFUL in 14s
38 actionable tasks: 38 executed
```

构建期间存在既有 Manifest 重复权限 warning，以及 `libc++_shared.so` 无法 strip 的提示；这些不是本轮 memory 改造的阻塞问题。

---

## 五、最终验收判断

本轮最新修复不是无效修复：`extraContext.session_id` 覆盖 canonical sessionId 的问题已经关闭，scene 被 session 强校验直接拦截的问题也已经关闭，删除顺序也有改善。

在当前 Demo 阶段，如果验收范围明确限定为 TEXT / memory 主路径，并且 VOICE / SCENE 不参与当前验收且未来预计删除，则本轮不再保留 P0 阻塞问题。剩余问题为：

1. P1：显式 sessionId metadata 创建失败时仍可能缓存不存在的 active session。
2. P1：关键 TEXT / memory 主路径行为测试仍是空测试，现有测试通过不能证明核心 memory 主路径安全。
3. P2：AgentLoop 构建 system prompt 时绕过 `AgentConfig.systemPromptTemplateName()`，当前 TEXT demo 影响有限，但建议收敛。

建议先处理两个 P1，再补齐至少一组可执行回归测试；P2 可顺手修复。之后重新执行 `testDebugUnitTest --rerun-tasks`、`assembleDebug --rerun-tasks`，再关闭本轮 memory 改造。
