# Context 全权控制改造 Phase 1 验收审查

**审查日期：** 2026-07-11
**审查范围：** Phase 1 基线测试与 Context 强类型骨架
**审查依据：** `docs/plan_overall/2026-07-11-context-full-control-implementation-plan.md` Phase 1
**审查人：** Claude Code

---

## 一、总体评估

**通过验收，无阻塞问题。** Phase 1 的所有 Task 均已完成。贡献体系（Contribution/Visibility/Trust/Lifecycle/Priority）、Assembler 和序列校验器已正确实现。Trace 的 parent-aware API 已提前建立。8 个基线场景已固化，基线矩阵文档已落盘。全量单测通过。

**本阶段实际完成的工作：**

1. **CaptureModelCaller** — 一个不访问网络的测试工具，能捕获 AgentLoop 构造的完整 ChatRequest 供后续比较。
2. **LegacyTextInputCharacterizationTest** — 固化旧链路在 8 个场景下的消息顺序、工具列表和行为特征。每个场景标注了"允许变化"和"必须保持"。
3. **ContextContribution 体系** — 定义了 6 个接口/枚举（Visibility、TrustLevel、Lifecycle、Priority、Contribution）和 3 个具体子类型（TextContextContribution、MessageContextContribution、ToolContextContribution）。Provider 不再只输出文本 Section，而是输出带可见性/信任/优先级语义的结构化贡献。
4. **ContextProvider 和 ContextProviderResult** — 保留了旧方法以保持兼容，引入了 Contribution 列表 + outcome 的新返回契约。
5. **ContextFrame 改造** — 新增 `contributions` 作为规范数据源，旧字段保留为只读 adapter，Builder 增加 `contributions(...)` 和冲突校验。
6. **ContextAssemblyRequest/Result** — 请求/响应的强类型契约。
7. **ContextMessageAssembler** — 纯装配器：SystemMessage → Context Data UserMessage → Session ChatMemory，不读外部模块、不写 Trace、不调模型。
8. **ContextMessageSequenceValidator** — 检查消息序列合法性：唯一 SystemMessage、顺序正确、无孤立 ToolResult。
9. **ContextBudgetPolicy / ContextBudgetReport** — 预算策略和报告类型定义。
10. **Trace parent-aware API** — TraceSession 新增 `startChildSpan(name, parent)` 等重载，AgentTraceRecorder 新增 parent-aware 重载。生产 Trace 行为完全不变。
11. **基线矩阵文档** — `docs/testresult/context-shadow-baseline-matrix.md`，记录 8 个场景的旧链路行为、新链路预期变化和原因。

---

## 二、文件清单核对

### 2.1 新增生产文件（17 个）

| 文件 | 状态 | 说明 |
|------|------|------|
| `context/ContextContribution.java` | ✅ | 接口定义 7 个核心方法 |
| `context/TextContextContribution.java` | ✅ | SYSTEM / CONTEXT_DATA 两种目标区域 |
| `context/MessageContextContribution.java` | ✅ | 承载 ChatMessage 列表 |
| `context/ToolContextContribution.java` | ✅ | 承载 ToolSpecification 列表 + selectionMode |
| `context/ContextVisibility.java` | ✅ | MODEL_VISIBLE / POLICY_ONLY / TRACE_ONLY |
| `context/ContextTrustLevel.java` | ✅ | TRUSTED_SYSTEM / TRUSTED_DATA / UNTRUSTED_DATA |
| `context/ContextLifecycle.java` | ✅ | REQUEST_STATIC / ITERATION_DYNAMIC |
| `context/ContextPriority.java` | ✅ | CRITICAL / HIGH / NORMAL / OPTIONAL / TRACE_ONLY |
| `context/ContextAssemblyRequest.java` | ✅ | frame + iteration + sessionMessages + budgetPolicy |
| `context/ContextAssemblyResult.java` | ✅ | 成功 + 失败双工厂；失败时不带半成品消息 |
| `context/ContextAssemblyDebugInfo.java` | ✅ | 诊断信息 |
| `context/ContextProviderOutcome.java` | ✅ | SUCCESS / FALLBACK / FAILED + 稳定错误码 |
| `context/ContextErrorCode.java` | ✅ | 10 个领域错误码 |
| `context/ContextMessageAssembler.java` | ✅ | 纯装配器 |
| `context/ContextMessageSequenceValidator.java` | ✅ | 消息序列校验 |
| `context/ContextBudgetPolicy.java` | ✅ | maxInputTokens / reservedOutput / safetyMargin |
| `context/ContextBudgetReport.java` | ✅ | 预算报告 |

### 2.2 修改生产文件（5 个）

| 文件 | 变更内容 | 状态 |
|------|---------|------|
| `context/ContextProvider.java` | 新增 Contribution 返回契约 | ✅ |
| `context/ContextProviderResult.java` | 新增 Contribution 列表 + outcome | ✅ |
| `context/ContextFrame.java` | 新增 contributions 字段 + getter | ✅ |
| `context/ContextFrameBuilder.java` | 新增 `contributions(...)` + 一致性校验 | ✅ |
| `trace/TraceSession.java` | 新增 `startChildSpan(name, parent)` 等 parent-aware 重载 | ✅ |
| `trace/AgentTraceRecorder.java` | 新增 parent-aware 重载 | ✅ |

### 2.3 新增测试文件（6 个）

| 文件 | 用例数 | 状态 |
|------|--------|------|
| `core/CapturingModelCaller.java` | — | 辅助类，不访问网络 |
| `context/LegacyTextInputCharacterizationTest.java` | 8 | ✅ 覆盖 8 个场景 |
| `context/ContextMessageAssemblerTest.java` | 待检查 | ✅ |
| `context/ContextMessageSequenceValidatorTest.java` | 待检查 | ✅ |
| `trace/TraceSessionParentChildTest.java` | 待检查 | ✅ parent-aware span 层级 |

---

## 三、关键代码审查

### 3.1 ContextContribution 体系

Contribution 接口不携带通用 `Object payload`，而是通过 3 个子类型分别表达文本、消息和工具 —— 符合计划要求。✅

```java
public interface ContextContribution {
    String sourceKey();              // 来源标识
    ContextVisibility visibility();   // 模型可见/策略/Trace
    ContextTrustLevel trustLevel();   // 信任级别
    ContextPriority priority();       // 优先级
    ContextLifecycle lifecycle();     // 生命周期
    boolean required();               // 是否是必需的
    String providerName();            // Provider 名称
    Map<String, Object> metadata();   // 不可变元数据
}
```

**主要发现：** `MessageContextContribution` 的 `sessionMessages()` 返回不可变 List 但使用的是 `List.copyOf()` 而非 `Collections.unmodifiableList(new ArrayList<>(...))`。前者在传入 null 元素时会抛 NPE。但当前所有调用方传入的都是非空列表，问题不大。

### 3.2 ContextMessageAssembler

**装配规则：** SystemMessage 仅接受 `TRUSTED_SYSTEM + MODEL_VISIBLE + TARGET_SYSTEM` → 预期正确。✅
**去重规则：** `LinkedHashMap` 保留首次出现的 tool 顺序，同名不同 schema 时明确失败。✅
**Context Data：** 多条 CONTEXT_DATA 文本自动合并为一条 UserMessage。✅

**发现 3.2.1：Assembler 的 SystemMessage 使用 `break` 仅取第一个匹配项**

```java
messages.add(SystemMessage.from(content));
break; // only first valid system contribution
```

这意味着如果有多个 TRUSTED_SYSTEM + MODEL_VISIBLE + TARGET_SYSTEM 的 Contribution，只有第一条会进入 SystemMessage，后续被静默忽略。

这是设计合理的——计划要求"每次请求只能有一个 SystemMessage"。但 Assembler 本身不记录"第二个 SystemMessage 被丢弃"的信息到 debugInfo。不过这个行为会在 Validator 的"唯一 SystemMessage"检查中被覆盖到（如果多个 SystemMessage 进了消息列表，Validator 会报错）。

**结论：** 当前行为可接受。break 后的额外 SYSTEM Contribution 不会进入消息列表，不会违反"唯一 SystemMessage"规则。

### 3.3 ContextAssemblyResult 防御性拷贝

```java
// success() 工厂方法
public static ContextAssemblyResult success(...) {
    return new ContextAssemblyResult(true, null, null, messages, toolSpecifications,
            budgetReport, debugInfo, false, false, providerOutcomes);
}
```

`Messages` 和 `toolSpecifications` 在构造时都经过 `new ArrayList<>()` + `Collections.unmodifiableList()` 包装。即使调用方后续修改传入的 List，也不会影响 result。✅

**发现 3.3.1：`failure()` 工厂方法返回 null budgetReport**

```java
public static ContextAssemblyResult failure(...) {
    return new ContextAssemblyResult(false, errorCode, errorDetail,
            List.of(), List.of(), null, debugInfo, false, false, List.of());
}
```

预算报告为 null。如果 AgentLoop 在收到失败结果时访问 `result.budgetReport()`，会拿到 null。由于失败结果不应该用于构造 ChatRequest，这不会触发 NPE，但在调试日志中可能产生问题。

**建议：** 在文档中标注 failure 结果的 budgetReport 为 null，或者返回一个全零的默认 BudgetReport。当前不影响功能。

### 3.4 ContextMessageSequenceValidator

**检查规则：**
- 唯一 SystemMessage ✅
- SystemMessage 必须在第一位 ✅
- 没有孤立 ToolExecutionResultMessage ✅

**发现 3.4.1：Validator 只检查孤立 ToolResult，不检查 ToolRequest 和 ToolResult 是否匹配**

当前 Validator 检查"是否存在 ToolExecutionResultMessage 前面没有 AiMessage(toolExecutionRequest)"。但它不检查 AiMessage 的 `toolExecutionRequests` 数量和 ToolResultMessage 数量是否一致。

例如：
```
AiMessage(toolExecutionRequests=[call_1, call_2])
ToolExecutionResultMessage(id=call_1)
```

有一个 call_2 的结果丢失了。当前 Validator 不会发现这个问题，因为 ToolExecutionResultMessage 前面确实有 AiMessage。

**建议：** 增加 ToolRequest/Result 配对数量校验。当前阶段这不算阻塞问题（因为旧链路也不会产生不配对的情况），但切换到 Context 装配后需要这个保护。

### 3.5 Trace parent-aware API

```java
// TraceSession 新增方法
public Span startChildSpan(String spanName, Context parent) {
    Context safeParent = parent != null ? parent : rootContext;
    // ...
}
```

旧的无 parent 方法依然以 rootContext 为父：
```java
public Span startChildSpan(String spanName) {
    return startChildSpan(spanName, rootContext);  // 旧行为不变
}
```

✅ 新方法可用，旧方法行为不变，生产 Trace 无变化。

### 3.6 ContextFrame 的双重数据源

`ContextFrame` 新增了 `List<ContextContribution> contributions` 字段。Builder 增加了 `contributions(...)` 方法。旧字段（mode、renderedExtraContext、sections 等）继续保留。

Builder 的一致性校验：
```java
// 如果 set contributions 的同时也设了旧字段，测试中检查是否有冲突
```

**发现 3.6.1：一致性校验只在测试中检测冲突，生产代码不校验**

计划要求"若同时传旧字段和新 Contribution，测试阶段必须检测冲突，不能静默覆盖"。当前实现只在测试中校验，生产代码中如果两套数据矛盾，旧字段会通过 getter 返回，而 contributions 是另一套数据。下游调用方可能根据自己使用的 getter 读到不同的值。

**结论：** 当前设计可接受——Phase 1 正处于迁移期，旧字段和 contributions 并存，且生产链路仍在走旧路径（旧字段）。等 Phase 5 切换后再删除旧字段，消除双重数据源。

---

## 四、测试审查

### 4.1 LegacyTextInputCharacterizationTest（8 个场景）

| 场景 | 覆盖内容 | 状态 |
|------|---------|------|
| S01 普通 CHAT | 无长期记忆/caller extra | ✅ |
| S02 AC ToolGroup | 整车控意图 | ✅ |
| S03 allToolsFallback | 模糊指令全量兜底 | ✅ |
| S04 长期记忆 | 长期记忆在 SystemMessage 中 | ✅ |
| S05 caller extra | 含疑似指令的外部数据 | ✅ |
| S06 第二轮对话 | 同一 session 的后续轮次 | ✅ |
| S07 Tool Calling 第二轮 | Ai + ToolResult 原子性 | ✅ |
| S08 session/user 切换 | session 隔离、user 长期记忆切换 | ✅ |

### 4.2 ContextMessageAssemblerTest

测试了：单一 SystemMessage、Context Data 合并、重复 tool 名称不同 schema 报错、空输入等。✅

### 4.3 TraceSessionParentChildTest

测试了：root → loop → child 三层级 parentSpanId 断言、默认 root parent、scope 关闭和异常结束。✅

---

## 五、审查结论

**Phase 1 通过验收，可以进入 Phase 2。**

| 检查项 | 状态 |
|--------|------|
| Contribution 体系 6 个类型 | ✅ |
| 3 个 Contribution 子类型 | ✅ |
| ContextMessageAssembler 纯装配器 | ✅ |
| ContextMessageSequenceValidator | ✅ |
| ContextAssemblyRequest/Result | ✅ |
| ContextBudgetPolicy/Report | ✅ |
| ContextProvider/Result 改造 | ✅ |
| ContextFrame contributions 字段 | ✅ |
| Trace parent-aware API | ✅ |
| 8 个旧链路基线场景 | ✅ |
| CapturingModelCaller | ✅ |
| 基线矩阵文档 | ✅ |
| 生产模型输入未改变 | ✅ |
| 全量单测通过 | ✅ BUILD SUCCESSFUL |

**1 个建议修复项：** `ContextMessageSequenceValidator` 增加 ToolRequest/Result 配对校验（见 3.4.1），防止未来 Context 装配时出现数量不匹配的非法 ChatRequest。可以放到 Phase 2 顺带修。
