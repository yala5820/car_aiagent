# Memory Session ChatMemory — 测试结果与验收文档

**编制日期：** 2026-07-09
**覆盖阶段：** Phase 0 ～ Phase 6
**项目：** AIAgent Memory 模块 session-scoped ChatMemory 改造

---

## 一、测试结果汇总

### 单元测试结果

| 测试范围 | 命令 | 结果 |
|---------|------|------|
| Memory 模块 | `testDebugUnitTest --tests "com.hirain.aiagent.memory.*"` | ✅ BUILD SUCCESSFUL |
| Core 模块 | `testDebugUnitTest --tests "com.hirain.aiagent.core.*"` | ✅ BUILD SUCCESSFUL |
| Runtime 模块 | `testDebugUnitTest --tests "com.hirain.aiagent.runtime.*"` | ✅ BUILD SUCCESSFUL |
| Context 模块 | `testDebugUnitTest --tests "com.hirain.aiagent.context.*"` | ✅ BUILD SUCCESSFUL |
| 全量 JVM 单测 | `testDebugUnitTest` | ✅ BUILD SUCCESSFUL |
| Debug 构建 | `assembleDebug` | ✅ BUILD SUCCESSFUL |

### 各 Phase 测试清单

| Phase | 新增/修改的测试 | 状态 |
|-------|---------------|------|
| Phase 0 | `SessionIdResolverTest`（3 tests）、`AgentRuntimeResolvedSessionTest`（3 tests）、`SessionMemoryStoreDeleteTest`（@Ignore 需设备） | ✅ |
| Phase 1 | `SessionMemoryIdsTest`（4 tests）、`SpeakerMessageFormatterTest`（3 tests） | ✅ |
| Phase 2 | 无新测试（`ConversationManagerTest` 验证兼容性） | ✅ |
| Phase 3 | `SessionChatMemoryProviderTest`（4 tests）、`MemorySnapshotTest`（1 test） | ✅ |
| Phase 4 | `AgentLoopOrchestratorSessionMemoryTest` 新增 1 test | ✅ |
| Phase 5 | `AgentLoopOrchestratorSessionMemoryTest` 新增 speaker 分离测试 | ✅ |
| Phase 6 | 验证回归 | ✅ |

---

## 二、未运行的测试

### `connectedDebugAndroidTest`

**未运行。原因：** 当前环境无连接设备或模拟器。

### `SessionMemoryStoreDeleteTest`

**标记为 @Ignore。原因：** 测试依赖 Android SQLiteOpenHelper，JVM 环境无法运行。
**待办：** 在模拟器或真机上运行此测试，验证全局删除语义。

### `AgentLoopOrchestratorSessionMemoryTest` 中的两个约束骨架

- `textPathFailsWhenSessionIdMissing` — **无断言**。因 `AgentLoopOrchestrator` 构造函数依赖 Android `Context`，JVM 无法构造。约束：缺失 sessionId 应返回 `INVALID_CONFIG`。
- `textPathUsesSessionMemoryNotFallback` — **无断言**。约束：必须走 `chatMemoryForSession`，不走 `fallbackChatMemory`。

**建议：** 如需 JVM 验证，需先解除 `PromptManager` 的 Android Context 依赖。当前已注明"未在 JVM 中验证"。

### 真实 SQLite 行为验证（待验证点）

需在 Android 设备上人工验证以下 4 点：

1. **v2 → v3 upgrade 不崩溃**：`aiagent_memory.db` 从旧版本升级时 `onUpgrade` 执行 v3 迁移的 `CREATE TABLE IF NOT EXISTS session_messages` 不崩溃
2. **`MessageWindowChatMemory(id=sessionId, store=SessionMemoryStore)` 可正常 update/get**：创建 ChatMemory 实例后添加消息，验证消息可从 store 读回
3. **`deleteGlobalSession(sessionId)` 正确性**：两个 user 共享同一 session，调用 `deleteGlobalSession` 后验证所有 user metadata 行和短期消息均被删除
4. **`provider.replaceMessages(sessionId, compressed)` 后缓存一致**：调用 replaceMessages 后，不重建 provider，同一 session 的 `getOrCreate().messages()` 立即返回压缩后的消息

---

## 三、人工验收用例

以下验收用例需要在 Android 设备/模拟器上手动执行。

### 前提条件

- 临时禁用 `MemoryExtractor` 或使用不易被长期记忆提取的短句（如"临时编号是 XXXX"）
- 避免使用"我喜欢 22 度"这类偏好句测试短期隔离——它可能被写入长期记忆并干扰结论
- 长期记忆验收与短期隔离验收分开执行（不放在同一轮测试中）

### 用例 1：同 session 多发言人共享短期上下文

**测试序列：**

```text
请求 1：sessionId=S_demo_1, userId=user_a, personaId=chat, text="上一句话的临时编号是 7788"
请求 2：sessionId=S_demo_1, userId=user_b, personaId=chat, text="刚才他说的临时编号是多少？"
```

**预期结果：**

- 第二轮模型从短期历史中看到 user_a 的上一轮发言（`[speaker=user_a] 上一句话的临时编号是 7788`）
- 不依赖长期记忆也能回答 `7788`
- 通过 logcat 确认短期历史有 `[speaker=user_a]` 标记

### 用例 2：不同 session 隔离短期上下文

**测试序列：**

```text
请求 1：sessionId=S_demo_1, userId=user_a, personaId=chat, text="上一句话的临时编号是 alpha-7788"
请求 2：sessionId=S_demo_2, userId=user_a, personaId=chat, text="上一句话的临时编号是什么？"
```

**预期结果：**

- `S_demo_2` 不应从短期历史中读取 `S_demo_1` 的 `alpha-7788`
- 如果长期记忆未禁用且模型仍答出该值，检查 `LongTermMemoryStore` 而非判定短期隔离失败

### 用例 3：persona 不切分短期记忆

**测试序列：**

```text
请求 1：sessionId=S_demo_1, userId=user_a, personaId=chat, text="临时编号是 7788"
请求 2：sessionId=S_demo_1, userId=user_a, personaId=friendly, text="临时编号是多少？"
```

**预期结果：**

- 第二轮仍能看到第一轮短期历史（回答 7788）
- `personaId` 变化只影响系统提示词风格，不改变 short-term memory key

### 用例 4：长期记忆按当前发言人读取

**测试序列：**

```text
请求 1：sessionId=S_demo_1, userId=user_a, personaId=chat, text="我喜欢 22 度空调"
请求 2：sessionId=S_demo_1, userId=user_b, personaId=chat, text="我喜欢 26 度空调"
请求 3：sessionId=S_demo_2, userId=user_a, personaId=chat, text="我喜欢多少度空调？"
```

**预期结果：**

- user_a 的长期记忆是 22 度
- user_b 的长期记忆是 26 度
- `S_demo_2` 中 user_a 不读取 user_b 的长期记忆
- 执行后先检查 `LongTermMemoryStore` 中 user_a/user_b 的记录已完成提取

---

## 四、验证记录

| 日期 | 验证人 | 内容 | 结果 |
|------|--------|------|------|
| 2026-07-09 | CI | JVM 单测（memory/core/runtime/context） | ✅ 全通过 |
| 2026-07-09 | CI | 全量 testDebugUnitTest | ✅ BUILD SUCCESSFUL |
| 2026-07-09 | CI | assembleDebug | ✅ BUILD SUCCESSFUL |
| — | — | connectedDebugAndroidTest | ⏸ 无设备，未运行 |
| — | — | SQLite 行为验证（4 点） | ⏸ 需设备 |
| — | — | 人工验收用例（4 项） | ⏸ 需设备/模拟器 |
