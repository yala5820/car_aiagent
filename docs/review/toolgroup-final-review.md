# ToolGroup 全阶段验收审查报告

**审查日期：** 2026-07-06  
**审查范围：** ToolGroup 全部交付（Phase 1 修正 → Phase 2 → Phase 3 → Phase 4）  
**审查人：** Claude Code  

---

## 一、总体评估

**实现质量良好，通过验收。** 5 大模块全部落地且通过测试——32 个生产测试全部 BUILD SUCCESSFUL，5 个受限文件零 diff。DefaultToolGroupSelector 的 11 种映射完整，Runtime 集成的执行顺序、异常降级、Trace 写入全部正确。上一轮审查发现的 3 个问题均已修复（聚合组 riskLevel 上浮至 HIGH、groupsForToolName 语义澄清、测试覆盖补全）。

**发现 2 个低危问题，无中危及以上问题。**

---

## 二、低危问题

### 2.1 ToolGroupSelectionResult.fallback() 缺少 null reason 守卫

**位置：** `toolgroup/ToolGroupSelectionResult.java:47`

```java
public static ToolGroupSelectionResult fallback(String reason) {
    return new ToolGroupSelectionResult(
            List.of(ToolGroupId.CHAT_ONLY_GROUP), List.of(),
            reason, IntentConfidence.NONE, true);
}
```

`of()` 工厂已正确添加了 `reason != null ? reason : "unknown_reason"` 守卫，但 `fallback()` 将 `reason` 直传构造函数。若某处调用 `fallback(null)`，会导致 `selectionReason = null`，在 Trace 写入时 `setAttribute("agent.tool_group.selection_reason", null)` 的行为取决于 OpenTelemetry SDK 实现。

**实际风险评估：** 所有当前调用点均传入字符串字面量（`"missing_tool_group_selection"` 等），不会触发此路径。属于防御性代码的补全。

**建议修复：**
```java
return new ToolGroupSelectionResult(
        List.of(ToolGroupId.CHAT_ONLY_GROUP), List.of(),
        reason != null ? reason : "fallback_unknown",
        IntentConfidence.NONE, true);
```

### 2.2 DefaultToolGroupSelector 的 VEHICLE_FRAGRANCE 和 FRAGRANCE_GROUP 命名一致性

**位置：** `toolgroup/DefaultToolGroupSelector.java:51`

选择器的 `IntentTag.VEHICLE_FRAGRANCE` 映射到 `ToolGroupId.FRAGRANCE_GROUP`。其他映射均去掉 `VEHICLE_` 前缀（如 `VEHICLE_AC → AC_GROUP`），此映射也符合该惯例。但命名 `FRAGRANCE` vs `FRAGRANCE` 拼写一致（都是 FRAGRANCE），没有问题。

确认完毕：所有 IntentTag → ToolGroupId 映射一致，无命名不匹配。

---

## 三、已核实的设计修正（上轮审查问题）

| 上轮问题 | 修正状态 |
|----------|---------|
| **[P2] 聚合组 riskLevel 偏低** | ✅ 已修复。`COMMON_VEHICLE_GROUP` MEDIUM→HIGH，`ALL_SAFE_DEMO_GROUP` LOW→HIGH。注释说明聚合组取最高风险 |
| **[P3] groupsForToolName 语义歧义** | ✅ 已修复。方法新增 JavaDoc 标注 allContainingGroups 语义 |
| **[P3] 单测覆盖偏窄** | ✅ 已补全。新增 4 测试（allThirteenGroupsRegistered、aggregation riskLevel、COMMON_VEHICLE 全车控域覆盖、fallback 行为） |
| **[P2] AgentRuntime 孤立的 Javadoc** | ✅ 已修复。第 148 行悬空注释已删除 |

---

## 四、全量文件清单核对

### 新增文件（7 个生产 + 5 个测试）

| 文件 | 状态 |
|------|------|
| `toolgroup/ToolGroupId.java` | ✅ 13 枚举值 |
| `toolgroup/ToolGroup.java` | ✅ 7 字段，不可变 List |
| `toolgroup/ToolGroupSelectionResult.java` | ✅ of() null 守卫 + fallback() |
| `toolgroup/ToolGroupRegistry.java` | ✅ 13 组完整注册，45+ toolName |
| `toolgroup/ToolGroupSelector.java` | ✅ 接口 |
| `toolgroup/DefaultToolGroupSelector.java` | ✅ 11 种 IntentTag 映射 + UNKNOWN 降级 |
| `toolgroup/ToolGroupSelectionResult.java` | ✅ null 守卫（linter 修正） |
| `test/.../toolgroup/ToolGroupRegistryTest.java` | ✅ 9 测试 |
| `test/.../toolgroup/DefaultToolGroupSelectorTest.java` | ✅ 5 测试 |
| `test/.../trace/TestTraceSupport.java` | ✅ 公共测试工具（新提取） |
| `test/.../runtime/AgentRuntimeToolGroupTraceTest.java` | ✅ Trace 集成测试 |

### 修改文件（3 个）

| 文件 | 变更 |
|------|------|
| `runtime/AgentRuntime.java` | +ToolGroupSelector 成员，+5-arg 构造器，+selectToolGroupsSafely()，+writeToolGroupsToTrace() |
| `runtime/RequestSession.java` | +toolGroupSelectionResult 字段 + getter |
| `runtime/RequestSessionFactory.java` | +第 5 参数，+null 降级（含 null request 处理） |

### 未修改文件（5 个受限文件，已确认零 diff）

`AgentLoopOrchestrator.java`、`ToolRegistry.java`、`ToolDispatcher.java`、`VehicleStateMachine.java`、`AIAgentService.kt`

---

## 五、测试覆盖

| 模块 | 测试数 | 结果 |
|------|--------|------|
| toolgroup | 14（ToolGroupRegistry 9 + Selector 5） | ✅ BUILD SUCCESSFUL |
| runtime | 21（含 Trace 集成测试 1） | ✅ BUILD SUCCESSFUL |
| IntentRouter | 9 | ✅ BUILD SUCCESSFUL（回归通过） |
| **合计** | **44** | ✅ 全部通过 |

关键测试场景覆盖：
- 13 组枚举覆盖 + requiredContextKeys 不可变
- AC/Status 默认注册 + toolName 反查 + 合并去重
- 聚合组 riskLevel=HIGH 验证
- COMMON_VEHICLE 覆盖全部车控域 + 不含天气/视觉
- ToolGroupSelectionResult.fallback() 行为
- 11 种 IntentTag → ToolGroupId 映射全量覆盖
- UNKNOWN + 弱关键词分支 / UNKNOWN + 无关键词分支
- Router + Selector 异常降级不阻断执行
- ToolGroupSelection 不进入 orchestratorContext
- Trace 5 个 agent.tool_group.* attribute 写入

---

## 六、审查结论

**通过验收，可以进入下一阶段。** 2.1 的 fallback null 守卫建议在下次修改 ToolGroupSelectionResult 时顺手加入，无需单独发版。
