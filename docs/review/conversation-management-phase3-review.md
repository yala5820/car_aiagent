# Conversation Management Phase 3 验收审查

**审查日期：** 2026-07-07
**审查范围：** Phase 3 全部交付（userId/personaId 规范化、AgentRuntime 透传、第一版 Persona 选择）
**审查人：** Claude Code

---

## 一、总体评估

**通过验收，无阻塞问题。** 所有核心功能均正确实现：RequestSessionFactory 已正确分离 userId/sessionId、personaId 流转链路完整、chat/friendly/concise 三套独立 orchestrator 就绪。92 个单测全部通过，5 个受限文件零 diff。

---

## 二、审查结果清单

| 检查项 | 结果 |
|--------|------|
| RequestSessionFactory 移除 `personaId` 参数，从 request 读取 | ✅ |
| userId 规范化为 `nonEmpty(request.getUserId(), "default_user")`，不再借用 sessionId | ✅ |
| RequestSession 新增 `clientMessageId` 字段 + getter | ✅ |
| orchestratorContext 写入 `user_id/persona_id/session_id/client_message_id` | ✅ |
| AgentRuntime.startSession() 使用新工厂签名 | ✅ |
| 新增 `startSession_preservesPersonaIdFromRequest` 测试 | ✅ |
| AIAgentService `ensureRequestId` / `normalizeUserId` / `normalizePersonaId` 方法 | ✅ |
| TEXT trace 使用规范化 requestId/userId/personaId（替代硬编码 chat + sessionId） | ✅ |
| VOICE 上下文透传 userId/personaId | ✅ |
| `supportedTextPersonas = setOf("chat", "friendly", "concise")` | ✅ |
| `textOrchestrators` 初始化顺序正确（在 agentRuntime 之前） | ✅ |
| `AgentConfigFactory.createTextPersona` 统一入口 + 3 套 prompt template | ✅ |
| `PromptConstants` 新增 FRIENDLY / CONCISE 常量 | ✅ |
| 2 个新的 .txt prompt 模板文件 | ✅ |
| AgentRuntime executor 按 personaId 路由到对应 orchestrator | ✅ |
| 非法 personaId 降级到 chat + 日志警告 | ✅ |
| 编译 BUILD SUCCESSFUL | ✅ |
| 全量 92 个单测通过 | ✅ |
| 5 个受限文件零 diff | ✅ |

---

## 三、观察项（非阻塞，无需修复）

### 3.1 VOICE 路径仍使用 `chatOrchestrator`（旧 memory store）

VOICE 透传 `userId/personaId`，但最终调用 `chatOrchestrator.execute()`（`memoryStoreId="ChatMemory"`），而非 `textOrchestrators[personaId]`（`memoryStoreId="ChatMemory_chat"`）。同一个人物的 TEXT 与 VOICE 请求将使用不同的 ChatMemory 存储。

**当前不影响功能**。计划明确声明 VOICE 不迁移，两段记忆是独立分支，不会有数据覆盖。

### 3.2 TEXT "chat" persona 的 ChatMemory 从 `ChatMemory` 迁移到 `ChatMemory_chat`

旧 TEXT 对话的短期记忆存储在 `ChatMemory`（通过 `chatOrchestrator`），新 TEXT 对话使用 `ChatMemory_chat`（通过 `textOrchestrators`）。旧对话记录在新 path 下不可见。

**属于版本更替的预期行为**，不损害功能。

### 3.3 `traceManager.startAgentRequest` 记录原始 `personaId`，而非 `normalizeTextPersona` 后的值

如果客户端发送 `personaId="warm"`，Trace 会记录 `agent.persona=warm`，但实际执行使用的是 `chat` orchestrator（normalizeTextPersona 降级）。Trace 记录的是"客户端请求的值"而非"实际使用的值"，调试时可能产生困惑。

可考虑将 trace 写入改为实际使用的值，但当前设计可接受。

---

## 四、审查结论

**通过验收，可以进入 Phase 3A 或 Phase 4。**
