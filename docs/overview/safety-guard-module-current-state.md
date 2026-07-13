# SafetyGuard 模块现状总结

> 最后更新：2026-07-11 | 依据实际代码（已验证）
>
> **历史文档说明：** 本文记录的是 2026-07-13 改造前的旧 SafetyGuard 体系，相关接口与实现现已删除。当前实现请阅读 [`tool-safety-policy-engine-current-state.md`](./tool-safety-policy-engine-current-state.md)。

---

## 一、模块定位与职责

SafetyGuard 是 Agent 循环引擎（`AgentLoopOrchestrator`）的 **工具执行前安全审查** 组件，位于 7 组件管线中的第 4 位（SafetyGuard → ToolExecutor 之前）：

```
PreProcessor → ModelCaller → SafetyGuard → ToolExecutor → PostProcessor → Terminator → ResultCollector
```

**核心职责**：在 LLM 请求执行某个 Tool 之后、实际调用 Tool 之前进行审查，判断该调用在当前车辆状态下是否安全。否决的调用不会执行真实 Tool，而是将 `[SAFETY VETO] + 原因` 作为 `ToolExecutionResultMessage` 写回 ChatMemory，让 LLM 在下轮迭代中向用户解释为何不能执行。

**适用范围**：仅审查 Tool 调用，不审查 LLM 的文本输出。当前仅实现了一条具体规则——车速超过 5 km/h 时阻止车门解锁。

---

## 二、文件清单

### 2.1 生产代码（7 文件）

| 文件 | 路径 | 职责 |
|------|------|------|
| `SafetyGuard.java` | `core/component/SafetyGuard.java` | `@FunctionalInterface` — 安全审查接口 |
| `SafetyVerdict.java` | `core/SafetyVerdict.java` | 审查结果值对象（ALLOW / VETO） |
| `AllowAllSafetyGuard.java` | `core/safety/AllowAllSafetyGuard.java` | 始终放行（占位实现） |
| `CompositeSafetyGuard.java` | `core/safety/CompositeSafetyGuard.java` | 组合多个 Guard，首个否决即停止 |
| `SpeedBasedDoorLockGuard.java` | `core/safety/SpeedBasedDoorLockGuard.java` | **唯一生产实现** — 车速门锁安全审查 |
| `SafetyVetoTerminator.java` | `core/terminator/SafetyVetoTerminator.java` | 辅助终止器：存在否决时停止循环 |
| `ToolExecutionRecord.java` | `core/ToolExecutionRecord.java` | 工具执行记录（含 SafetyVerdict） |

### 2.2 调用/集成代码（4 文件）

| 文件 | 路径 | 角色 |
|------|------|------|
| `AgentLoopOrchestrator.java` | `core/AgentLoopOrchestrator.java` | 主循环中调用 `config.safetyGuards()` |
| `AgentConfig.java` | `core/AgentConfig.java` | 持有 `List<SafetyGuard>` 配置字段 |
| `AgentConfigFactory.java` | `core/factory/AgentConfigFactory.java` | 为各 Persona 装配 SafetyGuard 实例 |
| `AgentLoopContext.java` | `core/AgentLoopContext.java` | 存储 `lastSafetyVeto` + 工具执行历史 |

### 2.3 Trace 集成（1 文件）

| 文件 | 路径 | 角色 |
|------|------|------|
| `AgentTraceRecorder.java` | `trace/AgentTraceRecorder.java` | `finishTool()` 中将 SafetyVerdict 写入 Span 属性 |

### 2.4 测试代码（1 文件）

| 文件 | 路径 | 角色 |
|------|------|------|
| `AgentTraceRecorderTest.java` | `trace/AgentTraceRecorderTest.java` | 仅 `recordsToolResultAndSafetyVeto()` 一个测试涉及 SafetyVerdict |

**无专用单元测试** — `AllowAllSafetyGuard`、`CompositeSafetyGuard`、`SpeedBasedDoorLockGuard`、`SafetyVetoTerminator` 均无独立测试。

---

## 三、核心接口与数据结构

### 3.1 `SafetyGuard` 接口

```java
@FunctionalInterface
public interface SafetyGuard {
    SafetyVerdict evaluate(ToolExecutionRequest request, AgentLoopContext ctx);
}
```

- **输入**：LangChain4j 的 `ToolExecutionRequest`（含 tool name + JSON arguments）+ 当前 `AgentLoopContext`（含 iteration、contextData、工具执行历史）
- **输出**：`SafetyVerdict` — ALLOW 放行 或 VETO("原因") 否决
- 设计为 `@FunctionalInterface`，可用 lambda 内联实现。当前 3 个实现均为具体类。
- 不抛出异常：否决通过返回值表达，不中断控制流。

### 3.2 `SafetyVerdict` 值对象

```java
public final class SafetyVerdict {
    // ALLOW 为不可变单例
    private static final SafetyVerdict ALLOW_INSTANCE = new SafetyVerdict(true, null);
    
    public static SafetyVerdict allow();       // 放行（单例）
    public static SafetyVerdict veto(String);   // 否决
    public boolean isAllowed();
    public boolean isVetoed();
    public String reason();  // 否决时返回原因，ALLOW 时返回 null
}
```

- 不可变、线程安全
- `allow()` 返回单例，`veto(reason)` 每次创建新实例
- 否决原因用于：写入 ChatMemory 中的 ToolExecutionResultMessage → LLM 可读并回应用户

### 3.3 `ToolExecutionRecord`

```java
public class ToolExecutionRecord {
    public String toolName();
    public String arguments();
    public String result();
    public SafetyVerdict safetyVerdict();
    public long timestampMs();
}
```

工具执行历史的不可变快照，存储在 `AgentLoopContext.toolExecutionHistory` 列表中，供 PostProcessor、ResultCollector 等后续组件查询。

---

## 四、三个具体实现

### 4.1 `SpeedBasedDoorLockGuard`（唯一生产实现）

```java
public class SpeedBasedDoorLockGuard implements SafetyGuard {
    private static final int MAX_UNLOCK_SPEED_KMH = 5;
    private static final String DOOR_LOCK_TOOL = "set_door_lock";
    
    // 通过依赖注入的 SpeedProvider 获取当前车速
    public interface SpeedProvider {
        int getCurrentSpeedKmh();
    }
}
```

**审查逻辑（4 条规则）：**
1. 非 `set_door_lock` 工具 → 直接放行
2. `set_door_lock` 且 `arg0=true`（锁门） → 放行（锁门始终安全）
3. `set_door_lock` 且 `arg0=false`（解锁） → 检查车速：若 `speed > 5 km/h` → VETO(`"出于安全考虑，车速 %d km/h 时不允许解锁车门。请先停车。"`)
4. 参数 JSON 解析异常 → 放行（容错，避免阻塞正常调用）

**关键设计：**
- `SpeedProvider` 通过函数接口注入，构造时由 `AgentConfigFactory` 传入 lambda `() -> parseSpeed(speedManager.getSpeedStatus())`，解析 `VehicleSpeedManager.getSpeedStatus()` 返回的 JSON 中的 `"车速"` 字段
- 锁门（`arg0=true`）始终安全，仅审查解锁操作
- 异常安全：JSON 解析失败时静默放行

### 4.2 `AllowAllSafetyGuard`（占位实现）

始终返回 `SafetyVerdict.allow()`，用于不需要安全审查的 Persona（当前仅 `vision_qa` 人格）。

### 4.3 `CompositeSafetyGuard`（组合器）

```java
public class CompositeSafetyGuard implements SafetyGuard {
    private final List<SafetyGuard> guards;
    // 遍历 guards，首个 veto 短路返回，全放行则返回 ALLOW
}
```

- Chain of Responsibility 模式，但**当前未被任何 Persona 使用**——各 Persona 直接在 `safetyGuards()` 中传入单元素的 `List.of(new SpeedBasedDoorLockGuard(...))`。`CompositeSafetyGuard` 是面向未来的基础设施，留待后续注入多条安全规则时启用。

---

## 五、集成详情

### 5.1 配置装配（AgentConfigFactory）

各 Persona 的 SafetyGuard 配置：

| Persona | Guard 实例 | SpeedProvider | 附带 Terminator |
|---------|-----------|---------------|-----------------|
| **chat**（遗留） | `SpeedBasedDoorLockGuard` | `() -> parseSpeed(speedManager.getSpeedStatus())` | `SafetyVetoTerminator` |
| **scene** | `SpeedBasedDoorLockGuard` | 同上 | 无 |
| **TEXT** | `SpeedBasedDoorLockGuard` | 同上 | `SafetyVetoTerminator` |
| **vision_qa** | `AllowAllSafetyGuard` | 无（不使用） | 无 |

`parseSpeed()` 工具方法（`AgentConfigFactory.java:233-240`）：
```java
private static int parseSpeed(String statusJson) {
    try {
        return new JSONObject(statusJson).optInt("车速", 0);
    } catch (Exception e) {
        return 0;
    }
}
```

### 5.2 执行流程（AgentLoopOrchestrator）

SafetyGuard 在 AgentLoopOrchestrator 的**两个执行路径**中均有集成：

**遗留路径 `execute(String, Map)`** — 第 264-290 行：
1. 对每个 `ToolExecutionRequest`，初始化 `verdict = ALLOW`
2. 遍历 `config.safetyGuards()`，任一 guard 返回 VETO 则终止遍历
3. 若 `verdict.isVetoed()`：设置 `ctx.setLastSafetyVeto(verdict)`，结果设为 `"[SAFETY VETO] " + reason`，`vetoCount++`
4. 若 ALLOW：调用 `config.toolExecutor().execute(toolReq)` 真正执行
5. 通过 `ToolExecutionResultMessage.from(toolReq, result)` 写回 ChatMemory
6. **全否决短路**：若本次迭代全部工具被否决（`vetoCount == toolReqs.size() && vetoCount > 0`），经 PostProcessor → ResultCollector 直接返回，不再继续循环

**TEXT 路径 `execute(RequestSession, ContextPrepareResult)`** — 第 548-582 行：
1. 与遗留路径逻辑一致，但 ChatMemory 写入使用 `new ToolExecutionResultMessage(id, name, result)` 显式构造
2. 增加了取消检查（在执行前检查 `cancelCheck.isCancelled()`）
3. 工具异常不回抛，而是写入错误结果到 ChatMemory（P1-6 修复的内容）

### 5.3 SafetyVetoTerminator（辅助终止器）

```java
public class SafetyVetoTerminator implements LoopTerminator {
    public boolean shouldStop(AgentLoopContext ctx, ChatResponse response) {
        return ctx.lastSafetyVeto() != null && ctx.lastSafetyVeto().isVetoed();
    }
}
```

- **次要退出路径**：主要否决处理已在 AgentLoopOrchestrator 的工具循环中完成（否决结果写回 ChatMemory 后继续循环）。Terminator 的存在意义是：
  - 遗留路径中，否决后 LLM 收到 `[SAFETY VETO]` 文本后可能再次生成相同工具调用，Terminator 在出口处额外检查一次
  - TEXT 路径中全否决短路已在工具循环后直接返回，Terminator 作为兜底

### 5.4 Trace 集成（AgentTraceRecorder）

`finishTool(Span, String result, SafetyVerdict verdict)` 写入 3 个 Span 属性：

| 属性 | 条件 | 值 |
|------|------|-----|
| `TOOL_SUCCESS` | 全部 | `!vetoed` |
| `TOOL_SAFETY_VETO` | 全部 | `true` / `false` |
| `TOOL_SAFETY_VETO_REASON` | vetoed 时 | `verdict.reason()` |

---

## 六、模块在系统中的实际作用

### 6.1 已发挥作用

1. **运行时安全防护**：在 `set_door_lock` 解锁操作前检查车速，阻止行车中开门风险。这是当前唯一一条硬安全规则。
2. **否决结果的语义反馈**：否决不是静默失败——`[SAFETY VETO] 原因` 以 ToolResult 形式回到 ChatMemory，LLM 可在下轮输出中向乘客解释"当前车速 63 km/h，无法解锁车门，请先停车"。
3. **全否决短路**：当 LLM 某轮迭代的全部工具都被否决时，直接终止循环，避免 LLM 反复请求同一组被阻止的工具导致无限循环。
4. **Trace 可观测**：否决事件记录在 OpenTelemetry Span 属性中，可在 Phoenix 中追溯每次否决。

### 6.2 未使用的功能

- **CompositeSafetyGuard**：组合器已实现但未被使用，各 Persona 直接传递单 guard 列表
- **SafetyVetoTerminator 的兜底价值有限**：全否决短路机制在工具循环内已处理完毕，Terminator 仅在不触发短路的边缘路径（如遗留路径中部分工具被否决、部分成功的情况）才有意义

---

## 七、对外接口（API Surface）

### 7.1 供给端接口

**`SafetyGuard.evaluate(ToolExecutionRequest, AgentLoopContext)` → `SafetyVerdict`**
- 供 AgentLoopOrchestrator 调用
- 所有 Persona 共用同一调用模式：`for (guard : config.safetyGuards())`

**`SafetyVerdict.allow()` / `veto(String)`**
- 供各 Guard 实现返回审查结果
- `allow()` 线程安全单例

**`SafetyGuard.SpeedProvider.getCurrentSpeedKmh()`**
- 供 `SpeedBasedDoorLockGuard` 获取车速
- 在 `AgentConfigFactory` 中由 lambda 注入（委托 `VehicleSpeedManager.getSpeedStatus()`）

### 7.2 消费端接口

**`AgentLoopContext.lastSafetyVeto()`** → 供 `SafetyVetoTerminator.shouldStop()` 读取
**`ToolExecutionRecord.safetyVerdict()`** → 供 PostProcessor / ResultCollector / Trace 读取
**`AgentTraceRecorder.finishTool(Span, String, SafetyVerdict)`** → 供 AgentLoopOrchestrator 记录 Trace

---

## 八、测试覆盖

| 测试目标 | 文件 | 覆盖情况 |
|---------|------|---------|
| `SafetyVerdict` 创建/判断 | — | **无** |
| `SpeedBasedDoorLockGuard` 四种逻辑分支 | — | **无** |
| `AllowAllSafetyGuard` 始终放行 | — | **无** |
| `CompositeSafetyGuard` 组合/短路 | — | **无** |
| `SafetyVetoTerminator` 终止判断 | — | **无** |
| Trace 记录否决属性 | `AgentTraceRecorderTest` | **有**（1 个测试） |
| AgentLoopOrchestrator 全否决短路 | — | **无**（集成测试使用 mock executor 绕过） |

共 1 个测试涉及 Safety 模块，覆盖率极低。

---

## 九、遗留问题与风险

### 问题 1：仅有一条安全规则，扩展性不足

当前只有 `SpeedBasedDoorLockGuard` 一条规则，且硬编码了 `set_door_lock` 工具名和 `arg0` 参数位置。未来应支持：
- 更多安全规则（如行驶中禁止调节方向盘、禁止操作中控屏设置）
- 规则可配置化（哪些工具需要审查、阈值参数可调）
- 通用参数解析（不再依赖 `arg0` 的具体 JSON 路径）

### 问题 2：`CompositeSafetyGuard` 未启用

组合器已实现但未接入，当前各 Persona 直接传 `List.of(singleGuard)`。若后续添加第二条 Guard，需要：
- 改造 AgentConfigFactory 统一创建 `CompositeSafetyGuard(List.of(doorLockGuard, steeringWheelGuard, ...))`
- 或直接在 AgentConfigFactory 中往 List 添加多个 Guard（不依赖 CompositeSafetyGuard 也能工作）

### 问题 3：无独立测试

所有 SafetyGuard 实现均无单元测试。`SpeedBasedDoorLockGuard` 有 4 条逻辑分支（非门锁工具、锁门、低速解锁、高速解锁），均未被测试覆盖。依赖集成测试（mock executor）无法触发真实 Guard 逻辑。

### 问题 4：否决语义在两种路径中不一致

遗留路径中 ToolResult 通过 `ToolExecutionResultMessage.from(toolReq, result)` 创建（自动处理 tool name/id），TEXT 路径中通过 `new ToolExecutionResultMessage(id, name, result)` 显式构造。虽然在 TEXT 路径中否决时 `toolResult = "[SAFETY VETO] " + reason` 的格式一致，但 `ToolExecutionResultMessage` 的构造差异是遗留的技术债。

### 问题 5：SafetyVetoTerminator 的实际生效场景有限

TEXT 路径中全否决短路已在工具循环内直接返回（第 592-609 行），Terminator 在大多数情况下不会触发。其保留价值在于：
- 遗留路径（部分工具被否决、部分成功时，下轮迭代的附加检查）
- 未来可能新增的异步审查场景
- 作为 AgentConfig 7 组件契约中的一环保持完整性
