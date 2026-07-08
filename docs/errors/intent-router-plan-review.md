# IntentRouter 计划审查报告

**审查日期：** 2026-07-02  
**计划文件：** [`docs/plan/intent-router-introduction-plan.md`](../plan/intent-router-introduction-plan.md)  
**审查人：** Claude Code  

---

## 一、总体评估

计划整体可行，架构设计清晰，与现有 `runtime` 包的边界划分正确。任务分解细致，测试驱动开发流程合理。

**发现的 3 个中危问题和 2 个低危建议，没有致命阻塞性问题。**

---

## 二、中危问题

### 2.1 `routeIntentSafely()` 方法体未完整定义

**位置：** Task 4, Step 4

计划只说 "routeIntentSafely(...) 必须捕获异常，并返回 UNKNOWN/NONE"，但未给出方法签名和完整实现。

**应补充的代码：**

```java
private IntentResult routeIntentSafely(AgentRequest request) {
    try {
        String text = request.getText() != null ? request.getText() : "";
        String inputType = request.getInputType() != null ? request.getInputType() : "TEXT";
        return intentRouter.route(text, inputType);
    } catch (Exception e) {
        return IntentResult.unknown("", "TEXT", "router_exception");
    }
}
```

**影响：** 不明确的话，实现者需自行推断参数提取逻辑，有风险在 null check 上踩坑。

---

### 2.2 `KeywordIntentRouter` 的 `debugReason` 在不同匹配路径下的取值需明确枚举

**位置：** Section 2.5 匹配策略

计划定义了 4 种 `debugReason` 值：`matched:<tag>`、`fallback_chat`、`empty_text`、`router_exception`。但以下场景的取值需补充说明：

| 场景 | 当前计划未覆盖 | 建议取值 |
|------|---------------|---------|
| 多 tag 命中数相同，固定优先级决出的 winner | 未说明 | `matched:<tag> (priority)` — 加注 priority 便于区分 |
| null 文本传入 `IntentResult.unknown()` | 定义为 `router_exception`，但 null text 可能是调用方 bug 而非 router 异常 | 建议增加 `null_input` 或合并入 `empty_text` |
| 文本非空且无业务关键词 → `CHAT/LOW` | 已说明 `fallback_chat` | 无需改动 |

**影响：** 缺少明确约定后，后续调试时可能看不懂 `debugReason` 的实际含义。

---

### 2.3 `RequestSessionFactory.create()` 参数增加后，所有旧调用点需要同步更新

**位置：** Task 3, Step 4

计划中提到 "所有旧的 `factory.create(request, "chat", traceContext)` 改为传入明确 `IntentResult`"，但未枚举所有旧调用点：

1. `AgentRuntimeTest.execute_callsExecutorWithNormalizedContext()` — 通过 `runtime.startSession()` 间接调用，需确认不直接调用 `sessionFactory.create()`
2. `AgentRuntimeTest.timeoutResult_createsTimeoutResultWithSessionIds()` — 同上
3. `AgentRuntimeTest.execute_mapsExecutorExceptionToExceptionResult()` — 同上
4. `AgentRuntime` 内部 `startSession()` — 已在 Task 4 中修改

**确认结论：** 已核实 — 现有测试中只有 `RequestSessionFactoryTest` 直接调用了 `factory.create()`，`AgentRuntimeTest` 通过 `runtime.startSession()` 间接调用。`AgentRuntime` 自身在 `startSession()` 中的调用已在 Task 4 修改。计划覆盖充分。

---

## 三、低危建议

### 3.1 缺少 null/null 文本的关键词匹配测试

**位置：** Task 2, Step 1 测试清单

计划有 5 个测试，覆盖了 3 种业务 tag + fallback CHAT + blank text。建议补充：

```java
@Test
public void route_returnsUnknownForNullText() {
    IntentResult result = new KeywordIntentRouter().route(null, "TEXT");
    assertEquals(IntentTag.UNKNOWN, result.intentTag());
    assertEquals(IntentConfidence.NONE, result.confidence());
}
```

**影响：** 不阻塞实现，但 `null` 文本在 Runtime 中来自 `request.getText()` 可能是 null 的情况需防御。

---

### 3.2 关键词 "温度调到" 的匹配逻辑需要确认

**位置：** Section 2.5 关键词表

计划在关键词表中列出 `温度调到` 同时在正则建议中写 `温度.*调到|调到.*度`。但如果 KeywordIntentRouter 第一版仅用 `contains()` 做匹配（无正则），"空调温度调到二十二度" 可命中关键词表中的 `空调`（→ VEHICLE_AC）和 `温度调到`（→ VEHICLE_AC）。但如果完整关键词表中同时有 `温度`（→ VEHICLE_AC），匹配逻辑没问题。

**建议：** 在 Task 2 Step 4 中明确第一版关键词表是否引入正则，还是纯字符串 `contains()`。避免实施时混淆。

---

## 四、已核实无误的设计点

| 检查项 | 状态 |
|--------|------|
| `AgentRuntime` 构造函数兼容性（1/3/4 参数版本链式委托） | ✅ 现有 1/3 参构造委托至新 4 参构造，AIAAgentService.kt 无需修改 |
| `TraceSession.setAttribute()` 存在且可用 | ✅ 已确认有 `setAttribute(String, String)` 方法 |
| `RequestSession` 构造函数增加 `IntentResult` 后 getter 命名一致 | ✅ 计划使用 `session.intentResult()` 风格，与现有 `session.userInput()` 一致 |
| `RuntimeResult` 提供 `.success()` / `.output()` / `.errorType()` | ✅ 已确认 |
| 现有测试在 plan 修改后不会直接报编译错误 | ✅ 所有调用点均有明确修改计划 |
| Trace 写入 `null` session 不抛异常 | ✅ `writeIntentToTrace` 有 null guard，测试中的 `new TraceContext("a", "b", null)` 安全跳过 |
| 不修改 `AIAgentService.kt` | ✅ `AgentRuntime` 1 参构造链路不变，Service 构造代码无需修改 |
| Java 11 兼容性 | ✅ `List.of()` 可用，项目 `sourceCompatibility = JavaVersion.VERSION_11` |
| JUnit4 兼容 | ✅ 项目 `testImplementation(libs.junit)` 使用 JUnit4 |

---

## 五、审查结论

**计划可以进入实施阶段。** 建议在实施前补充以上 3 个中危问题的细节（尤其是 `routeIntentSafely()` 的完整签名和方法体），低危问题可在实施过程中一并处理。
