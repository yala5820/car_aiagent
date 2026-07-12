# Memory Session ChatMemory 改造计划可行性审查

**审查对象：** `docs/plan_overall/2026-07-09-memory-session-chatmemory-implementation-plan.md`  
**审查日期：** 2026-07-09  
**审查方式：** 只读审查，未修改原计划与源码  

---

## 1. 总体结论

初版计划的主方向是正确的：它识别到了当前真实短期记忆仍固定在 `AgentLoopOrchestrator -> MessageWindowChatMemory -> PersistentChatMemorySqlite("ChatMemory")`，并计划切到 `sessionId` 级短期记忆、`userId` 级长期记忆，符合本轮目标。

但该计划目前还不建议直接交给实现人员执行。原因不是方向错误，而是计划中有几处关键边界没有收紧，尤其是：

- 缺失 `sessionId` 的解析结果没有作为一等运行时身份回写到 `RequestSession` / response / trace / context。
- 压缩写回只考虑了 store，没有明确同步 provider 缓存中的 live `ChatMemory`。
- `sessionId` 作为全局短期 key 后，与当前 user-scoped session 元数据表存在生命周期不一致风险。
- “fallback ChatMemory” 仍可能让 TEXT 主路径继续落回固定持久化 memory，削弱 session-scoped 改造。
- `MemoryPreProcessor` 的重复长期记忆注入治理放得偏晚。

因此建议结论是：**计划可行，但需要先修订后执行。**

---

## 2. 已确认的正确方向

### 2.1 使用 LangChain4j `ChatMemoryStore` 承接自研存储是合理的

计划选择继续使用 LangChain4j `MessageWindowChatMemory`，同时用 AIAgent 自己的 `SessionMemoryStore` 作为 `ChatMemoryStore`。这比自研完整 `ChatMemory` 更稳妥，因为本轮问题的根因不是 LangChain4j 抽象不适用，而是当前 store id 固定为 `"ChatMemory"`。

源码证据：

- `AgentLoopOrchestrator` 当前构造期固定创建 `chatMemory`，并用 `PersistentChatMemorySqlite(context, config.chatMemoryStoreId())` 持久化。
- `AgentConfigFactory.createTextPersona(...)` 当前仍把 `memoryId` 固定为 `"ChatMemory"`。
- `SessionMemoryStore` 已经实现 `ChatMemoryStore`，具备成为短期存储底座的基础。

补充核验：本地 Gradle 缓存中的 LangChain4j 1.16.3 确认 `MessageWindowChatMemory$Builder` 存在 `id(Object)`、`chatMemoryStore(...)`、`maxMessages(...)` 方法，因此计划中 `.id(memoryId)` 的 API 路线本身可行。

### 2.2 短期 `sessionId`、长期 `userId`、persona 不参与 key 的设计合理

在座舱 demo 场景中，`userId` 表示本轮发言人；同一 `sessionId` 代表同一车内共同语境。短期记忆按 `sessionId` 共享、长期记忆按 `userId` 读写，是目前最清晰的归属模型。

计划中这部分目标表达清楚，尤其是明确：

- 短期 memory id 只使用 `sessionId`。
- 不同 `userId`、不同 `personaId` 在同一 session 中共享短期上下文。
- 长期记忆继续按当前发言人 `userId` 提取和写入。
- `personaId` 不参与任何 memory key。

### 2.3 允许 AgentLoop 极窄接线是必要的

完全不改 `AgentLoopOrchestrator` 无法让真实模型可见的 `ChatMemory` 改变。计划允许 AgentLoop 只做“按 session 获取 ChatMemory、speaker 标记、SystemMessage 去持久化”的窄接线，符合本轮边界。

---

## 3. 阻塞问题

### P0-1：缺失 `sessionId` 的解析结果没有贯穿运行时身份链路

计划默认策略是：TEXT 请求缺少 `sessionId` 时，由 Memory 层使用当前 active session；没有 active session 时创建新 session，不写入 `"default_session"`。

这个策略方向可以接受，但计划没有说明解析后的 `resolvedSessionId` 如何回写到：

- `RequestSession.sessionId()`
- `RequestSession.orchestratorContext()`
- `ContextFrame.sessionId()`
- `RuntimeResult.sessionId()`
- `AgentResponse.sessionId`
- trace root span / context attributes

当前源码中，`RequestSessionFactory` 对缺失 `sessionId` 的处理是 `emptyToNull(request.getSessionId())`，只在非空时放入 `orchestratorContext`。`RuntimeResponseMapper` 又直接把 `RuntimeResult.sessionId()` 写入 `AgentResponse`。如果只在 `AgentLoopOrchestrator` 或 `MemoryOrchestrator` 内部临时 resolve session，调用方和后续 Context 阶段仍然看不到真实 session。

风险：

- 第一次缺少 sessionId 的请求可能实际写入某个 active/generated session，但 response 仍返回 null。
- 后续调用方无法知道应该继续传哪个 sessionId。
- Context 阶段读取 `ContextFrame.sessionId()` 时仍可能是空值。
- trace 上无法可靠判断本轮短期记忆归属。

建议修订：

1. 在进入 `ContextOrchestrator.build(session)` 之前完成 `sessionId` 解析，优先放在 `AgentRuntime.startSession(...)` 或 `RequestSessionFactory` 前后的显式步骤。
2. 新增 `resolvedSessionId` 后，应构造或返回带 resolved sessionId 的 `RequestSession`，并写入 `orchestratorContext`。
3. `RuntimeResult`、`AgentResponse`、trace 必须使用 resolved sessionId。
4. 测试必须覆盖：请求 `sessionId=null`，响应中仍能拿到实际 sessionId，且第二轮能继续使用该 session。

### P0-2：压缩写回没有同步 live `ChatMemory` 缓存

计划新增 `SessionChatMemoryProvider`，并用 LRU 缓存 `sessionId -> ChatMemory`。但计划中 `MemoryOrchestrator.onTurnComplete(userId, sessionId, ...)` 的核心仍是“压缩后写回 store”。这会遗漏一个关键点：如果 provider 缓存中仍持有同一个 `MessageWindowChatMemory` 实例，直接调用 `sessionStore.updateMessages(memoryId, compressed)` 并不必然改变该 live `ChatMemory` 实例的内存消息列表。

当前源码已有类似问题：`MemoryOrchestrator.onTurnComplete(...)` 压缩后只调用 `sessionStore.updateMessages(...)`，而主链路读的是另一个 `ChatMemory`。改造后如果仍只是写 store，会变成同一 store 下的缓存一致性问题。

风险：

- 压缩写入 SQLite 成功，但下一轮 `provider.getOrCreate(sessionId)` 返回缓存中的旧 `ChatMemory`。
- 模型仍看到未压缩历史，压缩对真实上下文不生效。
- `MemorySnapshot` 从 store 读取到压缩结果，但 AgentLoop 从 cached ChatMemory 读取到未压缩结果，两者再次分裂。

建议修订：

1. 压缩后不要只调用 `sessionStore.updateMessages(...)`。
2. `SessionChatMemoryProvider` 应提供原子方法，例如：
   - `replaceMessages(sessionId, compressedMessages)`
   - 或 `setMessages(sessionId, compressedMessages)`
3. 该方法必须同时更新 live `ChatMemory` 和底层 store。
4. 如果 LangChain4j `ChatMemory.set(...)` 可用，应优先调用 `chatMemory.set(compressed)`，由 LangChain4j 同步 store。
5. 增加测试：触发压缩后，不重新创建 provider，下一轮 `getOrCreate(sessionId).messages()` 必须直接返回压缩后的消息。

### P0-3：纯 `sessionId` 短期 key 与 user-scoped session 元数据生命周期不一致

计划把 `session_messages.memory_id` 改为纯 `sessionId`，但 `sessions` 表仍以 `(user_id, session_id)` 为主键，`createActiveSession`、`activateSession`、`deleteSession` 也仍按 user 维度管理。

这会产生一个必须提前定义的设计问题：如果同一个 `sessionId` 是车内共享会话，那么 session metadata 是否也应该是共享会话级别？如果继续保留每个 user 一条 session metadata，则可能出现：

- user_a 和 user_b 都有 `(user_id, same_session_id)` 的 metadata 行。
- 删除 user_a 的会话时，计划会精确删除 `session_messages WHERE memory_id=sessionId`，导致 user_b 的 metadata 仍存在，但短期消息被删。
- list/switch active session 仍是 user-scoped，但短期 ChatMemory 是 global session-scoped，两者生命周期语义不一致。

这个问题不是说纯 `sessionId` key 错了，而是计划必须明确 session metadata 的归属模型。

建议修订：

至少需要在计划中补一个明确不变量：

```text
sessionId 是全局共享会话 id，不是 user-local id。
短期消息表按 sessionId 全局存储。
user -> active session 只是每个发言人的当前指针，不代表 session 消息所有权。
删除 session 消息必须确认是删除全局共享会话，而不是只删除某个 user 的指针。
```

并补充删除策略选择：

- 如果 `deleteConversation(userId, sessionId)` 只是删除该用户列表中的会话指针，则不能删除 `session_messages`。
- 如果它表示删除全局共享会话，则应删除所有 user 的相关 metadata 行，而不是只删当前 user 的 `(user_id, session_id)`。

当前计划没有做这个选择，直接执行会留下数据一致性风险。

### P0-4：fallback ChatMemory 仍可能成为 TEXT 正常路径

计划 Task 4.1 中的示例代码：

```java
ChatMemory chatMemory = memoryOrchestrator != null && sessionId != null
        ? memoryOrchestrator.chatMemoryForSession(sessionId)
        : fallbackChatMemory;
```

这和计划前文“缺失 sessionId 时不得静默写入 default_session，而应使用 active session 或创建新 session”的策略不完全一致。只要 `extraContext` 里缺少 `session_id`，TEXT 主路径就可能继续落回构造期固定的 fallback memory。Task 4.2 虽然把 fallback id 改为 `"FallbackChatMemory"`，但它仍然是一个持久化 ChatMemory。

风险：

- 只要调用方漏传 sessionId，真实短期记忆再次绕过 session-scoped store。
- fallback memory 会累积多会话历史，变成新的隐藏共享池。
- 测试如果只覆盖有 sessionId 的路径，会漏掉这个回归。

建议修订：

1. TEXT 主路径不应直接使用 fallbackChatMemory。
2. 应先调用 `resolveSessionId(userId, requestedSessionId)`，保证得到非空 resolved sessionId。
3. 只有 `memoryPolicy == EPHEMERAL/NONE` 或明确的 legacy 非 TEXT 路径才允许 fallback。
4. 增加测试：TEXT 请求缺失 sessionId 时，不允许访问 `FallbackChatMemory`，必须进入 resolved active session。

---

## 4. 需要修订但不阻塞方向的问题

### P1-1：`MemoryPreProcessor` 去重治理放得偏晚

计划把 `SystemMessage` 改成本轮 transient 后，仍要到 Phase 5.2 才停用 `MemoryPreProcessor`。在这段中间状态里，长期记忆会同时通过：

- transient `SystemMessage`
- `MemoryPreProcessor` 生成的 `UserMessage("【用户记忆参考】...")`

进入同一次模型请求。虽然不再持久化到短期 store，但仍然重复注入，浪费 token，也会影响行为。

建议将 `MemoryPreProcessor` 从 TEXT 主路径移除的任务提前到 Task 4.0 同阶段，作为“长期记忆唯一注入点”的一部分，而不是 Phase 5 才处理。

### P1-2：`SessionChatMemoryProvider` 的 `maxMessages=50` 硬编码削弱配置边界

计划在 `MemoryOrchestrator` 构造中写：

```java
this.sessionChatMemoryProvider = new SessionChatMemoryProvider(sessionStore, 50);
```

这与 `AgentConfig.maxMemoryMessages()` 的配置边界不一致。当前 TEXT 恰好也是 50，但之后如果不同 persona 或不同场景需要不同窗口，MemoryOrchestrator 会变成隐藏配置源。

建议：

- `SessionChatMemoryProvider.getOrCreate(sessionId, maxMessages)`；
- 或按 memory policy / persona 注册 provider；
- 至少在计划中说明本轮只支持 TEXT 统一 50 条，并增加后续扩展点。

### P1-3：`MemorySnapshot` 预留接口过于依赖当前压缩格式

计划的 `extractExistingSummary(...)` 只扫描 `UserMessage` 且要求文本以 `【对话摘要】` 开头。当前 `MemoryCompressor` 的确用 `UserMessage.from(SUMMARY_PREFIX + summary)`，但这不是一个稳定的数据模型。

建议：

- `MemorySnapshot.summary()` 可以先保留，但应标注为 best-effort。
- 后续如果摘要要成为 Context 的稳定输入，应在 `session_messages` 或独立表中保存结构化 summary metadata。
- 本轮不要让 Context 或业务测试依赖 summary 一定存在。

### P1-4：真实 SQLite 行为验证不足

计划大量使用 JVM 单测和 fake `ChatMemoryStore`，这对 provider 逻辑有价值。但 `SessionMemoryStore` 是 Android `SQLiteOpenHelper`，其 DB version、onUpgrade、delete 行为、`INSERT OR REPLACE` 等不会被 JVM fake store 覆盖。

建议：

- 保留 fake store 单测。
- 增加 instrumentation 测试或明确写入手工 DB 验证步骤。
- 至少对以下行为做设备/模拟器验证：
  - v2 -> v3 upgrade 不崩溃。
  - `deleteSession` 在选定语义下不会留下 metadata/message 不一致。
  - `session_messages.memory_id=sessionId` 可被 LangChain4j ChatMemory 正常读写。

### P1-5：人工验收用例可能被长期记忆干扰

计划已经提醒“测试暗号可能被长期记忆提取”。这是对的，但人工验收仍需要更严格区分：

- 测短期隔离时，应禁用 MemoryExtractor 或使用不会进入长期记忆的临时表达。
- 测长期记忆时，应明确等待/确认提取完成，并检查写入 user_a/user_b 的 store。

建议在验收文档里把“短期隔离验收”和“长期记忆验收”分成两组，不要用同一自然语言问题同时验证两者。

---

## 5. 需要你确认的设计点

### 5.1 `deleteConversation(userId, sessionId)` 的语义

这是目前最需要产品/架构确认的点。

如果同一 `sessionId` 是共享座舱会话，那么删除操作到底表示：

1. 删除该 user 的会话列表入口，但保留全局 session messages；
2. 删除整个共享 session，包括所有 user 的 metadata 和 messages；
3. demo 阶段仅支持当前 user 管理，不考虑跨 user list/delete 一致性。

计划当前倾向于“删除当前 user metadata + 删除全局 session messages”，这是最容易产生悬挂 metadata 的方案，不建议保留。

### 5.2 缺失 `sessionId` 时是否允许自动创建 session

计划默认采用 active session / 自动创建策略。这个策略对 demo 友好，但必须接受一个结果：服务端会生成业务 sessionId，调用方必须从 response 或 conversation API 中拿到它继续使用。

如果调用方已经保证每轮都有 sessionId，那么更严格的策略是直接返回参数错误。两者都可以，但计划必须固定一个，不应同时保留两套未落地分支。

---

## 6. 建议修订顺序

建议在实施前按以下顺序修订计划：

1. **新增 Phase 0：身份模型硬门槛**
   - 明确 sessionId 是全局共享会话 id。
   - 明确 user-scoped metadata 与 session-scoped messages 的删除/list/switch 语义。
   - 明确缺失 sessionId 的策略。
   - 明确 resolvedSessionId 必须进入 RequestSession、ContextFrame、RuntimeResult、AgentResponse、trace。

2. **提前处理长期记忆注入唯一性**
   - Task 4.0 中同时移除 TEXT 主路径 `MemoryPreProcessor`。
   - transient SystemMessage 成为本轮唯一长期记忆注入点。

3. **修订压缩写回接口**
   - `MemoryOrchestrator` 不直接只写 store。
   - 通过 provider 同步 live ChatMemory 和 store。
   - 添加缓存一致性测试。

4. **禁止 TEXT fallback 正常生效**
   - 先 resolve sessionId，再获取 session ChatMemory。
   - fallback 只用于 legacy 或非持久化策略。

5. **补充真实 SQLite 验证**
   - 对 DB version、delete、session_messages 主键语义做 instrumentation 或手工验证。

---

## 7. 最终评审意见

该计划不是错误计划，反而已经抓住了本轮 memory 改造的核心：**让真实 ChatMemory 主路径按 sessionId 选择，并让长期记忆继续按 userId 工作。**

但它目前还存在几个执行级漏洞，尤其是 resolved sessionId 贯穿、压缩缓存一致性、session metadata 生命周期语义这三点。如果不先修订，后续很可能出现“测试中 session memory 生效，但真实响应、Context、删除、压缩又各走一套状态”的问题。

我的建议是：**先要求下属修订计划，再批准进入实现。**

