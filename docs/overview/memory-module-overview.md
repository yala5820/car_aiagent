# AIAgent 记忆模块概述

**编制日期：** 2026-07-09（Phase 6 更新）
**依据代码：** `app/src/main/java/com/hirain/aiagent/memory/` + `core/AgentLoopOrchestrator.java`
**上次修订：** Phase 0-5 完成短期记忆主路径 session-scoped 改造

---

## 一、关键设计结论

```
短期记忆：session-scoped，memoryId=sessionId，由 LangChain4j MessageWindowChatMemory + AIAgent SessionMemoryStore 共同实现。
长期记忆：user-scoped，读写 key=userId。
persona：不参与 memory key，只影响 prompt/template/行为策略。
sessionId：全局共享会话 id；user -> active session 只是当前发言人的指针。
缺失 sessionId：Runtime 在 Context 构建前解析为 resolvedSessionId，并写入 RequestSession/ContextFrame/Response/Trace。
Context：下一阶段负责决定本轮 prompt 中放入哪些 memory/context 内容；本轮只完成 memory 存储与 ChatMemory 主路径接线。
```

---

## 二、整体架构

记忆系统由 `MemoryOrchestrator` 统一对外暴露接口：

```
AgentLoopOrchestrator
       │
       ▼
MemoryOrchestrator  ←── 实现 SessionIdResolver（Phase 0 新增）
       │
       ├── SessionManager              第一层：会话生命周期管理
       ├── SessionMemoryStore           第一层：会话消息持久化（ChatMemoryStore 实现）
       ├── SessionChatMemoryProvider    第一层：session 级 ChatMemory 容器（Phase 3 新增）
       ├── MemorySnapshot               第一层：Context 预留只读快照（Phase 3 新增）
       ├── MemoryCompressor             第二层：Token 超限时 LLM 摘要压缩
       ├── MemoryExtractor              第三层：对话中提取长期记忆
       └── LongTermMemoryStore          第三层：长期记忆持久化
```

辅助工具类：

```
SessionMemoryIds          — 短期 memoryId 生成（shortTermMemoryId 只由 sessionId 决定）
SpeakerMessageFormatter   — 共享会话中的发言人标记（Phase 1 新增）
SessionIdResolver         — Runtime 解析 sessionId 的窄接口（Phase 0 新增）
```

---

## 三、各模块职责

### SessionManager — 会话生命周期

- 为每个 userId 维护一个活跃会话（`ConcurrentHashMap<String, ActiveSessionState>`）
- Session ID 格式：`S_yyyyMMdd_HHmmss_SSS_8位UUID`，重试 3 次防碰撞
- 程序启动后自动恢复上次活跃会话；用户指令"新对话"结束旧会话创建新会话
- 程序关闭时标记会话为 inactive，数据保留不删除
- 提供 `createConversationSession`（连 metadata）、`switchSession`（不中断当前会话切换）等方法

### SessionMemoryStore — 会话消息存储

- 实现 `ChatMemoryStore` 接口
- 短期 memoryId 只由 `sessionId` 决定（Phase 2 从 `userId_sessionId` 收敛）
- 同时维护 `sessions` 元数据表（标题、persona、消息数、压缩次数等）和 `session_messages` 消息表
- 提供 Session CRUD：`createSession`、`endSession`、`getActiveSession`、`listSessions`、`createActiveSession`（事务内更新）、`activateSession`、`deleteSession`、`deleteGlobalSession`（Phase 0）
- DB_VERSION=3（Phase 2 升级），v3 迁移确保 session_messages 表存在
- 异常时 catch 并打 Log.e，不向上抛，保证记忆读写不导致主线崩溃

### SessionMemoryIds — memoryId 生成工具

- `shortTermMemoryId(sessionId)` → 只返回 `sessionId` 本身（Phase 1 新增）
- `shortTermMemoryPrefix(sessionId)` → 与 shortTermMemoryId 相同
- 旧 `build(userId, sessionId, personaId)` 标记为 `@Deprecated`，仅作兼容

### SessionChatMemoryProvider — ChatMemory 容器（Phase 3 新增）

- 封装 `MessageWindowChatMemory.builder().id(sessionId).chatMemoryStore(sessionStore)`
- LRU 缓存（`LinkedHashMap`, accessOrder=true），默认上限 50 个 session
- `getOrCreate(sessionId, maxMessages)` — 获取或创建 session ChatMemory
- `replaceMessages(sessionId, messages)` — 压缩写回时同步 live ChatMemory 与 store
- `clear(sessionId)` — 清除缓存和 store

### SessionIdResolver — Runtime 解析接口（Phase 0 新增）

- `@FunctionalInterface`，由 `MemoryOrchestrator` 实现
- `resolveSessionId(userId, requestedSessionId, title, personaId, sourceApp)`
- 缺失 sessionId 时查询 active session，无 active session 则自动创建

### SpeakerMessageFormatter — 发言人标记（Phase 1 新增）

- `formatUserMessage(userId, text)` → `[speaker={userId}] {text}`
- 空白 userId 降级为 `default_user`
- Phase 4 起 AgentLoopOrchestrator 写入 ChatMemory 时强制使用

### MemorySnapshot — Context 预留只读快照（Phase 3 新增）

- 不可变值类型，包含 `sessionId`、`messages`、`tokenEstimate`、`summary`
- 防御性拷贝 `messages` 列表
- `summary()` 标注为 best-effort（当前摘要仍依赖 `【对话摘要】` 文本前缀）

### MemoryCompressor — 自动压缩

- 触发阈值：4000 token；压缩目标：2000 token；保留最近 5 轮
- 调用 LLM（qwen-turbo）对旧消息做摘要，替换为 `UserMessage("【对话摘要】...")`
- 安全措施：有未完成的工具调用对时不压缩；摘要为空跳过；旧消息为空跳过
- 支持通过 `AgentTraceRecorder` 记录压缩链路到 OpenTelemetry trace

### MemoryExtractor — 长期记忆提取

- 每轮对话后调用 LLM 分析 `(userMessage, aiResponse)`，提取偏好/事实/习惯/规则
- LLM 输出期望为 JSON 数组：`[{"category":"preference","key":"空调温度","value":"喜欢22度","confidence":0.85}]`
- 低于 0.3 置信度的不写入；解析失败时返回空数组，不阻断对话
- 写入使用原始 `userMessage`（不带 speaker 标记），保证长期记忆归属于当前发言人

### LongTermMemoryStore — 长期记忆存储

- 独立 SQLite 文件（`aiagent_longterm.db`），表 `long_term_memory`，`UNIQUE(user_id, category, key_text)`
- 写：`upsertMemory` 使用 `INSERT OR REPLACE` + `COALESCE` 保留原 created_at
- 读取时按 `confidence DESC, updated_at DESC` 排序，取前 100 条
- 格式化入口 `formatAsPromptContext` 按类别分组，只输出置信度 >= 0.5 的条目
- 提供 `incrementAccess`、`deleteMemory`、`clearUserMemories` 管理接口

### MemoryPreProcessor / MemoryPostProcessor

- `MemoryPreProcessor`：Phase 4 起不再是 TEXT/VOICE 主路径的一部分，保留为 legacy/no-op
  - 长期记忆唯一注入点已收敛到 AgentLoop 的 transient SystemMessage（`buildSystemPromptMessage()`）
  - 此 preprocessor 不能再把长期记忆拼入用户消息
- `MemoryPostProcessor`：**空实现**，无实际操作（实际提取由 AgentLoopOrchestrator 直接调 `onTurnComplete`）

---

## 四、数据流

```
用户输入 "调到22度"
  │
  ▼
AgentLoopOrchestrator.execute()
  │
  ├─ (0) 解析 sessionId → 获取 session-scoped ChatMemory（Phase 4）
  │      → extraContext.get("session_id") 来自 RequestSession（Phase 0 保证非空）
  │      → memoryOrchestrator.chatMemoryForSession(sessionId, maxMessages)
  │
  ├─ (1) 用户消息写入 ChatMemory（带 speaker 标记）
  │      → SpeakerMessageFormatter.formatUserMessage(userId, userInput)
  │      → chatMemory.add(UserMessage.from(...))
  │
  ├─ (2) 组装完整请求（transient SystemMessage + PreProcessors + 会话历史）
  │      → buildSystemPromptMessage(userId, personaId)   ── transient SystemMessage（含长期记忆）
  │      → PreProcessor 链（不含 MemoryPreProcessor）
  │      → chatMemory.messages()
  │
  ├─ (3) LLM 调用 → 获取回复
  │
  ├─ (4) MemoryOrchestrator.onTurnComplete(userId, sessionId, ...)
  │      → MemoryExtractor.extract(userMsg, aiResp)  →  提取候选（使用原始 userMsg，不带 speaker）
  │      → LongTermMemoryStore.upsertMemory(...)     →  写入长期记忆
  │      → MemoryCompressor.compress(messages, tokens)  →  token 超限则压缩
  │         → SessionChatMemoryProvider.replaceMessages(sessionId, compressed)
  ```

---

## 五、多用户隔离实现

- `MemoryOrchestrator` 内部 `ConcurrentHashMap<String, UserMemoryContext>`，每个 userId 独立
- 每个 `UserMemoryContext` 有独立的 SessionManager、LongTermMemoryStore 视图（SQL WHERE user_id=?）
- 短期记忆按 `sessionId` 隔离（同一会话内不同 userId 共享短期上下文）
- 长期记忆按 `userId` 隔离
- Persona 不参与任何 memory key
- 当前默认 userId = `"default_user"`，AIDL 传入 userId 即可扩展

---

## 六、已关闭的问题

### 问题 1（Phase 4 已修复）：长期记忆重复注入

~~长期记忆在同一轮 LLM 调用中被注入了两次：~~
1. ~~SystemMessage（`MemoryOrchestrator.prepareSystemPrompt`）~~
2. ~~UserMessage（`MemoryPreProcessor` 中 `"【用户记忆参考】..."`）~~

**Phase 4 修复：** `MemoryPreProcessor` 从 TEXT/VOICE preprocessor chain 移除；长期记忆唯一注入点收敛到 `buildSystemPromptMessage()` 返回的 transient SystemMessage。

### 问题 2：`MemoryPostProcessor` 是空壳

`MemoryPostProcessor.process()` 直接返回 `llmOutput`，不做任何操作。真正的记忆提取在 `AgentLoopOrchestrator` 中直接调用 `onTurnComplete()`，不通过 PostProcessor。**建议：** 要么把提取逻辑移入 PostProcessor，要么删除它避免误导。

### 问题 3（Phase 3 已修复）：`SessionChatMemoryProvider` 不存在

~~项目文档和 CLAUDE.md 中列出了 `SessionChatMemoryProvider.java`，但该文件在磁盘上不存在。~~

**Phase 3 已创建 `SessionChatMemoryProvider`**，封装 `MessageWindowChatMemory.builder().id(sessionId).chatMemoryStore(store)`。

### 问题 4：仍使用 `MessageWindowChatMemory`，未切换 `TokenWindowChatMemory`

当前仍以消息条数为窗口，不是 token 数。`estimateTokens()` 方法用 `chars/2` 粗略估算，与实际 tokenizer 有偏差。意味着：
- 压缩触发的 4000 token 阈值是基于粗略估算的，可能过早或过晚触发
- 模型实际输入窗口与计数窗口不匹配

### 问题 5：两个独立的 SQLite 数据库文件

- `SessionMemoryStore` → `aiagent_memory.db`
- `LongTermMemoryStore` → `aiagent_longterm.db`

两者可合并到同一个 DB 文件，减少连接数和简化备份。

### 问题 6：长期记忆缺乏遗忘机制

`long_term_memory` 表预留了 `updated_at` 和 `access_count` 字段，但没有任何衰减或遗忘逻辑。记忆条目只会增加，永远不会被自动清除。

---

## 七、优点

- **四层分离清晰** — Session 管理层、消息存储层、长期记忆层、压缩提取层各有独立职责
- **异常安全** — 所有 SQLite 操作有 try/catch 包裹，记忆系统异常不导致主流程崩溃
- **多用户隔离** — `ConcurrentHashMap` + SQL `WHERE user_id` 天然支持，扩展成本低
- **Trace 集成** — 压缩和提取链路已接入 OpenTelemetry，可在 Phoenix 中查看
- **压缩安全机制** — 有未完成工具调用时不压缩、摘要为空跳过、旧消息为空跳过
- **SessionId 幂等重试** — 3 次重试防碰撞，失败时抛 IllegalStateException 而非静默覆盖
- **session-scoped 短期记忆** — 同一会话内不同 userId、不同 personaId 共享短期上下文

---

## 八、文件清单

### 生产代码

| 文件 | 行数 | 职责 | 阶段 |
|------|------|------|------|
| `memory/MemoryOrchestrator.java` | ~230 | 记忆协调器，对外统一入口，实现 SessionIdResolver | — |
| `memory/SessionManager.java` | ~140 | Session 生命周期管理 | — |
| `memory/SessionMemoryStore.java` | ~340 | 消息持久化 + Session 元数据（ChatMemoryStore 实现，DB_VERSION=3） | — |
| `memory/SessionMemoryIds.java` | ~35 | memoryId 统一生成（shortTermMemoryId 只由 sessionId 决定） | Phase 1 |
| `memory/SessionIdResolver.java` | ~30 | Runtime 解析 sessionId 的窄接口 | Phase 0 |
| `memory/SessionChatMemoryProvider.java` | ~95 | session 级 ChatMemory 容器，LRU 缓存 | Phase 3 |
| `memory/MemorySnapshot.java` | ~55 | Context 预留只读快照值类型 | Phase 3 |
| `memory/SpeakerMessageFormatter.java` | ~30 | 共享会话中的发言人标记工具 | Phase 1 |
| `memory/UserMemoryContext.java` | ~110 | 单用户所有记忆聚合 | — |
| `memory/MemoryCompressor.java` | ~265 | Token 超限时 LLM 摘要压缩 | — |
| `memory/MemoryExtractor.java` | ~190 | 对话中提取长期记忆 | — |
| `memory/LongTermMemoryStore.java` | ~155 | 长期记忆持久化（独立 DB） | — |
| `memory/MemoryEntry.java` | ~50 | 长期记忆条目值类型 | — |
| `memory/MemoryCandidate.java` | ~25 | 提取候选项值类型 | — |
| `core/preprocessor/MemoryPreProcessor.java` | ~45 | Phase 4 起为 legacy/no-op，不再用于 TEXT/VOICE 主路径 | Phase 4 |

### 测试代码

| 文件 | 说明 | 阶段 |
|------|------|------|
| `test/.../memory/SessionIdResolverTest.java` | 3 个接口语义测试 | Phase 0 |
| `test/.../memory/SessionMemoryIdsTest.java` | 4 个短期 key 测试 | Phase 1 |
| `test/.../memory/SpeakerMessageFormatterTest.java` | 3 个 speaker 标记测试 | Phase 1 |
| `test/.../memory/SessionChatMemoryProviderTest.java` | 4 个 provider 测试 | Phase 3 |
| `test/.../memory/MemorySnapshotTest.java` | 1 个防御性拷贝测试 | Phase 3 |
| `test/.../core/AgentLoopOrchestratorSessionMemoryTest.java` | 约束测试 + speaker 分离测试 | Phase 0,4,5 |
| `test/.../runtime/AgentRuntimeResolvedSessionTest.java` | sessionId 解析贯穿全链路测试 | Phase 0 |
