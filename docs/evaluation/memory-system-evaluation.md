# AIAgent Memory 系统评价报告

生成日期：2026-07-03

## 1. 评估范围

本报告基于当前仓库的静态源码、参考总结文档和一次 memory 相关单元测试执行结果，对 AIAgent 当前 Memory 系统进行客观评估。重点检查：

1. Memory 系统技术栈设置是否合理。
2. 当前设计与实际接入是否一致，包括架构、会话生命周期、长期记忆、压缩、多用户隔离、AgentLoop 集成等。
3. 当前存在的问题、遗漏设计、风险等级和优化方向。

主要检查对象：

- `docs/act_summary/memory-system-refactoring-summary.md`
- `app/src/main/java/com/hirain/aiagent/memory/`
- `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java`
- `app/src/main/java/com/hirain/aiagent/core/factory/AgentConfigFactory.java`
- `app/src/main/java/com/hirain/aiagent/core/preprocessor/MemoryPreProcessor.java`
- `app/src/main/java/com/hirain/aiagent/core/postprocessor/MemoryPostProcessor.java`
- `app/src/main/java/com/hirain/aiagent/AIAgentService.kt`
- `app/src/main/java/langchain4j/chat_memory_sqlite/PersistentChatMemorySqlite.java`
- `app/src/test/java/com/hirain/aiagent/memory/`
- `app/build.gradle.kts`
- `gradle/libs.versions.toml`

验证命令：

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.memory.*"
```

执行结果：通过。需要注意，该测试只覆盖 `MemoryCompressor` 和 `MemoryExtractor` 的 trace 行为，不覆盖 `SessionMemoryStore`、`SessionManager`、`LongTermMemoryStore`、`MemoryOrchestrator`、主 AgentLoop 接入、多用户隔离、真实 SQLite 迁移和真实模型调用。

本次未修改业务代码，只新增本评价报告。

## 2. 总体结论

当前 Memory 系统的目标方向是合理的：对于车机 AI 助手，单纯依赖短窗口 ChatMemory 不足以支撑连续驾驶场景、跨会话用户偏好和长期个性化能力，引入 Session、长期记忆、压缩和多用户隔离是正确方向。

但以当前源码为准，Memory 系统仍处于“架构雏形已经写出，但主链路接入不完整”的状态，不能认为已经完成四层记忆架构闭环。最核心的问题是：新增的 `SessionMemoryStore` 并没有接入主对话 `ChatMemory`，`AgentLoopOrchestrator` 仍然使用旧的 `MessageWindowChatMemory + PersistentChatMemorySqlite`。因此总结文档中描述的 Session 级消息持久化、按 userId/sessionId 隔离、压缩后写回 Session 记忆等能力，在主对话链路里没有真正生效。

建议将当前完成度定义为：

- 架构设计意图：约 70%
- 长期记忆基础能力：约 55% - 65%
- Session 记忆闭环：约 25% - 35%
- 自动压缩闭环：约 30% - 40%
- 多用户隔离：约 20% - 30%
- 工程验证体系：约 25% - 35%

综合评价：当前系统适合作为下一阶段 memory 重构的代码基础，但不适合作为“已完成的 Memory 功能”进入稳定集成。应优先修正主链路接入、用户/session 边界、压缩写回和测试覆盖，再扩展更复杂的长期记忆治理。

## 3. 技术栈设置评价

### 3.1 LangChain4j ChatMemory 抽象

项目继续使用 LangChain4j 的 `ChatMemory` / `ChatMemoryStore` 抽象是合理的。它能和现有 `ChatModel` 调用链保持一致，不需要引入新的记忆框架，也适合当前自研 `AgentLoopOrchestrator`。

问题在于当前选型和目标能力不匹配：

- 主链路仍使用 `MessageWindowChatMemory`，不是 Token 维度窗口。
- 新增 `SessionMemoryStore` 虽然实现了 `ChatMemoryStore`，但没有被 `AgentLoopOrchestrator.createChatMemory()` 使用。
- `chatMemoryStoreId("ChatMemory")` 实际只作为旧 SQLite 文件名参与构造，不能表达 user/session 维度。

结论：LangChain4j 抽象合理，但当前接线没有利用好 `ChatMemoryStore` 的扩展点。

### 3.2 Android SQLite / SQLiteOpenHelper

使用 Android 原生 SQLite 存储短期会话消息和长期记忆是合理的。项目运行在 Android 车机 Service 中，SQLite 具备本地可用、依赖少、离线能力好、部署简单等优点。

当前实现的问题主要不是 SQLite 本身，而是数据库边界和治理不足：

- `SessionMemoryStore` 使用 `aiagent_memory.db`，旧 `PersistentChatMemorySqlite` 使用 `ChatMemory.db`，长期记忆使用 `aiagent_longterm.db`，主链路实际分裂在多个数据库中。
- `onUpgrade()` 直接 drop 表，会清空用户记忆，不适合长期记忆数据。
- 缺少索引、迁移策略、数据清理策略、隐私删除入口和错误上报。
- `session_messages` 只有 `memory_id` 和 `messages`，没有显式 `user_id`、`session_id` 外键，难以做一致性校验和运维排查。

结论：SQLite 作为技术栈合理，但当前 schema 和迁移治理不成熟。

### 3.3 LLM 驱动的提取与压缩

使用 `qwen-turbo` 做长期记忆提取和历史摘要，在 Demo 阶段是可接受的：实现成本低，能快速验证“对话后抽取 + 下轮注入”的产品闭环。

但作为车机后台 Service 的默认同步链路，需要谨慎：

- 每轮对话结束后同步调用提取模型，会增加端到端延迟和失败点。
- 压缩触发时再次调用模型，可能和主模型请求叠加超时。
- 结构化 JSON 输出依赖 prompt 约束，没有强 schema、重试、修复解析或降级队列。
- 提取 prompt 和压缩 prompt 硬编码在 Java 中，没有复用现有 `assets/prompts/ + PromptManager` 的外部化能力。

结论：适合原型验证，不适合作为稳定生产链路的最终形态。后续应引入异步队列、结构化校验、失败重试、可观测指标和外部化 prompt 管理。

### 3.4 Gson JSON 解析

`MemoryExtractor` 使用 Gson 解析模型输出，简单直接，符合当前依赖情况。缺点是容错有限：如果模型输出带自然语言解释、半截 JSON、多对象包装、字段别名或非法置信度，当前逻辑大多静默返回空数组。

结论：当前阶段可用，但需要在测试和 prompt 约束上补强。

### 3.5 OpenTelemetry trace 接入

`MemoryExtractor` 和 `MemoryCompressor` 已经接入 memory extract/compress span，且已有 trace 单元测试。这个方向是合理的，因为 memory 生命周期是 Agent 业务语义的一部分，不应只依赖低层 HTTP trace。

不足是 trace 覆盖不等于功能验证。当前测试能证明 span 记录逻辑有效，不能证明 memory 数据被正确写入、读取、压缩和跨会话恢复。

结论：trace 接入合理，但验证范围不足。

## 4. 当前实际运行链路

### 4.1 主对话链路

当前 TEXT / VOICE 请求最终进入 `chatOrchestrator.execute(...)`：

1. `AIAgentService` 初始化 `MemoryOrchestrator`。
2. `AgentConfigFactory.createChatPersona(...)` 把 `MemoryPreProcessor` 和 `MemoryPostProcessor` 放入 chat persona。
3. `AgentLoopOrchestrator` 构造自己的 `ChatMemory`。
4. 每次执行时调用 `injectSystemPrompt(userId)`，通过 `MemoryOrchestrator.prepareSystemPrompt(...)` 将长期记忆拼到 SystemPrompt。
5. 用户输入、AI 响应、工具结果写入 `chatMemory`。
6. 最终输出后调用 `MemoryOrchestrator.onTurnComplete(...)` 做长期记忆提取和压缩检查。

这个流程看起来接上了 MemoryOrchestrator，但实际短期会话消息仍写入旧存储：

```java
case PERSISTENT -> MessageWindowChatMemory.builder()
        .maxMessages(config.maxMemoryMessages())
        .chatMemoryStore(new PersistentChatMemorySqlite(
                context.getApplicationContext(), config.chatMemoryStoreId()))
        .build();
```

也就是说，主对话的真实消息存储仍是 `PersistentChatMemorySqlite("ChatMemory")`，不是 `SessionMemoryStore`。

### 4.2 新增 SessionMemoryStore 的实际地位

`MemoryOrchestrator` 创建了 `SessionMemoryStore` 和 `SessionManager`，`SessionManager` 也会创建 session 元数据。但这些 session 元数据没有驱动 `AgentLoopOrchestrator` 的 `chatMemory`。

压缩时存在更明显的断点：

```java
List<ChatMessage> compressed = ctx.compressIfNeeded(currentMessages, tokenEstimate, trace);
if (compressed != currentMessages) {
    String memoryId = ctx.currentMemoryId();
    if (memoryId != null) {
        sessionStore.updateMessages(memoryId, compressed);
    }
}
```

这里压缩后的消息写入 `SessionMemoryStore`，但主 AgentLoop 后续读取的仍是 `PersistentChatMemorySqlite` 背后的 `chatMemory.messages()`。因此压缩结果不会反向更新当前活跃 `chatMemory`，也不会影响下一轮主对话上下文。

### 4.3 长期记忆链路

长期记忆链路相对更接近可用：

1. `MemoryExtractor.extract(userMessage, aiResponse, trace)` 调用模型提取候选记忆。
2. `UserMemoryContext.extractAndStore(...)` 将候选写入 `LongTermMemoryStore`。
3. `MemoryOrchestrator.prepareSystemPrompt(...)` 读取长期记忆并拼接到 SystemPrompt。

但仍存在明显限制：

- 提取只看 `userMessage` 和最终 `aiResponse`，没有结构化利用工具执行结果、车辆状态、场景上下文和完整消息历史。
- 读取时最多取 100 条，高置信度和更新时间排序后全部注入，没有按当前 query 做相关性检索。
- `formatAsPromptContext()` 只注入 value，不注入 key、更新时间、来源和冲突信息。
- `incrementAccess()` 已实现但未被调用，access_count 没有参与召回或衰减。
- 没有用户可控的长期记忆删除、查看、确认和纠错机制。

### 4.4 多用户隔离链路

总结文档描述了 `ConcurrentHashMap<String, UserMemoryContext>` 的多用户池，但当前实现并不具备可靠的多用户隔离：

- `MemoryOrchestrator` 内只有一个全局 `SessionManager`。
- 每个 `UserMemoryContext` 都引用同一个 `SessionManager`，不是每个用户独立 session manager。
- `SessionManager` 内只有一个 `currentUserId` 和一个 `currentSessionId`。
- `AgentLoopOrchestrator` 内只有一个 `chatMemory` 实例。
- `AIAgentService` 把 `request.sessionId` 当作 `user_id` 使用，缺少独立 userId 概念。

因此多个 request.sessionId / userId 交替请求时，会共享同一个主对话 `chatMemory`，并不断改写全局 `SessionManager.currentUserId/currentSessionId`。

结论：当前长期记忆表按 `user_id` 查询具备一定隔离基础，但短期对话上下文和 session 生命周期不具备完整多用户隔离。

## 5. 设计合理性评价

### 5.1 合理部分

1. 方向正确：Session、长期记忆、压缩、多用户隔离都是车机 AI 助手需要的能力。

2. MemoryOrchestrator 的边界意识是正确的：让 AgentLoop 通过统一协调器调用 memory 子系统，比在主循环里散落 SQLite 和提取逻辑更容易治理。

3. 长期记忆表的基础字段合理：`user_id`、`category`、`key_text`、`value_text`、`confidence`、`created_at`、`updated_at`、`access_count` 覆盖了基本语义记忆需求。

4. 压缩器保留 SystemMessage 和近期消息的策略方向合理，避免把当前上下文全部摘要掉。

5. trace 语义合理：`memory.extract` 和 `memory.compress` 属于业务级 span，应放在 Agent 请求 trace 树中。

### 5.2 不合理或未闭环部分

1. 短期记忆存储没有切换到新 Session 存储，导致 Session 层和主对话层脱节。

2. 压缩结果没有写回主 `chatMemory`，因此“自动压缩”对实际后续模型请求基本不生效。

3. `MessageWindowChatMemory(50)` 仍会按消息数淘汰旧消息。即使压缩器存在，旧消息仍可能先被 LangChain4j 窗口策略截断。

4. 长期记忆被注入两次：一次拼进 SystemPrompt，一次由 `MemoryPreProcessor` 作为 transient `UserMessage` 注入。并且 transient messages 被放在 `chatMemory.messages()` 前面，可能导致 UserMessage 出现在 SystemMessage 之前，破坏消息角色层级。

5. `MemoryPostProcessor` 是占位类，不承担实际后处理。配置里出现它容易让读者误以为 postprocessor 链在负责 memory 提取，但真正逻辑仍在 `AgentLoopOrchestrator` 内显式调用。

6. 用户身份和会话身份混用。`request.sessionId` 被用于 `user_id`，而 session 本应是一次对话生命周期，userId 应是用户身份。两者混用会导致长期记忆归属不稳定。

7. 多用户隔离在架构图上存在，在运行态不成立。共享 `SessionManager` 和共享 `chatMemory` 会造成上下文串扰。

8. SQLite 迁移直接 drop 表，不符合长期记忆数据要求。

9. 错误处理倾向于吞异常并返回空结果。对 Demo 友好，但对诊断 memory 失效不友好。

10. 缺少 privacy-by-design。车机语音对话可能包含位置、联系人、手机号、家庭成员、行程等敏感信息，长期记忆写入没有确认、删除、保留期限、敏感字段过滤和用户可见机制。

## 6. 关键问题清单

### P0-1：新增 SessionMemoryStore 未接入主 ChatMemory

证据：

- `SessionMemoryStore` 声称替代 `PersistentChatMemorySqlite`。
- 但 `AgentLoopOrchestrator.createChatMemory()` 仍构造 `PersistentChatMemorySqlite`。
- 搜索结果显示 `SessionMemoryStore` 只被 `MemoryOrchestrator`、`SessionManager` 自己使用，没有作为主 `chatMemoryStore` 注入 `MessageWindowChatMemory`。

影响：

- session_messages 表不会成为主对话消息来源。
- sessions 表中的 session 生命周期和真实聊天上下文分离。
- 新对话、恢复、压缩、统计都不能可靠作用于主 AgentLoop。

建议：

- 让 `AgentLoopOrchestrator` 通过 `MemoryOrchestrator` / `UserMemoryContext` 获取当前 user-session 对应的 `ChatMemory`。
- 或将 `SessionMemoryStore` 注入 `MessageWindowChatMemory.builder().chatMemoryStore(...)`，并明确设置 memoryId 为 `{userId, sessionId}`。
- 避免同时维护 `PersistentChatMemorySqlite` 和 `SessionMemoryStore` 两套短期记忆存储。

### P0-2：压缩结果不会影响后续模型上下文

证据：

- `MemoryOrchestrator.onTurnComplete()` 将 compressed messages 写入 `sessionStore.updateMessages(...)`。
- 主循环后续请求仍从 `chatMemory.messages()` 读取，而 `chatMemory` 背后是 `PersistentChatMemorySqlite`。
- 当前活跃 `chatMemory` 对象没有被替换为压缩后的列表。

影响：

- 即使压缩成功，下一轮模型仍看到未压缩或已被 MessageWindow 截断的旧上下文。
- 压缩可能重复触发，增加模型调用成本，但无法产生预期收益。
- 文档中的“压缩替代旧消息”与实际行为不一致。

建议：

- 压缩成功后必须更新当前活跃 `ChatMemory`：清空后写入压缩列表，或让 `ChatMemoryStore` 与当前 `ChatMemory` 使用同一存储和同一 memoryId。
- 压缩后重新估算 token，并更新 session stats。
- 补充集成测试：构造超过阈值的消息，验证下一轮 `ChatRequest.messages()` 中确实包含摘要且不包含旧长历史。

### P0-3：多用户隔离设计不成立

证据：

- `MemoryOrchestrator.userContexts` 是按 userId 的 map。
- 但所有 `UserMemoryContext` 都持有同一个 `SessionManager`。
- `SessionManager` 内部只有一个 `currentUserId` 和一个 `currentSessionId`。
- 主 AgentLoop 只有一个 `chatMemory`。
- `AIAgentService` 使用 `request.sessionId ?: "default_user"` 作为 `user_id`。

影响：

- 用户 A 和用户 B 交替调用时，会话状态和短期上下文可能互相覆盖。
- 长期记忆的 user_id 来源不稳定，可能把会话 ID 当用户 ID 持久化。
- 如果 Launcher 每次生成不同 sessionId，同一用户的长期记忆会被分散到多个“伪用户”下。

建议：

- 明确 AIDL 层身份模型：`userId`、`conversationId/sessionId`、`requestId` 三者分开。
- 每个 userId/sessionId 应对应独立 `ChatMemory` 或至少独立 memoryId。
- `SessionManager` 不应是全局单 current 状态；应按 userId 管理 current session，或变成无状态 store API。

### P1-1：长期记忆注入重复且角色层级不清

证据：

- `injectSystemPrompt()` 已通过 `prepareSystemPrompt()` 把长期记忆拼进 SystemMessage。
- `MemoryPreProcessor.prepare()` 又返回 `UserMessage.from("【用户记忆参考】" + longTermCtx)`。
- `AgentLoopOrchestrator` 组装请求时先加入 transientMessages，再加入 chatMemory.messages()，可能让 UserMessage 出现在 SystemMessage 前。

影响：

- 同一长期记忆重复注入，浪费 token。
- 记忆内容以 UserMessage 注入，会弱化它作为系统上下文的权威性。
- 消息顺序不符合常见 chat model 期望，可能影响模型遵循系统指令。

建议：

- 只保留一种注入方式。更建议将长期记忆作为 SystemPrompt 的受控上下文片段，或使用明确的上下文消息但保证 SystemMessage 第一。
- 删除或改造 `MemoryPreProcessor`，避免重复注入。
- 增加请求组装测试，断言第一条消息为 SystemMessage，且长期记忆只出现一次。

### P1-2：Session 元数据没有真实维护

证据：

- `SessionMemoryStore` 有 `message_count`、`token_estimate`、`compression_count` 字段和 `updateSessionStats()`。
- 当前未发现主链路调用 `updateSessionStats()`。
- 压缩计数只在 `UserMemoryContext` 运行时字段里自增，不持久化。

影响：

- sessions 表中的统计字段长期为默认值。
- 无法根据 session 元数据做压缩、恢复、清理、诊断。
- 文档中“Session 元数据追踪”目前不成立。

建议：

- 每轮结束时统一更新 message_count、token_estimate、compression_count。
- 将 session stats 与真实 ChatMemory 使用同一 user/session 维度。

### P1-3：Session ID 生成存在碰撞风险

证据：

- `SessionManager.createNewSession()` 使用 `yyyyMMdd_HHmmss`，精度到秒。
- `SessionMemoryStore.createSession()` 使用 `INSERT OR IGNORE`。

影响：

- 同一用户一秒内连续开始新对话，可能得到相同 sessionId。
- `INSERT OR IGNORE` 会掩盖冲突，调用方以为创建了新 session，实际仍在旧 session 上。

建议：

- sessionId 加入毫秒、随机后缀或 UUID。
- 插入失败应显式报错或重试生成新 ID。

### P1-4：长期记忆缺少冲突、衰减、删除和用户确认机制

证据：

- `upsertMemory()` 按 `(user_id, category, key_text)` 覆盖写入。
- `incrementAccess()` 已实现但未接入。
- 没有对外暴露长期记忆查看、删除、清空、确认接口。
- `formatAsPromptContext()` 按 confidence 和 updated_at 取前 100 条后全部注入。

影响：

- 用户偏好变化时，旧记忆可能被简单覆盖或与新记忆并存，缺少冲突解释。
- 低质量抽取一旦写入，后续会持续污染 SystemPrompt。
- 敏感信息无法由用户主动管理。
- 长期记忆变多后 token 成本不可控，且与当前请求无关的记忆会干扰模型。

建议：

- 增加记忆状态：active / deprecated / rejected / pending_confirmation。
- 增加用户可控命令：查看记忆、删除某条记忆、清空长期记忆、不要记住。
- 引入按当前 query 的相关性召回，避免无差别注入 top 100。
- 对偏好类记忆做置信度合并，对事实类记忆做时效性和来源管理。

### P1-5：Memory 提取输入过窄

证据：

- `MemoryExtractor.extract()` 只接收 `userMessage` 和 `aiResponse`。
- 工具调用结果、车辆状态、场景上下文、SafetyVeto、真实执行结果都不作为结构化输入。

影响：

- “用户要求”和“工具实际执行结果”之间可能有差异，长期记忆只看最终自然语言答复，容易记录不准确。
- 车控偏好、失败原因、安全限制等关键上下文可能漏记。

建议：

- 提取输入应包含本轮结构化事件：用户输入、工具调用名称与参数、工具结果、安全审查结果、最终回复。
- 不建议直接把完整 JSON 不加过滤地送入提取模型，应先做字段白名单和脱敏。

### P1-6：测试覆盖不足

证据：

- 当前 memory 测试仅覆盖 `MemoryCompressorTraceTest` 和 `MemoryExtractorTraceTest`。
- 没有 `SessionMemoryStore`、`SessionManager`、`LongTermMemoryStore`、`MemoryOrchestrator`、`AgentLoopOrchestrator` memory 集成测试。

影响：

- 当前最关键的接入断点无法被测试发现。
- 多用户隔离、压缩写回、session 切换、长期记忆注入顺序都没有自动化保护。

建议：

- 增加 JVM/Android 层测试矩阵：
  - Session 创建、恢复、结束、同秒新建冲突。
  - LongTermMemory upsert、读取、过滤、删除。
  - AgentLoop 首轮和第二轮请求消息顺序。
  - ClearChatMemory 后短期消息清空但长期记忆是否保留。
  - 压缩后下一轮 ChatRequest 使用摘要。
  - 两个 userId 交替请求时上下文不串。

### P2-1：数据库迁移策略会丢失数据

证据：

- `SessionMemoryStore.SessionDbHelper.onUpgrade()` drop `session_messages` 和 `sessions`。
- `LongTermMemoryStore.onUpgrade()` drop `long_term_memory`。

影响：

- 后续 schema 升级会直接清空用户长期记忆和 session 历史。

建议：

- 使用版本化迁移脚本，禁止默认 drop。
- 对 Demo 阶段也建议至少保留备份表或导出日志。

### P2-2：长期记忆 prompt 和压缩 prompt 未外部化

证据：

- `MemoryExtractor.buildExtractPrompt()` 和 `MemoryCompressor.buildCompressionPrompt()` 均硬编码在 Java 中。

影响：

- Prompt 系统已经外部化，但 Memory 关键 prompt 仍散落在源码里。
- 调优 memory 行为需要改代码、编译、部署。

建议：

- 将提取和压缩 prompt 迁移到 `assets/prompts/task/` 或 `assets/prompts/memory/`。
- 使用 `PromptManager` 统一加载，并增加变量校验。

### P2-3：错误处理对用户和运维不可见

证据：

- SQLite 写入异常多为 `Log.e` 后吞掉。
- 提取失败返回空数组。
- 压缩失败返回原消息。

影响：

- 对用户体验是温和降级，但系统长期失效时难以及时发现。
- Trace 中有部分异常记录，但 DB 层错误没有统一健康状态。

建议：

- 增加 memory health 指标：最近提取成功率、压缩成功率、DB 写入失败次数、长期记忆条数。
- 对关键失败写入 trace/event，便于 Phoenix 观察。

## 7. 缺少或遗漏的设计

### 7.1 用户身份模型

当前缺少明确的 userId 来源。车机环境至少应区分：

- userId：驾驶员或乘客身份。
- conversationId/sessionId：一次对话或一次上车周期。
- requestId：一次 AIDL 请求。
- sourceApp：调用方。

不能用 `request.sessionId` 替代 userId，否则长期记忆归属会不稳定。

### 7.2 记忆治理策略

长期记忆不应只“写入并注入”。需要治理策略：

- 哪些信息允许长期记住。
- 哪些信息必须确认后记住。
- 哪些敏感信息禁止记住。
- 如何删除、过期、降权、纠错。
- 如何处理互相矛盾的偏好。

### 7.3 召回策略

当前是按 confidence 和 updated_at 取前 100 条，全部格式化注入。更合理的设计是：

- 基础用户规则和强偏好可常驻注入。
- 普通事实和偏好按当前 query 召回。
- 场景类记忆按工具领域召回，例如空调、座椅、车窗、导航、音乐。
- 过期事实默认不注入。

### 7.4 隐私与合规

车机语音数据可能高度敏感。Memory 系统至少需要：

- 敏感字段过滤。
- 用户可见的长期记忆管理入口。
- 清除长期记忆命令。
- 数据保留期限。
- 本地加密或至少明确 threat model。
- 不把完整原始对话长期保存为语义记忆。

### 7.5 异步与性能

记忆提取和压缩不一定要阻塞主回复链路。可考虑：

- 主回复先返回。
- 后台队列异步提取长期记忆。
- 压缩在下一轮前或空闲时执行。
- 对 memory 模型调用设置独立超时、熔断和频率限制。

### 7.6 可观测性和评估数据集

需要专门验证 memory 功能质量，而不仅是单元测试：

- 偏好提取准确率。
- 错误写入率。
- 长期记忆命中率。
- 压缩后关键事实保留率。
- 多轮工具调用后上下文保真度。
- 多用户串扰测试。

## 8. 优化路线建议

### 阶段 1：修正主链路闭环

优先级最高，建议先做这些：

1. 明确短期记忆唯一存储实现。将主对话从 `PersistentChatMemorySqlite` 迁移到 `SessionMemoryStore`，或暂时移除未接入的 SessionStore 宣称。
2. 让每个 user/session 拥有独立 memoryId 和 ChatMemory。
3. 修正压缩写回：压缩结果必须影响当前和下一轮 `chatMemory.messages()`。
4. 修正长期记忆注入重复和消息顺序问题，保证 SystemMessage 第一。
5. 增加 session / compression / multi-user 集成测试。

这一阶段完成后，才能认为四层 memory 架构“主链路可用”。

### 阶段 2：长期记忆治理

在主链路闭环后，建议补齐：

1. 明确 userId 与 sessionId 的 AIDL 字段或映射规则。
2. 增加长期记忆查看、删除、清空、拒绝记忆命令。
3. 引入敏感信息过滤和禁止记忆类别。
4. 做冲突合并和置信度更新，而不是简单覆盖。
5. 将 memory prompts 外部化到 assets。

### 阶段 3：性能、召回和质量评估

当基础稳定后再做：

1. 从全量 top 100 注入改为 query-aware 召回。
2. 引入异步提取队列和压缩队列。
3. 建立 memory 质量测试集。
4. 增加 trace 和 metrics 面板，持续观察 memory 成功率和延迟。
5. 考虑本地轻量 embedding 或关键词索引，但不建议在主链路尚未闭环前引入新依赖。

## 9. 对参考总结文档的校正

参考总结文档可以作为设计意图说明，但不能作为当前完成状态的准确证明。需要修正的关键表述包括：

1. “SessionMemoryStore 替代原有 PersistentChatMemorySqlite”不符合当前源码。主 AgentLoop 仍使用 `PersistentChatMemorySqlite`。

2. “TokenWindowChatMemory + ChatMemoryStore 持久化”不符合当前源码。当前仍使用 `MessageWindowChatMemory`。

3. “压缩后持久化到 SessionMemoryStore”虽然代码中存在，但由于主 ChatMemory 不读取该 store，对实际对话上下文不闭环。

4. “每个 UserMemoryContext 拥有独立 SessionManager”不符合当前源码。当前所有 UserMemoryContext 共享同一个 SessionManager。

5. “MemoryPostProcessor 触发记忆提取”不符合当前源码。`MemoryPostProcessor` 是 no-op，占位；实际提取由 `AgentLoopOrchestrator` 显式调用 `MemoryOrchestrator.onTurnComplete()`。

6. “多用户原生支持”只能算 schema 和部分 map 结构预留，不能认为运行态已经支持。

## 10. 当前项目验证状态

已执行：

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.memory.*"
```

结果：BUILD SUCCESSFUL。

构建过程中出现若干 AndroidManifest 重复 permission warning，与 Memory 功能无直接关系。

验证限制：

- 未执行真实 DashScope 模型调用。
- 未执行 Android 设备/模拟器 SQLite 持久化测试。
- 未验证跨进程重启、Service onDestroy、异常杀进程恢复。
- 未验证 AIDL 多调用方并发。
- 未验证真实用户长期记忆提取质量。

## 11. 最终评价

当前 Memory 系统的架构方向值得保留，但完成状态需要重新定义：它不是一个已经完整落地的四层记忆系统，而是一个包含长期记忆、压缩器、SessionStore 雏形和 trace 能力的半集成版本。

最需要优先处理的不是继续增加新能力，而是把已有能力接成真实闭环：

1. 主 ChatMemory 必须按 user/session 隔离。
2. SessionMemoryStore 必须成为主短期记忆来源，或明确放弃该层设计。
3. 压缩必须写回当前活跃上下文。
4. 长期记忆注入必须去重并保持正确消息角色。
5. 用户身份、隐私删除和测试矩阵必须补齐。

在这些问题修复前，当前 Memory 功能可以用于 Demo 探索，但不建议作为稳定车机 AI 中枢的记忆基础能力对外宣称完成。
