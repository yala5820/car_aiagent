# ToolGroup Interface Improvement 计划文档 审查报告

**审查日期：** 2026-07-10
**审查范围：** `docs/plan_overall/2026-07-10-toolgroup-interface-improvement-plan.md`
**审查依据：** 当前 `com.hirain.aiagent.toolgroup` / `com.hirain.aiagent.runtime` 实际代码 + 任务目标
**审查人：** Claude Code

---

## 一、总体评估

**有条件通过，3 个问题需要在实施前确认方案。** 计划整体方向正确：规范接口、补齐兜底、为 Context 预留查询能力、增加 LangChain4j 一致性校验。四阶段划分合理，边界声明清晰。但有三处实现细节在当前代码约束下无法按计划直接落地，需要选定方案。

---

## 二、需确认的关键问题

### 问题 1（阻塞）：`RequestSessionFactory` 无法自行构造全量兜底结果

**计划声称（Task 3.3）：**
> 当传入 toolGroupSelectionResult == null 时，构造全量兜底结果，reason 使用 `missing_tool_group_selection_all_tools`。

**实际约束：**
`RequestSessionFactory` 当前不持有 `ToolGroupRegistry` 引用，也没有任何途径获取 47 个 toolName 的完整列表。当前代码的降级是：

```java
if (toolGroupSelectionResult == null) {
    toolGroupSelectionResult = ToolGroupSelectionResult.fallback("missing_tool_group_selection");
    // → selectedGroupIds = [CHAT_ONLY_GROUP], selectedToolNames = []
}
```

要改成全量兜底（`ALL_SAFE_DEMO_GROUP` + 47 个 toolName），Factory 需要一个数据源。三种可选方案：

| 方案 | 做法 | 影响 |
|------|------|------|
| A | 给 `RequestSessionFactory` 注入 `ToolGroupRegistry` | 轻量 Factory 重了 |
| B | `ToolGroupSelectionResult` 新增静态方法，但调用方（RequestSessionFactory）仍需传入 toolName 列表 | 谁传？Factory 自己没这数据 |
| C | Factory 保持当前 0 工具降级不变，因为 Runtime 层的 `selectToolGroupsSafely()` 已经保证不会传 null 到 Factory | 无风险，但不满足计划的"全量兜底"要求 |

**实际情况：** 当前 `AgentRuntime.startSession()` 调用链中，`selectToolGroupsSafely()` 已经处理了 selector 异常和 null。Factory 收到 null 的可能性极低——但计划要求 Factory 的降级也改成全量。**请确认：Factory 层是维持现状（0 工具降级），还是需要注入依赖来支持全量兜底？**

---

### 问题 2（阻塞）：AgentRuntime 测试构造函数缺少 `ToolGroupRegistry` 引用

**计划声称（Task 3.2）：**
> 注入自定义 selector 的测试构造路径也要能提供全量 fallback。

**实际约束：**
`AgentRuntime` 当前有 8 个构造函数，其中多个测试构造函数走的是简化参数（IdGenerator + TimeProvider），内部自动创建 `DefaultToolGroupSelector(ToolGroupRegistry.defaultRegistry())`——但 Runtime 本身不保存 Registry 引用。

当 selector 抛异常或返回 null 时，Runtime 需要构造全量 fallback，但计划要的 `ToolGroupRegistry.allToolNames()` 只在 selector 内部有，Runtime 层没有。

**需要确认：** `AgentRuntime` 新增的 `ToolGroupRegistry` 字段，是只在生产构造函数注入，还是所有 8 个构造函数全部覆盖？建议方案：

- 生产构造函数：通过 `ToolGroupRegistry.defaultRegistry()` 注入（已有该静态方法）
- 测试构造函数：内部仍创建 `DefaultToolGroupSelector(ToolGroupRegistry.defaultRegistry())`，同时把同一个 registry 引用也传给 Runtime
- 无 Registry 路径：Runtime 降级为 `ToolGroupSelectionResult.fallback()`（CHAT_ONLY_GROUP, 0 工具）——不会崩溃，只是 fallback 粒度假了

---

### 问题 3（建议）：`ALL_SAFE_DEMO_GROUP` 兜底会导致 Context 文本暴涨

**影响分析：**
目前 `UNKNOWN + 无弱关键词` → `CHAT_ONLY_GROUP` → Context 中 `selectedToolNames = []` → `ToolGroupContextProvider` 渲染内容极短。

改为 `ALL_SAFE_DEMO_GROUP` 后 → `selectedToolNames = [47 个工具名]` → Context 中渲染：
- 47 行 toolName（每行一个 `  - set_xxx`）
- 13 行的 group 描述

估算文本从 ~50 字符涨到 ~3000 字符。这对 LLM 的上下文窗口是实际负担。

**建议：** 在 `ToolGroupContextProvider` 中检查 `ToolGroupSelectionResult.allToolsFallback()`，如果为 `true`，跳过完整 toolName 列表的渲染，只输出一行：

```
【工具组上下文】
- 全量工具兜底：47 个工具可用（未限制）
```

这样既保留了 Context 中有此信息，又不把 47 个名字全倒进 LLM。此改动不在计划范围内，但值得在 Task 4.1 的文档更新中讨论。

---

## 三、非阻塞问题

### 3.1 `isContextMarkerGroup` / `isAggregationGroup` 的所属类不明确

**Section 2** 说在 `ToolGroup.java` 上补充查询语义，但 **Task 2.1** 说在 `ToolGroupRegistry.java` 上新增这两个方法（参数为 `ToolGroupId`）。

合理的分工：
- `ToolGroup.isContextMarker()` — 实例方法：`toolNames.isEmpty() && !requiredContextKeys.isEmpty()`
- `ToolGroup.isAggregation()` — 实例方法：groupId 为 COMMON_VEHICLE_GROUP 或 ALL_SAFE_DEMO_GROUP
- `ToolGroupRegistry.isContextMarkerGroup(ToolGroupId)` — 委托到 `group(groupId).isContextMarker()`
- `ToolGroupRegistry.isAggregationGroup(ToolGroupId)` — 同理

建议在计划中明确两者的分工，避免实施时临时拍板。

### 3.2 `ToolGroupRegistryValidationResult.summary()` 格式未指定

计划只说了"生成稳定可读文本"，但 `summary()` 的输出格式没有被定义。测试用例 `valid / missing / ungrouped` 需要知道 `summary()` 预期返回什么才能写断言。

建议在 Task 2.2 中补充格式示例，例如：
```
Valid: true
Missing toolNames in LangChain4j: [xxx]
Ungrouped toolNames: [yyy]
```

### 3.3 `AgentRuntimeToolGroupTraceTest` 未列入 Section 2 测试清单

Task 3.2 提到了 `AgentRuntimeToolGroupTraceTest.java`，但 Section 2 "计划修改的测试" 小节中没有列出。这棵文件要么在 Section 2 中补上，要么说明它是可选/自检文件。

### 3.4 UNKNOWN 兜底语义变更会破坏现有测试

当前 `DefaultToolGroupSelector` 对 `UNKNOWN + 无弱关键词` 返回 `[CHAT_ONLY_GROUP]`。计划改为 `[ALL_SAFE_DEMO_GROUP]`。这会导致：
- 任何针对 `UNKNOWN` 的现有测试断言都会失效
- Context 中所有 UNKNOWN 场景的工具列表从 0 个变成 47 个

计划在测试部分只提"覆盖 UNKNOWN 无弱关键词"（含义模糊——是新加测试还是改旧测试？），建议明确声明：**现有 UNKNOWN 断言需更新为全量兜底。**

### 3.5 `AgentRuntime` 当前已有 `SessionIdResolver` 字段，但不影响计划

实际代码中 `AgentRuntime` 有一个 `sessionIdResolver` 字段（来自 memory 包）和对应的构造函数参数。计划中没有提到这个字段。这不影响计划的可行性——`selectToolGroupsSafely()` 不依赖 `sessionIdResolver`。但计划如果要给 `AgentRuntime` 再加一个 `ToolGroupRegistry` 字段，构造函数数量可能会再增加，需要在 Task 3.2 中给出明确的新构造函数列表，避免遗漏。

---

## 四、与确认文档一致的要点

以下各点在审查中确认与计划边界一致，无需修改：

| 检查项 | 结论 |
|--------|------|
| 不改 `AgentLoopOrchestrator` 的 `toolSpecifications` 绑定 | ✅ 边界明确 |
| 不改 Context 注入流程 / `ContextExtraPreProcessor` | ✅ 边界明确 |
| `CHAT` 明确选择为 `CHAT_ONLY_GROUP`，不视为"选不出" | ✅ |
| `ToolGroupSelectionInput` 不依赖 Android / LangChain4j 类型 | ✅ |
| 旧接口 `select(IntentResult, String)` 通过默认方法保留 | ✅ |
| `of(...)` 工厂方法旧参数不破坏编译 | ✅ 默认补齐新增字段 |
| LangChain4j 一致性校验本阶段不 fail fast | ✅ |
| 不引入小 LLM / 子 agent | ✅ 仅预留接口 |
| 不修改真实车控 tool 实现 | ✅ |

---

## 五、审查结论

**通过，需先处理 2 个阻塞问题 + 1 个建议问题。**

| 优先级 | 问题 | 处理建议 |
|--------|------|---------|
| **阻塞** | RequestSessionFactory 无法自行构造全量兜底 | 确认方案：维持 0 工具降级，还是注入 Registry |
| **阻塞** | AgentRuntime 测试构造函数缺少 Registry 引用 | 明确所有构造函数中 Registry 的注入路径 |
| **建议** | ALL_SAFE_DEMO_GROUP 导致 Context 文本膨胀 | 在 ToolGroupContextProvider 中按 allToolsFallback 标记跳过渲染 |
| 次要 | isContextMarker/isAggregation 归属不明确 | 明确 ToolGroup vs ToolGroupRegistry 的分工 |
| 次要 | summary() 格式未指定 | 补格式示例 |
| 次要 | 测试清单遗漏 | Section 2 补上 AgentRuntimeToolGroupTraceTest |
