# ToolGroup 计划审查报告

**审查日期：** 2026-07-02  
**计划文件：** [`docs/plan/toolgroup-introduction-plan.md`](../plan/toolgroup-introduction-plan.md)  
**审查人：** Claude Code  

---

## 一、总体评估

计划整体可行，架构清晰，与 Phase 2 IntentRouter 的衔接设计合理。按 toolName（非完整 schema）元数据建模、只记录不执行、通过 Trace 观测的分层策略正确。

**发现 1 个中危问题和 4 个低危建议，没有致命阻塞问题。**

---

## 二、中危问题

### 2.1 已有 3-arg 构造函数 `(AgentExecutor, IdGenerator, TimeProvider)` 在计划中未显式展示

**位置：** Phase 4, Task 5, Step 3

当前 `AgentRuntime` 有 3 个构造函数（1 / 3 / 4 参数）。计划展示的代码块仅覆盖了 1-arg 和 4-arg（含 IntentRouter），未展示已有的 3-arg `(AgentExecutor, IdGenerator, TimeProvider)`。

虽然计划说"保留旧构造函数"，但已有测试（`AgentRuntimeTest` 中 3 处）直接使用此构造函数：

```java
new AgentRuntime(executor, () -> "req-fixed", () -> 3000L);
// 参数: (AgentExecutor, IdGenerator, TimeProvider)
```

**影响：** 实施时如果忘了更新 3-arg 构造函数来创建 `DefaultToolGroupSelector`，此构造函数路径上将缺少 selector，运行时 startSession 会 NPE。

**建议的补充代码：**

```java
// 3-arg 构造函数（保留，需显式更新）
public AgentRuntime(AgentExecutor chatExecutor,
                    IdGenerator idGenerator,
                    TimeProvider timeProvider) {
    this(chatExecutor,
            new KeywordIntentRouter(),
            new DefaultToolGroupSelector(ToolGroupRegistry.defaultRegistry()),
            idGenerator,
            timeProvider);
}
```

---

## 三、低危建议

### 3.1 `CHASSIS_GROUP` 包含 `set_vehicle_spd` 需要明确设计意图

**位置：** Section 2.2, CHASSIS_GROUP

`set_vehicle_spd` 实际定义在 `tools/vehicle/speed/VehicleSpeedManager.java`，而非 `tools/vehicle/chassis/VehicleChassisManager.java`。计划将其归入 `CHASSIS_GROUP`。

**说明：** 这不是错误。没有单独的 `SPEED_GROUP`，且车速控制与底盘动态相关。但没有注释说明为什么速度控制属于底盘组，后续维护者可能困惑。

**建议：** 在 `CHASSIS_GROUP` 上添加一行注释 `// 含车速控制（无独立 SPEED_GROUP）`。

---

### 3.2 ToolGroup 元数据与 @Tool 注解之间无编译时一致性检查

**位置：** Section 2.2 工具名列表

ToolGroup 使用硬编码的 toolName 字符串引用了 45+ 个 @Tool(name="xxx")。如果后续 @Tool 改名或增删，ToolGroupRegistry 中的硬编码不会自动更新。

**当前状态的验证结果：** 已逐一比对 8 个 VehicleManager + WeatherUtils + VlManager 的 @Tool 注解，计划中的 toolName 与源码**完全一致**（0 差异）。

**建议：** Phase 1 不引入自动化检查（YAGNI），但实施时在 `docs/act_summary/toolgroup-introduction-summary.md` 中注明"ToolGroup 的 toolName 与 ToolRegistry 中 @Tool name 的对应关系需人工维护"。

---

### 3.3 `BASIC_STATUS_GROUP` 的设计假设需要明确

**位置：** Section 2.2, Section 0.3

计划设定 `BASIC_STATUS_GROUP` 不包含任何 toolName，只声明 `requiredContextKeys=["user_id","vehicle_status"]`。但选择策略中，只有车辆意图（AC/WINDOW/SEAT/DOOR/CHASSIS/FRAGRANCE/DMS）才附加 BASIC_STATUS_GROUP。

**缺少 BASIC_STATUS_GROUP 的意图：** WEATHER、VISION_QA、CHAT 不包含它。但 WEATHER 和 VISION 实际上也都需要 `user_id` 做日志溯源。

**说明：** `requiredContextKeys` 只是元信息，本阶段不使用。`user_id` 由 Runtime 层自动注入 `orchestratorContext`，不受 ToolGroup 影响。属于设计选择，不阻塞实现。

---

### 3.4 `COMMON_VEHICLE_GROUP` 的 toolName 需要手动维护

**位置：** Section 2.2

`COMMON_VEHICLE_GROUP` 需要从 7 个车辆组（AC/WINDOW/SEAT/DOOR/CHASSIS/FRAGRANCE/DMS）手动拼接 toolName 列表。

**实现方式建议：** 不在 `defaultRegistry()` 中手动复制 45+ 个 toolName，而是在注册完各组后动态合并：

```java
// 在 defaultRegistry() 中构建 COMMON_VEHICLE_GROUP 时
List<String> commonVehicleToolNames = new ArrayList<>();
for (ToolGroupId id : List.of(AC_GROUP, WINDOW_GROUP, SEAT_GROUP, DOOR_GROUP,
        CHASSIS_GROUP, FRAGRANCE_GROUP, DMS_GROUP)) {
    commonVehicleToolNames.addAll(acTools); // ...各自引用
}
```

这样做的好处是：未来新增工具到某个车辆组时，`COMMON_VEHICLE_GROUP` 自动包含，不需要两处同步修改。

---

## 四、已核实无误的设计点

| 检查项 | 状态 |
|--------|------|
| ToolGroupId 13 个枚举值覆盖 | ✅ |
| toolName 与源码 @Tool 注解完全一致（已逐一核对 50 个工具名） | ✅ 0 差异 |
| IntentTag → ToolGroupId 映射关系 | ✅ VEHICLE_AC→AC_GROUP, VEHICLE_WINDOW→WINDOW_GROUP 等，命名统一去掉 VEHICLE_ 前缀 |
| `TraceAttributeWriter` / `TraceSpanNames` / `TraceConfig.ContentCaptureMode` 存在且签名匹配 | ✅ |
| `TraceSession(Span, Tracer, TraceAttributeWriter)` 3 参构造函数存在 | ✅ |
| `ContentCaptureMode.REDACTED` 枚举值存在 | ✅ |
| `RequestSession` / `RequestSessionFactory` 的现有调用点在 plan 中均有明确修改方案 | ✅ |
| `ToolGroupSelectionResult` 不进入 `orchestratorContext` 在测试中验证 | ✅ `assertFalse(context.containsKey("selected_tool_groups"))` |
| ToolGroup 不可变性（`Collections.unmodifiableList`） | ✅ |
| Selector 异常不阻断执行 | ✅ `startSession_toolGroupSelectorExceptionFallsBackAndExecuteContinues` 测试 |
| AIAgentService.kt 不修改（TEXT 链路通过已有 `agentRuntime` 自动生效） | ✅ |
| 不引入新依赖 | ✅ |

---

## 五、审查结论

**计划可以进入实施阶段。** 实施时需注意中危问题 2.1——确保已有的 3-arg 构造函数 `(AgentExecutor, IdGenerator, TimeProvider)` 也更新为包装 `DefaultToolGroupSelector`。4 个低危建议可在实施过程中一并处理，不阻塞进度。
