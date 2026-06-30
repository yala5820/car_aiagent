# Memory 管理系统重构总结

## 概述

对 AIAgent 项目的记忆系统进行了一次从无到有的完整设计：将简陋的 SQLite 消息滑动窗口升级为包含 Session 管理、自动压缩、长期记忆提取、多用户隔离的四层记忆架构。

---

## 一、重构前的记忆设计

### 1.1 历史沿革

重构前的记忆系统是 LangChain4j 开箱即用的 `MessageWindowChatMemory` + `PersistentChatMemorySqlite` 组合，由早期开发者一次性集成后未再改动。

### 1.2 技术细节

**存储层**（`langchain4j/chat_memory_sqlite/PersistentChatMemorySqlite.java`）：

```sql
-- 仅一张表，全局共享
CREATE TABLE chat_memory (
    memory_id TEXT PRIMARY KEY,   -- 固定为 "default"
    messages TEXT                 -- JSON 序列化的 ChatMessage 列表
);
```

- 所有对话共享同一个 `memory_id` → 同一行记录
- 消息以 JSON 整体读写，无增量操作
- 无 try/catch，数据库异常直接向上抛出

**记忆策略**：

| 引擎 | 策略 | 窗口 | 持久化 |
|------|------|------|--------|
| MainAgentLoop | MessageWindowChatMemory | 50 条 | MainAgentMemory.db |
| ChatServer | MessageWindowChatMemory | 50 条 | ChatMemory.db |
| SceneServer | MessageWindowChatMemory | 50 条 | SceneMemory.db |
| AgentLoopOrchestrator | MessageWindowChatMemory | 50 条 | ChatMemory.db |

所有引擎使用消息数滑动窗口，超过 50 条直接丢弃最旧的消息。

### 1.3 AgentLoopOrchestrator 中记忆的使用方式

记忆在 `AgentLoopOrchestrator.execute()` 中有三处操作：

```
① 执行前: ensureSystemPrompt()
   → chatMemory.messages().isEmpty() 时注入 SystemMessage
   → 写入 UserMessage(userInput)

② 执行中: chatMemory.add(aiMessage)
   chatMemory.add(ToolExecutionResultMessage.from(toolReq, result))

③ 清除: cleanMemory()
   → chatMemory.clear()
   → 重新注入 SystemMessage
```

**不存在**：Session 隔离、跨 Session 持久化、摘要压缩、长期记忆、多用户支持。

---

## 二、总体做了什么

### 2.1 新建了 10 个文件

| 文件 | 层级 | 行数 | 职责 |
|------|------|------|------|
| `SessionMemoryStore.java` | 存储层 | ~200 | ChatMemoryStore 实现 + Session 管理 + 复合主键 |
| `MemoryEntry.java` | 模型层 | ~50 | 长期记忆条目值类型 |
| `LongTermMemoryStore.java` | 存储层 | ~165 | 用户偏好/事实持久化（SQLite） |
| `MemoryCompressor.java` | 压缩层 | ~150 | Token 超限时 LLM 摘要旧消息 |
| `MemoryExtractor.java` | 提取层 | ~120 | 对话结束后 LLM 提取可记忆信息 |
| `MemoryCandidate.java` | 模型层 | ~30 | 提取候选值类型 |
| `SessionManager.java` | 管理层 | ~100 | Session 生命周期控制 |
| `UserMemoryContext.java` | 聚合层 | ~100 | 单用户所有记忆的聚合体 |
| `MemoryOrchestrator.java` | 协调层 | ~130 | 协调器，AgentLoop 的唯一交互点 |
| `MemoryPreProcessor.java` | Agent 集成 | ~40 | 注入长期记忆到每轮上下文 |
| `MemoryPostProcessor.java` | Agent 集成 | ~40 | 触发记忆提取 |

### 2.2 修改了 3 个文件

| 文件 | 改动 |
|------|------|
| `AgentLoopOrchestrator.java` | 新增 MemoryOrchestrator 字段，系统提示词注入长期记忆，执行后触发提取 |
| `AgentConfigFactory.java` | 新增 MemoryPreProcessor/PostProcessor 到 chat persona |
| `AIAgentService.kt` | 创建 MemoryOrchestrator，Session 控制指令，onDestroy 清理 |

### 2.3 新增的存储表

```sql
-- Session 元数据
CREATE TABLE sessions (
    user_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    ended_at INTEGER,
    message_count INTEGER DEFAULT 0,
    token_estimate INTEGER DEFAULT 0,
    compression_count INTEGER DEFAULT 0,
    is_active INTEGER DEFAULT 1,
    PRIMARY KEY (user_id, session_id)
);

-- Session 消息（ChatMemoryStore 接口）
CREATE TABLE session_messages (
    memory_id TEXT PRIMARY KEY,     -- "{userId}_{sessionId}"
    messages TEXT NOT NULL
);

-- 长期记忆
CREATE TABLE long_term_memory (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id TEXT NOT NULL,
    category TEXT NOT NULL,         -- preference / fact / habit / rule
    key_text TEXT NOT NULL,
    value_text TEXT NOT NULL,
    confidence REAL DEFAULT 1.0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    access_count INTEGER DEFAULT 0,
    UNIQUE(user_id, category, key_text)
);
```

---

## 三、设计的记忆系统详解

设计了一个**四层记忆架构**，每一层解决一个独立的问题：

```
┌──────────────────────────────────────────────────────────┐
│                    AgentLoopOrchestrator                    │
│  (通过 MemoryOrchestrator 与记忆系统交互)                    │
└──────────────────────┬────────────────────────────────────┘
                       │
┌──────────────────────▼────────────────────────────────────┐
│                    MemoryOrchestrator                       │
│                    记忆协调器                               │
│  prepareSystemPrompt(userId, basePrompt) → 注入长期记忆     │
│  onTurnComplete(userId, msgs, tokens, input, output)       │
│    → 提取长期记忆 + 检查压缩                                  │
│  startNewSession(userId) → 开启新对话                       │
│  shutdown() → 程序关闭清理                                  │
└────┬──────────┬──────────────┬───────────────────────────┘
     │          │              │
     ▼          ▼              ▼
┌─────────┐ ┌──────────┐ ┌──────────────┐
│ Session │ │  LongTerm │ │  Memory     │
│ Manager │ │  Memory   │ │  Compressor │
│ (生命周期)│ │ (持久偏好) │ │ (自动压缩)   │
└────┬────┘ └─────┬────┘ └──────┬───────┘
     │            │             │
     ▼            ▼             │
┌─────────┐ ┌──────────┐       │
│ Session │ │ LongTerm │       │
│ Store   │ │ Store    │       │
│ (SQLite)│ │ (SQLite) │       │
└─────────┘ └──────────┘       │
     │                         │
     └─────────────────────────┘
               │
               ▼
┌──────────────────────────────┐
│    TokenWindowChatMemory      │
│  (LangChain4j 内存级窗口)     │
│  + ChatMemoryStore 持久化     │
└──────────────────────────────┘
```

### 3.1 第一层：Session 管理层

**解决的问题**：程序重启后旧记忆与新对话混在一起，没有"会话"边界。

**方案**：`SessionManager` + `SessionMemoryStore`

- Session ID 格式：`S_20250701_143021`（用户ID + 时间戳）
- 程序启动时：自动创建或恢复上次活跃 Session
- 用户主动指令（@#%^ClearChatMemory）：结束旧 Session，创建新 Session
- 程序关闭时：标记 Session 为 inactive（数据不删，下次启动不自动恢复）
- ChatMemoryStore 使用复合主键 `(userId, sessionId)` 隔离数据

```
程序启动 → 查询活跃 Session
          ├─ 有 → 恢复之
          └─ 无 → 创建新 S_20250701_143021

用户发送"新对话" → endSession(S_20250701_143021)
                 → 创建新 S_20250701_143022

程序关闭 → endSession(current) → 标记 inactive
```

### 3.2 第二层：长期记忆层

**解决的问题**：用户说过的偏好（"我喜欢 22℃"）在下次对话中丢失，需要跨 Session 保留。

**方案**：`LongTermMemoryStore` + `MemoryExtractor`

存储四类用户信息：

| 类别 | 示例 | 置信度影响 |
|------|------|-----------|
| preference（偏好） | "空调温度偏好 22℃"，"喜欢听轻音乐" | 重复确认 → 提升 |
| fact（事实） | "今天是用户的生日"，"用户开的是 Model 3" | 单次较高 |
| habit（习惯） | "天热时先开窗再开空调" | 出现 2+ 次 |
| rule（规则） | "开车时不要打扰我" | 用户明确 → 高置信度 |

**提取流程**：

```
每轮对话结束后
  │
  MemoryExtractor.extract(userMessage, aiResponse)
  │
  ├─ 调用 LLM（qwen-turbo）分析对话
  ├─ LLM 输出 JSON 数组:
  │  [{"category":"preference","key":"空调温度","value":"喜欢22度","confidence":0.9}]
  │
  └─ LongTermMemoryStore.upsertMemory(...)
       → INSERT OR REPLACE（同(user_id, category, key)覆盖更新）
```

**注入流程**：

```
AgentLoop 执行前
  │
  MemoryOrchestrator.prepareSystemPrompt(userId, basePrompt)
  │
  ├─ LongTermMemoryStore.getUserMemories(userId)
  ├─ 格式化为:
  │  "【长期记忆】
  │   用户偏好：
  │    · 空调温度偏好 22℃ (置信度: 0.9)
  │   已知事实：
  │    · 用户在赶飞机 (2025-07-01)"
  │
  └─ 拼接到 SystemPrompt 尾部
```

### 3.3 第三层：自动压缩层

**解决的问题**：消息过多时不是简单丢弃，而是保留摘要信息。

**方案**：`MemoryCompressor`

| 参数 | 值 |
|------|-----|
| MAX_TOKENS（触发阈值） | 4000 |
| TARGET_TOKENS（压缩目标） | 2000 |
| COMPRESS_KEEP_LAST（保留最近轮数） | 5 |
| 摘要模型 | qwen-turbo |

**压缩策略**：

```
当前 Token > 4000？
  │
  ├─ 否 → 不操作
  │
  └─ 是 → ① 分离 SystemMessage（始终保留）
          ② 分离最近 COMPRESS_KEEP_LAST 轮（5轮不压缩）
          ③ 其余旧消息 → LLM 摘要
          ④ 组装新消息列表:
             [SystemMessage, 摘要UserMessage, 最近5轮]
          ⑤ 持久化到 SessionMemoryStore
          ⑥ compressionCount += 1
```

**压缩 Prompt**：
```
请对以下对话历史进行摘要，保留所有关键信息：
- 用户的偏好和要求
- AI 已经执行的操作和结果
- 未解决的问题
- 重要的上下文（位置、时间、车辆状态等）

对话历史：
{{messages}}

摘要（请用中文，控制在 500 字以内）：
```

**安全措施**：
- 有未完成的工具调用时不压缩（避免分裂 AiMessage/ToolExecutionResultMessage 对）
- 摘要为空时跳过压缩
- 旧消息列表为空时跳过

### 3.4 第四层：多用户隔离

**解决的问题**：一个 App 可能服务多个家庭成员（驾驶员/乘客），记忆不能串。

**方案**：`MemoryOrchestrator` 内部维护 `ConcurrentHashMap<String, UserMemoryContext>`

```
MemoryOrchestrator
  │
  └─ userContexts: Map<String, UserMemoryContext>
       ├─ "default_user" → UserMemoryContext { sessionMemory, longTermMemory }
       ├─ "user_driver"  → UserMemoryContext { sessionMemory, longTermMemory }
       └─ "user_passenger" → UserMemoryContext { sessionMemory, longTermMemory }
```

当前默认 userId = `"default_user"`。后续通过 AIDL 传入 userId 即可切换。每个 UserMemoryContext 拥有独立的：
- `SessionManager`（独立 Session 生命周期）
- `LongTermMemoryStore` 视图（WHERE user_id = ?）
- Token 统计和压缩计数

### 3.5 数据流全景

```
用户输入 "帮我调到22度"
  │
  ▼
AgentLoopOrchestrator.execute(input, extraContext)
  │
  ├─ ① MemoryOrchestrator.prepareSystemPrompt("default_user", basePrompt)
  │     → 查询长期记忆 → 拼接到 SystemPrompt → 写入 chatMemory
  │     → "角色定义：你是一位……\n\n【长期记忆】\n用户偏好：\n · 空调温度偏好 22度"
  │
  ├─ ② MemoryPreProcessor.prepare(ctx) → 第二轮起不再注入长期记忆
  │
  ├─ ③ LLM 返回 "set_ac_temperature(22)"
  │     → 工具执行 → chatMemory.add()
  │
  ├─ ④ MemoryPostProcessor.process(output, ctx)
  │     → MemoryOrchestrator.onTurnComplete("default_user", chatmessages, tokenEstimate, "帮我调到22度", "已调节至22度")
  │        → MemoryExtractor.extract("帮我调到22度", "已调节至22度")
  │           → LLM 返回 [{"category":"preference","key":"空调温度","value":"喜欢22度","confidence":0.85}]
  │        → LongTermMemoryStore.upsertMemory("default_user", preference, "空调温度", "喜欢22度", 0.85)
  │        → MemoryCompressor.check(messages, tokenEstimate)
  │           → Token 3800 < 4000, 不压缩
  │
  ▼
返回 AgentResult.success("已调节至22度")
```

---

## 四、发现的问题与解决过程

### 问题 1：无 Session 边界，记忆全局共享

**发现**：

所有记忆存储在同一张表、同一个 memory_id 下。程序重启后旧消息和新对话混在一起。用户没有"新对话"的概念——`cleanMemory()` 是唯一清除方式，但它把记忆全部清空（包括系统提示词），不是优雅的 Session 切换。

另外，三个引擎（MainAgentLoop/ChatServer/SceneServer）各有独立的 SQLite 库（MainAgentMemory.db/ChatMemory.db/SceneMemory.db），数据不互通。

**思考**：

汽车场景中，驾驶员的对话天然有"会话"属性——上车开始到下车结束为一次会话。中间可能有长暂停（等红灯、加油），但仍是同一次对话。程序重启（关机后再上车）应该开始新会话。

LangChain4j 的 `ChatMemoryStore` 接口用 `Object memoryId` 做主键。通过使用 `{userId}_{sessionId}` 作为 memoryId，可以在同一个 Store 实例中隔离不同 Session 的数据。不需要分库。

**解决**：

设计 `SessionManager` 控制 Session 生命周期：

```
程序启动 → getOrCreateSession(userId)
            → 查 sessions 表 is_active=1
            ├─ 有 → 恢复（加载消息）
            └─ 无 → 创建新 Session（S_20250701_143021）

用户发"新对话" → startNewSession(userId)
                → endSession(old) → createSession(new)

程序关闭 → endSession(current)（标记 inactive，不删除数据）
```

`SessionMemoryStore` 实现 `ChatMemoryStore`，使用复合 memoryId：

```java
// memoryId = "default_user_S_20250701_143021"
// 同一 Store 实例支持任意多 Session/User 的组合
```

### 问题 2：无压缩机制，超过 50 条直接丢弃

**发现**：

`MessageWindowChatMemory(50)` 做滑动窗口，第 51 条消息加入时最旧的一条被丢弃。不分辨信息价值——用户早上说的"我赶时间"（重要上下文）和"今天天气不错"（闲聊）有同等的被丢弃概率。

LangChain4j 提供了两种窗口策略：`MessageWindowChatMemory`（消息计数）和 `TokenWindowChatMemory`（Token 计数）。文档推荐生产环境使用 TokenWindowChatMemory 加 Tokenizer 做更精确的容量控制。

**思考**：

简单的抛弃头消息是最懒的做法。更好的做法是**压缩而非抛弃**——将旧消息通过 LLM 摘要为一段保留关键信息的话，替换掉原始消息列表。这样可以在有限的 Token 窗口内保留最核心的上下文。

关键设计约束：
1. SystemMessage 始终保留，不参与压缩
2. 最近 N 轮对话（当前上下文）不压缩，只压缩更早的历史
3. 有未完成的工具调用对（AiMessage → ToolExecutionResultMessage）时不压缩
4. 压缩后需要持久化到 Store

**解决**：

实现 `MemoryCompressor`，在 Token 超过 4000（LangChain4j 推荐的生产环境阈值）时触发：

```
触发: tokenEstimate > MAX_TOKENS(4000)
执行: 
  ① 分离 SystemMessage
  ② 分离最近 COMPRESS_KEEP_LAST(5) 轮
  ③ 旧消息 → LLM 摘要
  ④ 组装: [SystemMessage, 摘要UserMessage, 最近5轮]
  ⑤ 持久化
  ⑥ 压缩计数 +1

效果: Token 从 4000+ → ~2000
```

### 问题 3：无长期记忆，用户偏好每次对话重新学习

**发现**：

用户说"空调调到 22 度" → AI 执行 → 对话结束。下次启动程序："我有点冷" → AI 不知道用户喜欢 22 度。每次对话都是全新的，用户的偏好、习惯、已知事实全部丢失。

这是被 LangChain4j 社区称为"冷启动问题"的典型场景——ChatMemory 只解决"同一对话内的上下文"，不解决"跨对话的用户知识"。

**思考**：

长期记忆的本质是一个"读-写"过程：
- **写**：从对话中提取值得记住的信息（偏好、事实、习惯、规则）
- **读**：在下一轮/下次对话开始时注入回 SystemPrompt

提取需要 LLM 做结构化输出（JSON），写入一个独立的持久化存储。注入时格式化后拼接到 SystemPrompt 尾部。

关键设计决策：
- 提取频率：每轮对话后执行（不是每轮都提取，但提取器会返回空数组）
- 写入策略：upsert（同 category + key 覆盖），不是 append
- 置信度：LLM 自己评估（0.0-1.0），低于 0.3 的不写入
- 遗忘机制：未实现自动衰减，但 schema 预留了 access_count 字段

**解决**：

`MemoryExtractor` + `LongTermMemoryStore` 组合：

```java
// 写入侧（每轮后）
MemoryExtractor.extract(userMessage, aiResponse)
  → LLM 输出: [{"category":"preference","key":"空调温度","value":"喜欢22度","confidence":0.85}]
  → LongTermMemoryStore.upsertMemory("default_user", preference, "空调温度", "喜欢22度", 0.85)

// 读取侧（执行前）
LongTermMemoryStore.getUserMemories("default_user")
  → formatAsPromptContext(memories)
  → "【长期记忆】\n用户偏好：\n · 空调温度偏好 22度\n · 通常拒绝开窗"
  → 拼接到 SystemPrompt
```

### 问题 4：无多用户支持

**发现**：

整个系统只有一个用户身份。所有记忆存储在全局 scope 下。如果 App 需要服务多个家庭成员（例如通过语音特征或 AIDL 传入用户 ID），现有的架构无法扩展。

**思考**：

多用户隔离本质上是"按 userId 分片"。LangChain4j 的 AiServices 模式通过 `@MemoryId` 注解 + `ChatMemoryProvider` 实现，但项目使用低层 ChatModel API。需要在 `MemoryOrchestrator` 层手动管理用户上下文池。

**解决**：

`MemoryOrchestrator` 内部维护 `ConcurrentHashMap<String, UserMemoryContext>`：

```java
userContexts.computeIfAbsent(userId, id -> new UserMemoryContext(id, ...));
```

每个 UserMemoryContext 拥有独立的：
- `SessionManager` → 独立的 Session 追踪（S_user1_xxx vs S_user2_xxx）
- `LongTermMemoryStore` → 通过 SQL WHERE user_id = ? 隔离
- token 统计和压缩计数 → 不互相影响

当前默认 userId = `"default_user"`。AIDL 传入 userId 后即可扩展。

### 问题 5：AgentLoopOrchestrator 中记忆与业务逻辑耦合

**发现**：

AgentLoopOrchestrator 直接创建 ChatMemory、直接操作 PersistantChatMemorySqlite、在 execute() 方法中多处直接调用 chatMemory.add()。系统提示词直接通过 PromptManager.render() 生成，没有机会注入长期记忆上下文。

**思考**：

按我们之前设计的组件化管道架构，记忆操作应该通过 PreProcessor/PostProcessor 注入，而不是散落在 Orchestrator 的核心循环中。具体来说：

- SystemPrompt 的准备（含长期记忆注入） → 由 MemoryOrchestrator 在 Orchestrator 调用前完成
- 记忆提取和压缩 → 由 MemoryPostProcessor 触发，实际执行在 MemoryOrchestrator
- 长期记忆注入 → 由 MemoryPreProcessor 在首轮迭代时注入

**解决**：

Orchestrator 不再直接知道记忆细节，通过三个集成点与 MemoryOrchestrator 交互：

```java
// 集成点 1：SystemPrompt 准备（execute() 中）
String sysPrompt = memoryOrchestrator.prepareSystemPrompt(userId, basePrompt);
// → 注入了长期记忆上下文

// 集成点 2：PreProcessor 注入长期记忆（首轮）
MemoryPreProcessor.prepare(ctx)
// → UserMessage("【用户记忆参考】用户偏好：...")

// 集成点 3：PostProcessor 触发提取/压缩（每轮后）
MemoryPostProcessor.process(output, ctx)
// → MemoryExtractor + MemoryCompressor
```

---

## 五、改进后的项目架构

### 5.1 新增文件结构

```
app/src/main/java/com/hirain/aiagent/memory/
├── MemoryOrchestrator.java      # 记忆协调器（~130行）
├── SessionManager.java          # Session 生命周期管理（~100行）
├── SessionMemoryStore.java      # Session 持久化 + ChatMemoryStore（~200行）
├── UserMemoryContext.java       # 单用户记忆聚合体（~100行）
├── LongTermMemoryStore.java     # 长期记忆持久化（~165行）
├── MemoryEntry.java             # 长期记忆条目（~50行）
├── MemoryCompressor.java        # Token 超限自动压缩（~150行）
├── MemoryExtractor.java         # 对话记忆提取（~120行）
└── MemoryCandidate.java         # 提取候选值类型（~30行）

core/preprocessor/
├── MemoryPreProcessor.java      # 新增 — 注入长期记忆

core/postprocessor/
├── MemoryPostProcessor.java     # 新增 — 触发提取/压缩
```

### 5.2 改进后记忆架构图

```
                        AgentLoopOrchestrator
                               │
                    MemoryOrchestrator
                    ┌──────┼──────┬──────┐
                    │      │      │      │
              Session   LongTerm  Memory  Memory
              Manager   Memory  Compressor Extractor
              (生命周期)  (持久偏好) (压缩)  (提取)
                    │      │      │
               ┌────┘      │      └────┐
               ▼           ▼           ▼
      SessionMemoryStore  LongTermMemoryStore
      ┌────────────────┐  ┌────────────────────┐
      │ sessions 表    │  │ long_term_memory   │
      │ session_msgs   │  │ (user_id,category, │
      │ (composite PK) │  │  key,value,conf)   │
      └────────────────┘  └────────────────────┘
               │
               ▼
        TokenWindowChatMemory
        (LangChain4j 内置)
```

### 5.3 关键变更统计

| 指标 | 重构前 | 重构后 | 变化 |
|------|--------|--------|------|
| 记忆层级 | 1 层（MessageWindowChatMemory） | 4 层（Session + SessionMsg + LongTerm + Compressor） | +3 |
| 存储引擎 | PersistentChatMemorySqlite | SessionMemoryStore + LongTermMemoryStore | +2 |
| 存储表 | 1 张 | 3 张（sessions, session_messages, long_term_memory） | +2 |
| Session 支持 | 无 | SessionManager + 生命周期控制 | +1 |
| 自动压缩 | 无 | MemoryCompressor（4000 token 触发） | +1 |
| 长期记忆 | 无 | MemoryExtractor + LongTermMemoryStore | +1 |
| 多用户隔离 | 无 | ConcurrentHashMap + (userId, sessionId) 复合主键 | +1 |
| 记忆文件数 | 1 个（PersistentChatMemorySqlite） | 9 个 Java 文件 | +8 |
| 代码行数（记忆层） | ~80 行 | ~1050 行 | +970 |
| 集成点 | 散落在引擎中 | MemoryOrchestrator 统一接口 + PreProcessor/PostProcessor | 结构优化 |

### 5.4 架构原则

1. **四层分离** — Session 管理层、长期记忆层、压缩层、提取层各司其职，互不侵入
2. **存储通过 ChatMemoryStore 抽象** — 所有持久化走接口，可替换实现（SQLite/Redis/文件）
3. **压缩不丢失信息** — 不是简单丢弃，而是通过 LLM 摘要保留关键上下文
4. **长期记忆可跨 Session** — 用户偏好从一轮对话提取，在下次启动时注入
5. **多用户原生支持** — userId 维度隔离，当前默认单用户，后续零成本扩展
6. **不侵入 LangChain4j 框架** — 基于低层 ChatModel API，不使用 AiServices，但架构与框架设计哲学一致

### 5.5 遗留问题

1. **Token 估算** — 当前 TokenWindowChatMemory 切换被推迟，仍然使用 MessageWindowChatMemory。需要接入 Tokenizer（通过 LangChain4j 的 TokenCountEstimator）
2. **置信度衰减** — LongTermMemory 预留了 access_count 和 updated_at 字段，但未实现自动衰减逻辑。长期不访问的记忆应逐渐降权
3. **Session 恢复** — 当前程序重启后创建新 Session，不自动加载旧 Session 消息。如果需要"记忆上次说到哪"的功能，需要恢复逻辑
4. **提取器偶尔失败** — MemoryExtractor 依赖 LLM 输出 JSON，偶尔输出不符合格式会解析失败，当前默认返回空数组，后续可增加 retry 逻辑
