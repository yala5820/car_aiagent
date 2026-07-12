# Memory Session ChatMemory 改造 — 工作总结

**编制日期：** 2026-07-09
**改造范围：** Phase 0 ～ Phase 6，AIAgent Memory 模块短期记忆主路径从固定 store 到 session-scoped 的完整改造
**提交对象：** 项目总工程师

---

## 一、工作背景与目标

### 改造前的问题

AIAgent 的短期记忆（ChatMemory）使用三个独立的 `AgentLoopOrchestrator` 实例（对应 chat/friendly/concise 三种人格），每个实例绑定一个独立的 SQLite 存储文件（`ChatMemory_chat.db` / `ChatMemory_friendly.db` / `ChatMemory_concise.db`）。这导致：

1. **切换人格 = 切换记忆**：用户在同一次对话中切换人格后，AI 不记得刚才聊了什么
2. **短期记忆按 userId 分裂**：同一 session 内不同发言人虽然共享同一个物理会话，但短期消息写入不同的 `memory_id` 行
3. **长期记忆重复注入**：`SystemMessage` 和 `MemoryPreProcessor` 两条路径同时向 LLM 输入长期记忆，浪费 token
4. **`sessionId` 缺失时无兜底**：Runtime 对 `sessionId` 做透传处理，`AgentLoopOrchestrator` 拿不到确定的 session 标识

### 改造目标

将短期记忆主路径从"人格隔离 + userId 分裂"改为"全局共享 session-scoped"，使得：同一座舱会话内不同用户、不同人格的发言共享同一份短期上下文；长期记忆按发言人 `userId` 独立读写；`personaId` 彻底退出 memory key。

---

## 二、各 Phase 工作内容

### Phase 0：身份模型硬门槛和 resolvedSessionId 贯穿

**范围：** `SessionIdResolver` 接口、`AgentRuntime` 构造注入、`RequestSession`/`ContextFrame`/`Response`/`Trace` 全链路、全局删除语义

**做了什么：**

- 新增 `SessionIdResolver` 窄接口（`@FunctionalInterface`），由 `MemoryOrchestrator` 实现
- `AgentRuntime.startSession()` 在 Context 构建前调用 `resolveSessionId()`：缺失 sessionId 时自动查询或创建 active session
- `RequestSessionFactory` 新增重载接收已解析的 `resolvedSessionId`，确保 `orchestratorContext["session_id"]` 始终存在（不再条件性缺失）
- `writeRequestMetaToTrace()` 写入 `agent.session.id` 属性，使 Trace 中可追溯本轮 session 归属
- `SessionMemoryStore.deleteGlobalSession(sessionId)` 按全局 `sessionId` 删除所有 user 的 metadata 和短期消息，放弃旧的 `LIKE userId_sessionId_%` 模式
- `AIAgentService` 注入 `memoryOrchestrator` 作为 `SessionIdResolver`

**新增文件：** `SessionIdResolver.java`、`SessionIdResolverTest.java`、`AgentRuntimeResolvedSessionTest.java`

### Phase 1：定义短期记忆身份模型和纯函数测试

**范围：** `SessionMemoryIds` 收敛、`SpeakerMessageFormatter`

**做了什么：**

- `SessionMemoryIds.shortTermMemoryId(sessionId)` → 只返回 `sessionId` 本身；空值抛 `IllegalArgumentException`，强制要求调用方确保 `sessionId` 非空
- 旧 `build(userId, sessionId, personaId)` 标记 `@Deprecated`，委托新方法
- `SpeakerMessageFormatter.formatUserMessage(userId, text)` → 输出 `[speaker={userId}] {text}`，空白 userId 降级为 `default_user`
- 以上均为纯函数，JVM 单测无需 Android 依赖

**新增文件：** `SessionMemoryIdsTest.java`、`SpeakerMessageFormatter.java`、`SpeakerMessageFormatterTest.java`

### Phase 2：让 SessionMemoryStore 成为 LangChain4j 短期存储底座

**范围：** `SessionMemoryStore.buildMemoryId` 收敛、DB_VERSION 升级

**做了什么：**

- `buildMemoryId(userId, sessionId)` 从 `userId + "_" + sessionId` 改为 `SessionMemoryIds.shortTermMemoryId(sessionId)`，返回值从二级 key 变为纯 `sessionId`
- `SessionManager.currentMemoryId(userId)` 通过委托 `buildMemoryId` 自动继承新语义
- DB_VERSION 从 2 升至 3，`onUpgrade` 添加 v3 迁移（`CREATE TABLE IF NOT EXISTS session_messages`），幂等安全
- `ConversationManagerTest` 验证无 memoryId 断言，不受影响

### Phase 3：新增 SessionChatMemoryProvider 和 Context 预留只读接口

**范围：** `SessionChatMemoryProvider`、`MemorySnapshot`、`MemoryOrchestrator` 接入

**做了什么：**

- `SessionChatMemoryProvider` 封装 `MessageWindowChatMemory.builder().id(sessionId).chatMemoryStore(store)`，提供 LRU 缓存（默认上限 50 个 session）、`getOrCreate`/`replaceMessages`/`clear`
- `MemorySnapshot` 不可变只读快照值类型，包含 `sessionId`/`messages`/`tokenEstimate`/`summary`，防御性拷贝
- `MemoryOrchestrator` 新增字段 `sessionChatMemoryProvider`，暴露 6 个预接口：
  - `chatMemoryForSession(sessionId, maxMessages)` — TEXT 主路径获取 ChatMemory
  - `clearSessionMemory(sessionId)` — 清理 session 记忆
  - `readSessionMessages(sessionId)` / `getMemorySnapshot(sessionId)` / `getSummarizedMemory(sessionId, maxChars)` — Context 预留只读
  - 新增带 `sessionId` 的 `onTurnComplete` 重载，压缩写回通过 `provider.replaceMessages` 同步 live ChatMemory 与 store
- 旧 `onTurnComplete` 保留为 legacy 路径，标注"不走 provider 缓存"限制

**新增文件：** `SessionChatMemoryProvider.java`、`SessionChatMemoryProviderTest.java`、`MemorySnapshot.java`、`MemorySnapshotTest.java`

### Phase 4：极窄接入 AgentLoop，使真实 ChatMemory 按 session 生效

**范围：** `AgentLoopOrchestrator` 核心改造、`AgentConfigFactory` 配置修正、`AIAgentService` ClearChatMemory 修复

**做了什么：**

- **移除 SystemMessage 持久化**：`injectSystemPrompt()` 替换为 `buildSystemPromptMessage()`，返回的 `SystemMessage` 作为每轮 transient 消息拼入 `allMessages`，不再写入 `ChatMemory`。这是防止长期记忆泄漏到共享短期历史的关键——多人共享 session 时不会看到彼此的长期记忆
- **session-scoped ChatMemory**：`chatMemory` 字段改名 `fallbackChatMemory`；`execute()` 开头从 `extraContext["session_id"]` 获取已解析的 sessionId，调用 `memoryOrchestrator.chatMemoryForSession(sessionId, maxMessages)`
- **speaker 标记**：用户消息写入改为 `SpeakerMessageFormatter.formatUserMessage(userId, userInput)`
- **`onTurnComplete` 全部改用新重载**：传入 sessionId，压缩写回走 `provider.replaceMessages` 同步缓存
- **移除 MemoryPreProcessor**：`createTextPersona()` 和 `createChatPersona()` 的 preprocessor chain 中移除 `MemoryPreProcessor`，长期记忆唯一注入点收敛到 `buildSystemPromptMessage`
- **`chatMemoryStoreId` 改为 `"FallbackChatMemory"`**：标明 legacy 路径，正常 TEXT 请求不得使用
- **`ClearChatMemory` 语义修正**：从 `startNewSession(sessionId)` + `chatOrchestrator.cleanMemory()` 改为 `textOrchestrator.cleanMemory(sessionId)`，收敛为清除当前 session 短期消息

### Phase 5：长期记忆与共享短期历史的边界加固

**范围：** 验证加固，新增测试

**做了什么：**

- 审查确认 Phase 4 已正确实现 speaker/userInput 分离：ChatMemory 存 `[speaker=user_a]`，`onTurnComplete` 传原始 `userInput`
- 确认 MemoryCompressor 不产生 `[speaker=...]` 前缀噪声（使用 `用户：`/`AI：` 前缀）
- 新增 `speakerFormattedMessageInChatMemoryRawUserInputOnTurnComplete` 测试，通过 `SessionChatMemoryProvider` + `FakeStore` 验证 speaker 标记的正确性

### Phase 6：文档、回归和验收

**范围：** 文档更新、全量回归、验收文档

**做了什么：**

- 重写 `memory-module-overview.md`，反映 Phase 0-5 的所有架构变化，修正过期说法，补充文件清单
- 创建 `memory-session-chatmemory-testresult.md`，汇总 JVM 单测结果、SQLite 待验证点、人工验收用例
- 全量回归：`testDebugUnitTest` → BUILD SUCCESSFUL，`assembleDebug` → BUILD SUCCESSFUL

---

## 三、改进后 Memory 模块的能力

### 1. 短期记忆 session-scoped（核心变化）

```
旧行为：
  userId=user_a, sessionId=S_001 → memoryId = "user_a_S_001"
  userId=user_b, sessionId=S_001 → memoryId = "user_b_S_001"  ← 两个文件，互相隔离

新行为：
  userId=user_a, sessionId=S_001 → memoryId = "S_001"
  userId=user_b, sessionId=S_001 → memoryId = "S_001"          ← 同一文件，共享上下文
```

- 同一 `sessionId` 内不同 `userId` 的发言共享同一份 `MessageWindowChatMemory`
- 同一 `sessionId` 内不同 `personaId` 不影响 memory key，只影响 system prompt 模板
- `chatMemory.add(UserMessage.from([speaker=user_a] 打开空调))` — 保留发言人标记

### 2. 长期记忆 user-scoped（保持不变，边界加固）

- 按 `userId` 读写 `LongTermMemoryStore`
- `MemoryExtractor` 提取时使用原始 `userInput`（不带 `[speaker=user_a]` 前缀），保证记忆归属正确
- 唯一注入点为 transient `SystemMessage`（`buildSystemPromptMessage`），不持久化进 shared `ChatMemory`

### 3. sessionId 缺失时自动兜底

- `AgentRuntime.startSession()` 调用 `SessionIdResolver.resolveSessionId()`
- 缺失 sessionId → 查询 active session → 无 active session → 自动创建新 session
- 兜底结果写入 `RequestSession.sessionId()` / `orchestratorContext["session_id"]` / `RuntimeResult.sessionId()` / Trace

### 4. 删除语义统一为全局共享删除

- `deleteGlobalSession(sessionId)` 按 `sessionId` 删除所有 user 的 metadata 和短期消息
- `deleteSession(userId, sessionId)` 委托 `deleteGlobalSession(sessionId)`，`userId` 仅用于调用方兼容

### 5. Context 模块预留接口

- `MemoryOrchestrator.readSessionMessages(sessionId)` — 直读 store
- `MemoryOrchestrator.getMemorySnapshot(sessionId)` — 只读快照 + token 估算
- `MemoryOrchestrator.getSummarizedMemory(sessionId, maxChars)` — 预算截断摘要
- 但当前不做 prompt 组装决策（留给下阶段 Context 模块）

---

## 四、涉及文件统计

### 新增文件（生产代码 7 个）

| 文件 | 职责 | 阶段 |
|------|------|------|
| `memory/SessionIdResolver.java` | Runtime 解析 sessionId 窄接口 | Phase 0 |
| `memory/SessionChatMemoryProvider.java` | session 级 ChatMemory 容器，LRU 缓存 | Phase 3 |
| `memory/MemorySnapshot.java` | Context 预留只读快照 | Phase 3 |
| `memory/SpeakerMessageFormatter.java` | 共享会话发言人标记 | Phase 1 |

### 新增文件（测试代码 8 个）

`SessionIdResolverTest`、`AgentRuntimeResolvedSessionTest`、`SessionMemoryIdsTest`、`SpeakerMessageFormatterTest`、`SessionChatMemoryProviderTest`、`MemorySnapshotTest`、`SessionMemoryStoreDeleteTest`、`TestRequestSessions`

### 修改文件（生产代码 6 个）

| 文件 | 主要改动 |
|------|---------|
| `memory/MemoryOrchestrator.java` | 实现 SessionIdResolver；构造 SessionChatMemoryProvider；暴露 6 个 memory 接口；新增 onTurnComplete(sessionId) |
| `memory/SessionMemoryStore.java` | buildMemoryId 改为 sessionId；deleteGlobalSession；DB_VERSION 3 |
| `memory/SessionMemoryIds.java` | 新增 shortTermMemoryId/shortTermMemoryPrefix；旧方法 @Deprecated |
| `memory/SessionManager.java` | currentMemoryId 通过 buildMemoryId 自动继承新语义 |
| `memory/UserMemoryContext.java` | 无修改（自动继承 buildMemoryId 新语义） |
| `core/AgentLoopOrchestrator.java` | 移除 injectSystemPrompt；buildSystemPromptMessage transient 注入；session-scoped ChatMemory；speaker 标记；onTurnComplete 新重载；cleanMemory(sessionId) |
| `core/factory/AgentConfigFactory.java` | 移除 MemoryPreProcessor；chatMemoryStoreId → "FallbackChatMemory" |
| `core/preprocessor/MemoryPreProcessor.java` | 注释标注 legacy |
| `runtime/AgentRuntime.java` | 新增 SessionIdResolver 构造器；startSession 解析 sessionId；trace 写入 |
| `runtime/RequestSessionFactory.java` | 新增 create() 重载接收 resolvedSessionId |
| `AIAgentService.kt` | 注入 SessionIdResolver；修正 ClearChatMemory 语义 |

---

## 五、遗留问题和风险

### 1. 设备端 SQLite 行为验证未完成

以下验证点需在 Android 设备或模拟器上手动执行：
- `v2 → v3 upgrade` 不崩溃，`session_messages` 表按新 schema 可读写
- `MessageWindowChatMemory(id=sessionId, store=SessionMemoryStore)` 可正常 update/get
- `deleteGlobalSession(sessionId)` 正确删除所有 user metadata 和短期消息
- `provider.replaceMessages(sessionId, compressed)` 后缓存立即可见
- 对应的 `SessionMemoryStoreDeleteTest` 目前标记 `@Ignore`

### 2. AgentLoopOrchestrator 的 JVM 单元测试覆盖不足

由于 `PromptManager` 构造函数依赖 Android `Context`，JVM 环境下无法构造完整的 `AgentLoopOrchestrator` 实例。以下约束测试保持骨架状态，需在设备端验证：

- `textPathFailsWhenSessionIdMissing` — 缺失 sessionId 时返回 INVALID_CONFIG
- `textPathUsesSessionMemoryNotFallback` — 不走 fallback ChatMemory

### 3. 长期记忆缺乏遗忘机制

`LongTermMemoryStore` 的 `long_term_memory` 表预留了 `updated_at` 和 `access_count` 字段，但没有任何衰减或自动清除逻辑。记忆条目只会增加，不会自动衰减。长期运行可能导致：
- SystemPrompt 中长期记忆段落过大
- 过时信息持续影响模型行为

### 4. TokenWindowChatMemory 未切换

当前仍使用 `MessageWindowChatMemory`（按消息条数 50 条为窗口），未切换到 `TokenWindowChatMemory`。`estimateTokens()` 使用 `chars/2` 粗略估算，与实际 tokenizer 存在偏差。这意味着：
- 压缩触发的 4000 token 阈值估算不精确，可能过早或过晚触发
- 模型实际输入窗口（通常 4k/8k/32k）与计数窗口不匹配

### 5. 两个独立的 SQLite 数据库文件

- `SessionMemoryStore` → `aiagent_memory.db`
- `LongTermMemoryStore` → `aiagent_longterm.db`

两者可合并到同一个 DB 文件以减少连接数和简化备份。

### 6. MemoryPostProcessor 是空壳

`MemoryPostProcessor.process()` 直接返回 `llmOutput`，不做任何操作。真正的记忆提取在 `AgentLoopOrchestrator` 中直接调用 `onTurnComplete()`，不经过 PostProcessor。要么把提取逻辑移入 PostProcessor，要么删除它避免误导。

### 7. Context 模块尚未完整接入

Phase 3 已预留 `readSessionMessages`、`getMemorySnapshot`、`getSummarizedMemory` 三个读取接口，但 Context 模块尚未使用它们做完整的 prompt 组装决策。下阶段需将 Context 的预算管理策略与 Memory 的数据输出对接。

---

## 六、架构决策记录

| 决策 | 选择 | 理由 |
|------|------|------|
| 短期 memory key | 纯 `sessionId`，不含 `userId`/`personaId` | 同一会话内不同用户/人格共享上下文 |
| ChatMemory 构造 | `MessageWindowChatMemory.builder().id(sessionId).chatMemoryStore(store)` | 利用 LangChain4j 现有机制，id 直接作为 store 的 memoryId |
| 长期记忆注入 | transient SystemMessage（非持久化） | 避免长期泄漏到共享短期历史 |
| 缺失 sessionId 策略 | 自动 resolve（active session → 创建新 session） | 兼容现有调用方，不给设备端增加修复成本 |
| 删除语义 | 全局共享删除（删 sessionId = 删所有 user metadata + 消息） | 短期记忆归属于 sessionId 而非 userId |
| LRU 缓存上限 | 50 个 session | 长期运行 Service 的合理上限；超限时淘汰最久未访问 |
| 旧 onTurnComplete | 保留为 legacy（不走 provider 同步） | 不破坏现有测试和非 TEXT 路径调用 |
