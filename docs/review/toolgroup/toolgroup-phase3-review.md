# ToolGroup Interface Improvement — Phase 3 验收审查

**审查日期：** 2026-07-10
**审查范围：** Phase 3 兜底语义调整、Runtime 防御路径、Context 摘要渲染（Task 3.1～3.4）
**审查依据：** `docs/plan_overall/2026-07-10-toolgroup-interface-improvement-plan.md` Phase 3
**审查人：** Claude Code

---

## 一、总体评估

**通过验收，1 项测试缺失需补充。** 本阶段的核心变更全部正确落地：三处兜底路径语义统一为 `ALL_SAFE_DEMO_GROUP`，Runtime 防线不依赖 Factory 持有 Registry，Context 提供者做了全量兜底摘要渲染防止文本暴涨。Phase 1 的 SAM 方向问题也一并修复。仅 `ToolGroupContextProviderTest` 缺少计划要求的 4 个 allToolsFallback 测试。

---

## 二、本阶段做了什么

Phase 3 做了四件事，目标是让"选不出工具组时"的行为从"返回空"变成"返回全量兜底"：

1. **调整 DefaultToolGroupSelector 兜底** — `UNKNOWN + 无弱关键词` 和 `null intent` 原来返回 `CHAT_ONLY_GROUP`（0 个工具），现在返回 `ALL_SAFE_DEMO_GROUP`（47 个工具）。返回结果通过 `ToolGroupSelectionResult.full()` 补齐了 context keys、风险等级、聚合组标记。

2. **统一 Runtime 异常兜底** — `selectToolGroupsSafely()` 中 selector 返回 null 或抛异常时，不再调用轻量 `fallback()`，而是用 `ToolGroupSelectionResult.allToolsFallback()` 构造全量兜底，reason 统一带 `_all_tools` 后缀便于 Trace 识别。

3. **Factory 保持轻量防线** — `RequestSessionFactory` 不改，继续用 `fallback("missing_tool_group_selection")` 做最后轻量降级。生产主路径的全量兜底由 Runtime 保证。

4. **Context 摘要渲染** — `ToolGroupContextProvider` 检测 `allToolsFallback=true` 时，不展开 47 个 toolName，只渲染一行摘要，避免 Context 文本暴涨。

额外修复：**Phase 1 的 SAM 方向问题已在 Phase 3 修正**。`ToolGroupSelector` 现在以 `select(IntentResult, String)` 为抽象方法，`select(ToolGroupSelectionInput)` 为默认委托方法，与计划一致。

---

## 三、代码审查

### 3.1 DefaultToolGroupSelector 兜底变更

| 场景 | 旧行为 | 新行为 | 审查 |
|------|--------|--------|------|
| `UNKNOWN + 无弱关键词` | `CHAT_ONLY_GROUP`（0 tools） | `ALL_SAFE_DEMO_GROUP`（47 tools） | ✅ |
| `null intent / null tag` | `CHAT_ONLY_GROUP`（0 tools） | `ALL_SAFE_DEMO_GROUP`（47 tools） | ✅ |
| `CHAT + 无弱关键词` | `CHAT_ONLY_GROUP`（不变） | `CHAT_ONLY_GROUP`（不变） | ✅ |
| `CHAT + 弱关键词` | `COMMON_VEHICLE_GROUP`（不变） | `COMMON_VEHICLE_GROUP`（不变） | ✅ |
| `UNKNOWN + 弱关键词` | `COMMON_VEHICLE_GROUP`（不变） | `COMMON_VEHICLE_GROUP`（不变） | ✅ |
| 所有车控意图 | 对应组+BASIC（不变） | 对应组+BASIC（不变） | ✅ |

**`buildResult()` 工厂路径：**

```java
private ToolGroupSelectionResult buildResult(...) {
    List<String> toolNames = registry.toolNamesFor(groupIds);
    boolean isAllToolsFallback = groupIds.contains(ToolGroupId.ALL_SAFE_DEMO_GROUP);
    return ToolGroupSelectionResult.full(
            groupIds, toolNames, reason, confidence, fallbackUsed,
            registry.requiredContextKeysFor(groupIds),
            registry.highestRiskLevelFor(groupIds),
            isAllToolsFallback,
            registry.containsAggregationGroup(groupIds));
}
```

- 生产路径统一用 `full()`（不是旧 `of()`），派生字段全由 registry 填充 ✅
- `allToolsFallback` 通过"是否含 ALL_SAFE_DEMO_GROUP"推导，逻辑一致 ✅

### 3.2 AgentRuntime 兜底防线

```java
private ToolGroupSelectionResult selectToolGroupsSafely(...) {
    ...
    if (result != null) return result;
    return ToolGroupSelectionResult.allToolsFallback(
            toolGroupRegistry != null ? toolGroupRegistry.allToolNames() : List.of(),
            "tool_group_selector_null_all_tools");
    } catch (Exception e) {
    return ToolGroupSelectionResult.allToolsFallback(
            toolGroupRegistry != null ? toolGroupRegistry.allToolNames() : List.of(),
            "tool_group_selector_exception_all_tools");
}
```

- **null → `tool_group_selector_null_all_tools`** ✅
- **exception → `tool_group_selector_exception_all_tools`** ✅
- **reason 统一 `_all_tools` 后缀**，Trace 中可从此后缀识别全量兜底 ✅
- **registry 为空时防御：`List.of()` 空列表**，不会 NPE ✅
- **9 个构造函数全部传递 `ToolGroupRegistry.defaultRegistry()`**，不新增第 10 个 constructor ✅

### 3.3 RequestSessionFactory 保持不动

当前代码不变（`ToolGroupSelectionResult.fallback("missing_tool_group_selection")`），注释明确这是"最后防线"，生产路径由 Runtime 保证不为 null。✅

### 3.4 ToolGroupContextProvider 摘要渲染

```java
if (selection != null && selection.allToolsFallback()) {
    return createAllToolsFallbackSection(toolNames, selection);
}
```

摘要输出：
```
【工具组上下文】
- allToolsFallback: true
- selectedToolCount: 47
- summary: 选择器无法确定明确工具组，本轮仅记录全量候选工具；实际 LLM 可见工具仍由 AgentLoop 固定绑定决定。
```

- 不展开 47 个 toolName ✅
- metadata 中 `all_tools_fallback: true` ✅
- 与计划指定格式一致 ✅

### 3.5 SAM 方向修复

| 阶段 | 抽象方法 | 默认方法 | 计划要求 |
|------|---------|---------|---------|
| Phase 1（旧） | `select(ToolGroupSelectionInput)` ← 反了 | `select(IntentResult, String)` | ❌ 不符 |
| Phase 3（现） | `select(IntentResult, String)` | `select(ToolGroupSelectionInput)` | ✅ 符合 |

### 3.6 旧测试名称同步更新

`select_unknownWithoutVehicleKeywordReturnsChatOnly` → `select_unknownWithoutVehicleKeywordReturnsAllSafeDemo` ✅

---

## 四、缺失项

### 问题：`ToolGroupContextProviderTest` 缺少 allToolsFallback 测试

计划 Task 3.4 要求 4 个测试断言，当前 `ToolGroupContextProviderTest.java` 只有 1 个旧测试 `provide_rendersOnlySelectedTools`：

| 计划要求 | 状态 |
|---------|------|
| 输出包含 `allToolsFallback: true` | ❌ 缺失 |
| 输出包含 `selectedToolCount: ` + registry.allToolNames().size() | ❌ 缺失 |
| 输出不包含逐行工具名（如 `  - set_ac_status`） | ❌ 缺失 |
| 普通 AC 选择结果仍保留逐行 toolName | ❌ 缺失 |

需要补上。测试需要构造一个 `allToolsFallback=true` 的 `ToolGroupSelectionResult` 注入到 `RequestSession` 中，然后调用 `ToolGroupContextProvider.provide()` 验证输出。

---

## 五、审查结论

**Phase 3 通过验收，补充 4 个 Context 测试后可进入 Phase 4。**

| 检查项 | 状态 |
|--------|------|
| Task 3.1: Selector 兜底语义 | ✅ |
| Task 3.2: Runtime 异常兜底 + `_all_tools` 后缀 | ✅ |
| Task 3.3: Factory 保持轻量 | ✅ |
| Task 3.4: Context 摘要渲染 | ✅ 代码正确 |
| Task 3.4: Context 测试 | ❌ 缺 4 个测试 |
| SAM 方向修复 | ✅ |
| 旧测试名称更新 | ✅ |
| 全量单测回归 | ✅ BUILD SUCCESSFUL |
