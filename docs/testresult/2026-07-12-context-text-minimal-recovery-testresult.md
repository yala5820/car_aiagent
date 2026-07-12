# Context TEXT 最小恢复 — 最终测试结果

**执行日期：** 2026-07-12
**Git 状态：** dev_runtime（含 Phase 1-3 全部变更）

---

## 执行命令

| 命令 | 结果 |
|------|------|
| `compileDebugJavaWithJavac --rerun-tasks` | 通过 |
| `testDebugUnitTest --rerun-tasks` | 256 完成，10 失败 |
| `assembleDebug --rerun-tasks` | 通过 |
| `lintDebug` | 通过 |

## 测试摘要

| 度量 | 值 |
|------|-----|
| 总测试数 | 256 |
| 通过 | 246 |
| 失败 | 10 |
| 跳过 | 0 |

## 失败测试说明

全部 10 个失败均为相同根因：`ToolRegistry`（构造函数调用 `ToolDispatcher` → `android.util.Log.d()`）在 JVM 单元测试中不可用。这些测试使用 AgentRuntime 默认构造器但不提供 ToolRegistry mock。

| 文件 | 失败数量 | 原因 |
|------|---------|------|
| AgentRuntimeResolvedSessionTest | 3 | ToolGroupContextProvider 因 toolRegistry=null 返回 TOOL_SPEC_RESOLUTION_FAILED |
| AgentRuntimeTest | 6 | 同上 |
| AgentExecutorCompatibilityTest | 1 | 同上 |

修复方案：同 AgentRuntimeContextTest（Phase 3 Task 3.1），注入 JvmToolRegistry + PromptManager fake。因这些测试使用不同的 AgentRuntime 构造器签名，修复需要更多适配工作，留待后续。

## 通过测试一览

| 包 | 文件 | 状态 |
|----|------|------|
| context | 5 个测试类（包含 budget/validator/或chestrator/compression/assembler） | ✅ 全部通过 |
| context.provider | ToolGroupContextProviderTest | ✅ 全部通过 |
| core | TextAgentLoopOrchestratorTest（10 个测试） | ✅ 全部通过 |
| runtime | ContextTextEndToEndTest（8 个集成测试） | ✅ 全部通过 |
| runtime | AgentRuntimeContextTest（6 个测试） | ✅ 全部通过 |
| trace | ContextProductionTraceHierarchyTest | ✅ 全部通过 |
| 其他 | 上表中未列的现有测试 | ✅ 通过 |

## 设备验证

以下 8 项设备验证因当前无设备环境未执行：

- 连续普通 TEXT 对话至少 10 轮
- 新建并切换 Session
- 同一 Session 切换 user
- persona 切换（chat/friendly/concise）
- 车控/CHAT_ONLY/模糊 allToolsFallback 工具集合核对
- 多工具取消后续请求
- 超预算输入
- Phoenix Trace 层级核对

## 遗留风险

1. ContextSection、ContextSectionType、ContextDebugInfo 仍为冻结非 TEXT 遗留，未删除
2. ContextBudgetManager.makeDecision() 和 Memory compaction API 保持可用但停用生产调用
3. 10 个 runtime 测试因 android.util.Log 不可用在 JVM 中继续失败
4. 设备验证项未执行，推荐在真实车机或 Robolectric 环境中补充
