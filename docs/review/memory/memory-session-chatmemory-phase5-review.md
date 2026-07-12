# Memory Session ChatMemory Phase 5 验收审查

**审查日期：** 2026-07-09
**审查范围：** Phase 5 长期记忆与共享短期历史的边界加固（Task 5.1～5.3）
**审查依据：** `docs/plan_overall/2026-07-09-memory-session-chatmemory-implementation-plan.md` Phase 5 全部任务
**审查人：** Claude Code

---

## 一、总体评估

**通过验收，无问题。** Phase 5 是轻量加固阶段，主要验证 Phase 4 的改动是否满足长期记忆/短期记忆的边界约束，并新增一个 speaker 标记的验收测试。生产代码无需修改。

---

## 二、本阶段做了什么

### Task 5.1：长期记忆写入使用原始用户文本 ✅

新增测试 `speakerFormattedMessageInChatMemoryRawUserInputOnTurnComplete`，用 `FakeStore` + `SessionChatMemoryProvider` 验证 5 个断言：

1. ChatMemory 中的消息包含 `[speaker=user_a]` 标记
2. ChatMemory 中包含用户输入文本 `打开空调`
3. 原始 `userInput` 不包含 `[speaker=` 前缀
4. formatted 消息与 raw 消息不同
5. 底层 store 同样保存了带 speaker 标记的消息（持久化正确）

生产代码（AgentLoopOrchestrator）在 Phase 4 已实现：
- `chatMemory.add(UserMessage.from(SpeakerMessageFormatter.formatUserMessage(userId, userInput)))` — 带 speaker
- `memoryOrchestrator.onTurnComplete(..., userInput, ...)` — 原始文本

### Task 5.2：复核长期记忆唯一注入点 ✅

验证 Phase 4 已形成的不变量：

| 不变量 | 验证方式 | 状态 |
|--------|---------|------|
| ChatMemory.messages() 无 SystemMessage | 代码审查 | ✅ `buildSystemPromptMessage` 不写入 chatMemory |
| ChatMemory.messages() 无 `【长期记忆】` | 代码审查 | ✅ 同上 |
| TEXT preprocessor 链不含 MemoryPreProcessor | 已有测试 | ✅ `textPersonaPreprocessorChainExcludesMemoryPreProcessor` |
| MemoryPreProcessor 保留为 legacy/no-op | 代码审查 | ✅ 注释标明"不再使用" |

### Task 5.3：压缩 speaker 噪声清理（可选，未执行）

按计划标注为非阻塞项，未实现。当前压缩摘要中可能出现 `用户：[speaker=user_a] 打开空调` 的冗余格式，但不影响功能。

---

## 三、审查结论

**Phase 5 通过验收，可以进入 Phase 6。**

| 检查项 | 状态 |
|--------|------|
| Task 5.1：speaker/raw 边界测试 | ✅ 新增 1 测试 |
| Task 5.2：长期记忆唯一注入点复核 | ✅ 3 项不变量全部确认 |
| Task 5.3：压缩 speaker 噪声 | ⏩ 可选，未执行 |
| 全量测试 | ✅ BUILD SUCCESSFUL |
