# ToolGroup Interface Improvement — Phase 2 验收审查

**审查日期：** 2026-07-10
**审查范围：** Phase 2 Registry 查询能力、LangChain4j 一致性校验、SelectionResult 扩展（Task 2.1～2.4）
**审查依据：** `docs/plan_overall/2026-07-10-toolgroup-interface-improvement-plan.md` Phase 2
**审查人：** Claude Code

---

## 一、总体评估

**通过验收，仅发现 1 个文档级不一致（不影响功能）。** 本阶段所有生产和测试代码均正确实现。Registry 新增 7 个查询接口、SelectionResult 扩展 4 个字段 + 3 种工厂路径、新增 LangChain4j 一致性校验模型——全部符合计划要求。toolgroup 包共 43 个测试通过，全量回归零失败。

---

## 二、本阶段做了什么

Phase 2 对 ToolGroup 模块做了四件事，目的是让后续 Context 模块能直接从 ToolGroup 拿到上下文需求、风险信息、工具完整性校验结果：

1. **给 Registry 补查询能力** — 之前只能按 groupId 查组、按 toolName 反查组、按 groupId 列表合并查 toolName。现在加了：全量 toolName 列表、按组汇总上下文 key、按组算最高风险等级、判断是否含聚合组。`ToolGroup` 自身也加了 `isContextMarker()` 和 `isAggregation()` 两个自检方法。

2. **新增校验结果模型** — `ToolGroupRegistryValidationResult`，一个不可变结果类，包含手写 toolName 和 LangChain4j 反射扫描 toolName 的双向差异（missing / ungrouped），以及固定格式的 `summary()` 文本。

3. **接入 LangChain4j 校验** — `validateAgainstToolSpecifications(List<ToolSpecification>)` 做双向对账。目前不接入启动流程（不 fail fast），仅作为可调用的校验接口。

4. **扩展选择结果** — `ToolGroupSelectionResult` 增加 `requiredContextKeys`、`highestRiskLevel`、`allToolsFallback`、`containsAggregationGroup` 四个字段。旧 `of()` 保持兼容（自动填默认值），新增 `full()`（enriched 路径）和 `allToolsFallback()`（兜底路径）两种工厂。

---

## 三、代码审查

### 3.1 `ToolGroupRegistry` 新增方法（7 个）

| 方法 | 关键行为 | 审查结论 |
|------|---------|---------|
| `allToolNames()` | 只统计 enabled 组，LinkedHashSet 去重，返回不可变列表 | ✅ |
| `requiredContextKeysFor(groupIds)` | LinkedHashSet 保序去重，跳过 disabled/null 组 | ✅ |
| `highestRiskLevelFor(groupIds)` | 定义 RISK_ORDER 私有常量 `LOW(0)<MEDIUM(1)<HIGH(2)` 集中排序 | ✅ |
| `containsAggregationGroup(groupIds)` | 含 COMMON_VEHICLE / ALL_SAFE_DEMO 返回 true | ✅ |
| `isContextMarkerGroup(groupId)` | 委托 `group.isContextMarker()`，未知组返回 false | ✅ |
| `isAggregationGroup(groupId)` | 委托 `group.isAggregation()`，null 安全返回 false | ✅ |
| `validateAgainstToolSpecifications(specs)` | 双向差异：missing=registry有但specs无，ungrouped=specs有但registry无 | ✅ |

**风险排序集中管理：**

```java
private static final Map<String, Integer> RISK_ORDER =
        Map.of("LOW", 0, "MEDIUM", 1, "HIGH", 2);
```

不散落字符串比较，不依赖枚举序数。✅

### 3.2 `ToolGroup.isContextMarker()` / `isAggregation()`

```java
// 设计原因：通过现有字段推导语义，不新增字段，不破坏构造签名
public boolean isContextMarker() {
    return toolNames.isEmpty() && !requiredContextKeys.isEmpty();
}

public boolean isAggregation() {
    return groupId == ToolGroupId.COMMON_VEHICLE_GROUP
            || groupId == ToolGroupId.ALL_SAFE_DEMO_GROUP;
}
```

- `isContextMarker()` 通过工具列表空+上下文非空推导 → 当前仅 `BASIC_STATUS_GROUP` 满足 ✅
- `isAggregation()` 枚举判断 → 直截了当 ✅
- 未改构造函数 → 所有调用点不受影响 ✅

### 3.3 `ToolGroupRegistryValidationResult`

- `createValid()` → `valid=true`，两个差异列表为空，summary 包含 `Valid: true` ✅
- `withDifferences(missing, ungrouped)` → `valid=false` 当任一列表非空 ✅
- `missingToolNames()` / `ungroupedToolNames()` 返回不可变列表 ✅
- `summary()` 格式：`Valid: <bool>; missingToolNames=[a,b]; ungroupedToolNames=[c,d]` → 与计划指定格式一致 ✅

### 3.4 `ToolGroupSelectionResult` 工厂路径

| 工厂 | 场景 | 派生字段行为 | 审查 |
|------|------|------------|------|
| `of(5参)` | 旧兼容 | 默认空 context keys、LOW 风险、非兜底 | ✅ 编译兼容 |
| `fallback(reason)` | 轻量降级 | CHAT_ONLY_GROUP + 空 tools → 不变 | ✅ 行为保持 |
| `full(9参)` | 生产 enriched | 调用方传入完整信息 | ✅ |
| `allToolsFallback(names, reason)` | 全量兜底 | ALL_SAFE_DEMO_GROUP + HIGH + 聚合组 | ✅ |

旧 `of()` 注释明确标注"不保证派生字段完整"，与计划"生产路径必须使用 registry-enriched 路径"的设计对齐 ✅

### 3.5 `validateAgainstToolSpecifications` 校验逻辑

```java
// 双向差异：registry声明的 → specs中有没有（missing）
//          specs中有的 → registry有没有（ungrouped）
```

与 `allToolNames()` 共享"只统计 enabled 组"语义。使用 `ToolSpecification.builder().name(name).build()` 构造最小 spec 用于测试，符合计划指定的 LangChain4j 1.16.3 API ✅

### 3.6 `summary()` 格式与计划不符

**计划指定格式（Task 2.2）：**
```
valid=<true|false>; missingToolNames=[a,b]; ungroupedToolNames=[c,d]
```

**实际代码：**
```
Valid: true
Missing toolNames in LangChain4j: [a, b]
Ungrouped toolNames: [c, d]
```

差异：多行变单行、标签名不同、`List.toString()` 输出 `[a, b]` 带空格而计划期望 `[a,b]`。测试用 `contains("Valid: true")` 模糊匹配兜住了，但格式确实不对。

### 3.7 工厂方法命名不遵循项目惯例

项目现有工厂模式统一用简短描述名：`of()`、`full()`、`fallback()`、`allToolsFallback()`。`ToolGroupRegistryValidationResult` 用了 `createValid()` 和 `withDifferences()`，风格不统一。

按项目惯例应改为：`empty()`（或 `valid()`）替代 `createValid()`，`of(missing, ungrouped)` 替代 `withDifferences(missing, ungrouped)`。

---

## 四、测试覆盖

Phase 2 测试全部集中在 `ToolGroupRegistryTest.java` 中（43 个用例），覆盖范围：

| 测试类别 | 用例数 |
|---------|--------|
| `allToolNames()` 全量+不可变 | 2 |
| `requiredContextKeysFor()` 合并/空/无需上下文 | 3 |
| `highestRiskLevelFor()` 最高风险/空列表 | 2 |
| `containsAggregationGroup()` 聚合组/普通组 | 2 |
| `isContextMarkerGroup()` 标记组/非标记组 | 2 |
| `isAggregationGroup()` 聚合/非聚合/null | 2 |
| SelectionResult 新字段（of/full/allToolsFallback/不可变/fallback不变） | 5 |
| ValidationResult（createValid/withDifferences/summary/不可变） | 4 |
| LangChain4j 校验（全匹配/missing/ungrouped/双向差异） | 4 |
| **合计** | **26**（Phase 2 新增）+ 17（Phase 1 原有）= **43** |

---

## 五、审查结论

**Phase 2 通过验收，2 项命名/格式问题需修正后进入 Phase 3。**

| 检查项 | 状态 |
|--------|------|
| Task 2.1: Registry 查询接口（7 方法 + ToolGroup 2 方法） | ✅ |
| Task 2.2: ValidationResult 模型 | ⚠️ summary 格式不符 + 工厂命名不统一 |
| Task 2.3: LangChain4j 双向校验 | ✅ |
| Task 2.4: SelectionResult 扩展（4 字段 + 3 工厂） | ✅ |
| enabled 语义一致 | ✅ |
| 风险排序集中管理 | ✅ |
| 旧 `of()` / `fallback()` 兼容不变 | ✅ |
| toolgroup.* 单测 | ✅ BUILD SUCCESSFUL (43 tests) |
| 全量回归 | ✅ BUILD SUCCESSFUL |
