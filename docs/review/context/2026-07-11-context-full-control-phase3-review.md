# Context 全权控制改造 Phase 3 验收审查

**审查日期：** 2026-07-11
**审查范围：** Phase 3 影子装配与动态工具差异验证
**审查依据：** `docs/plan_overall/2026-07-11-context-full-control-implementation-plan.md` Phase 3
**审查人：** Claude Code

---

## 一、总体评估

**不通过，3 个问题必须修复。** 核心发现是影子装配**没有在生产链路中实际启用**——`textOrchestrator` 没有被注入 `ContextAssemblyGateway`，AgentLoop 不会执行影子 assemble。影子比较器存在逻辑 Bug，`ContextShadowRecorder` 缺失。

全量单测通过仅说明独立组件可编译，没有覆盖"影子装配是否真正在 AgentLoop 内运行"这一关键集成点。

---

## 二、本阶段做了什么

Phase 3 的目标是在旧链路的每次模型调用前，额外跑一次 Context 装配（影子装配），比较新旧两套输入的差异，证明新链路可以安全切换。

**实际完成的工作：**

1. **AgentExecutor 接口变更** — `execute(String, Map)` → `execute(RequestSession, ContextPrepareResult)`。所有 runtime lambda 和测试已同步更新。
2. **AgentRuntime.execute() 改造** — 改为调用 `ContextOrchestrator.prepare()`，将 `RuntimeCancelChecker` 适配为 `ContextCancelChecker`，处理 prepare 失败/取消。
3. **AgentLoop 影子装配适配点** — AgentLoopOrchestrator 增加了 `ContextAssemblyGateway` 参数和构造期判断（gateway != null 时执行）。
4. **影子比较器骨架** — `ContextShadowComparator`、`ContextShadowComparison`、`ContextShadowComparatorTest` 已创建。
5. **ContextAssemblyGateway 接口** — 窄接口已创建（Phase 2 缺失项已补充）。

---

## 三、阻塞问题

### 问题 1（阻塞）：`textOrchestrator` 未注入 ContextAssemblyGateway，生产影子装配未启用

**位置：** `AIAgentService.kt:378-385`

**现状：**
```kotlin
textOrchestrator = AgentLoopOrchestrator(
    AgentConfigFactory.createTextPersona(...),
    this, promptManager!!, memoryOrchestrator, toolRegistry.toolSpecifications
    // ← 5-arg constructor, NO ContextAssemblyGateway passed
)
```

**后果：**`AgentLoopOrchestrator` 的 `contextAssemblyGateway` 字段为 null，第 208 行 `if (contextAssemblyGateway != null)` 判定为 false，影子装配被跳过。每轮模型调用只在旧路径装配，没有生成新链路的候选输入用于比较。

**修复方案：** 将 `contextOrchestrator` 的 assemble 方法适配为 `ContextAssemblyGateway` 注入到 textOrchestrator 构造器中。注意 `ContextOrchestrator` 需要实现 `ContextAssemblyGateway` 接口（当前只实现了同名方法，未声明 `implements ContextAssemblyGateway`）。

### 问题 2（阻塞）：ContextShadowRecorder 缺失

**计划要求：** `context/ContextShadowRecorder.java` 负责将影子比较结果写入 Trace event 和测试捕获器。

**现状：** 该文件不存在。`AgentLoopOrchestrator` 中的影子装配没有记录任何诊断信息——`contextAssemblyGateway.assemble(shadowRequest)` 的结果被完全丢弃（既不做比较也不记录）。

**修复方案：** 创建 `ContextShadowRecorder`，至少支持写入 Trace event（生产）和测试捕获器（单元测试）。

### 问题 3（阻塞）：影子比较器 `classifyDifference` 逻辑 Bug

**位置：** `ContextShadowComparator.java:109-123`

```java
private static ContextShadowComparison classifyDifference(String scenarioId, String reason) {
    String allowed = ALLOWED_DIFFERENCES.getOrDefault(scenarioId, "");
    if (!allowed.isEmpty()) {
        for (String code : allowed.split(";")) {
            if (reason.contains(code) || code.isEmpty()) {
                continue;
            }
        }
        // ← 这段代码遍历了所有 code 后啥也没做，直接落到这里
        if (!allowed.isEmpty()) {   // ← 这个条件一定为 true（刚检查过）
            return ContextShadowComparison.EXPECTED_DIFFERENCE;
        }
    }
    return ContextShadowComparison.BLOCKING_DIFFERENCE;
}
```

**Bug 描述：** 无论 `reason` 具体是什么（`message_count_mismatch`、`tool_count_mismatch`、`system_prompt_mismatch`），只要 `scenarioId` 对应的 `ALLOWED_DIFFERENCES` 非空，方法始终返回 `EXPECTED_DIFFERENCE`。例如 S03 的允许差异是 "ALL_FALLBACK_KEPT"，但如果出现 `message_count_mismatch`，也会被错误地判定为 EXPECTED_DIFFERENCE。

**修复要求：** `classifyDifference` 必须依据 `reason` 具体内容判断——只有 reason 明确在允许差异表中才算 EXPECTED，其余必须 BLOCKING。

---

## 四、非阻塞但需关注的问题

### 4.1 影子装配传了 null ContextFrame

`AgentLoopOrchestrator.java:211-213`：
```java
ContextAssemblyRequest shadowRequest = new ContextAssemblyRequest(
        null, i, chatMemory.messages(), null, null, false);
```

ContextFrame 传了 null，导致 Assembler 无法访问 Provider 的 Contribution（prompt、intent、tool 等）。影子装配即使运行起来，Assembler 也拿不到 System Prompt 和 ToolSpecification。

**影响：** 即使修复了问题 1（注入 gateway），当前影子结果也不可用。需要在 AgentLoop 中传递正确的 ContextFrame。

### 4.2 影子比较器未在 AgentLoop 中实际调用

`ContextShadowComparator.compare()` 和 `compareTools()` 方法已实现，但 AgentLoop 的影子装配代码只调了 `gateway.assemble()` 并丢弃结果，没有调用 comparator 做新旧比较。

### 4.3 AgentLoopContextShadowTest 只验证"接口可调用"

测试仅验证 gateway lambda 能被执行 1 次，没有验证：
- 影子装配是否在真实 AgentLoop 内集成
- 比较器是否生成有效的 MATCH/EXPECTED_DIFFERENCE 结果
- 多轮 Tool Calling 后车辆状态是否刷新

### 4.4 `ContextOrchestrator` 未声明实现 `ContextAssemblyGateway`

ContextOrchestrator 有 `assemble()` 方法，Javadoc 说"实现 ContextAssemblyGateway"，但类声明中没有 `implements ContextAssemblyGateway`。AI 开发者看到 `ContextOrchestrator` 无法赋值给 `ContextAssemblyGateway` 类型变量。

---

## 五、审查结论

**Phase 3 验收不通过，3 个阻塞问题需修复后重新审查。**

| 优先级 | 问题 | 影响 |
|--------|------|------|
| **阻塞** | textOrchestrator 未注入 ContextAssemblyGateway | 影子装配未在生产链路中启用 |
| **阻塞** | ContextShadowRecorder 缺失 | 影子结果无处记录 |
| **阻塞** | classifyDifference 逻辑 Bug | 允许差异判断形同虚设 |
| 需关注 | 影子装配传了 null ContextFrame | 即使 gateway 接入也无法正常装配 |
| 需关注 | Comparator 未在 AgentLoop 中调用 | 有比较器但无人使用 |
| 需关注 | ContextOrchestrator 未 implements ContextAssemblyGateway | 窄接口无法赋值 |
