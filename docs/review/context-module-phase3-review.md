# Context 模块 Phase 3 验收审查

**审查日期：** 2026-07-07
**审查范围：** Phase 3 ContextOrchestrator 组装、渲染和 fallback（Task 3.1～3.2）
**审查依据：** `docs/plan/context-module-implementation-plan.md` Phase 3 全部任务
**审查人：** Claude Code

---

## 一、总体评估

**发现 1 个关键 Bug，修复前不得进入 Phase 4。** `ContextOrchestrator.build()` 中 fallback 结果的 section 被重复添加到 sections 列表，导致 `renderedExtraContext` 包含重复内容、section 计数虚高。

---

## 二、文件清单核对

### 2.1 新增生产文件

| 文件 | 状态 | 说明 |
|------|------|------|
| `context/ContextOrchestrator.java` | ⚠️ **含 Bug** | defaultForText 正确，build() 有 section 重复添加 Bug |
| `context/ContextBuildResult.java` | ✅ | success/fallback 双工厂方法，接口完整 |

### 2.2 修改测试文件

| 文件 | 新增用例 | 状态 |
|------|---------|------|
| `context/ContextProviderFailureTest.java` | +1（`build_providerExceptionCreatesDebugInfoAndContinues`） | ✅ |
| `context/ContextOrchestratorTest.java` | +1（`build_hybridModeCreatesFrameBeforeAgentLoop`） | ✅ |

---

## 三、关键 Bug

### 问题 1（Bug）：fallback 结果的 section 被重复添加到 sections 列表

**文件：** `ContextOrchestrator.java:69-74`

```java
// ── 现有代码 ──
if (result.success() && result.section() != null) {    // 条件 A
    sections.add(result.section());
}
if (result.fallback() && result.section() != null) {   // 条件 B
    sections.add(result.section());                      // 重复添加
}
```

**根因分析：**

`ContextProviderResult.fallback()` 的构造函数参数为 `(true, true, ...)`：
```java
public static ContextProviderResult fallback(String providerName, ContextSection section,
                                              String reason) {
    return new ContextProviderResult(true, true, providerName, section, reason);
    //                ────  ────
    //           success  fallback
}
```

因此 `fallback()` 同时满足：
- `result.success()` → **true**
- `result.fallback()` → **true**

两个 if 条件都命中，section 被 **add 两次**。

**导致后果：**
- `renderedExtraContext` 中 fallback provider 的内容出现 **重复**
- `ContextDebugInfo.sectionCount` 虚高（如 10 而非 9）
- `ContextFrame.sections` 列表包含重复条目，下游遍历会看到两份相同数据

**修复方案：**

将两段 if 合并为一段，只根据 section 是否有效来决定添加：

```java
if (result.section() != null) {
    sections.add(result.section());
}
```

由于 `success()` 和 `fallback()` 都产生有效的 section（`failure()` 的 section 为 null），只需判断 section 非空即可。

**严重程度：** 关键 — 会导致 Context 注入到 LLM 的内容中包含重复文本。

---

## 四、非阻塞审查项

### 4.1 `build()` 无 session 空值保护

若传入 null session，`session.personaId()` 在 `ContextFrameBuilder.fromSession(session)` 内部触发 NPE。实际调用链中 `RequestSession` 由 `AgentRuntime.startSession()` 创建且始终非空，运行时不会触发。

### 4.2 FULL_CONTEXT 模式无测试覆盖

`ContextOrchestrator.build()` 对 FULL_CONTEXT 的处理（降级为 HYBRID + 记录 `full_context_deferred`）没有任何测试覆盖。虽属 deferred 行为，但如果后续阶段修改了 HYBRID 路径，无法通过现有测试发现 FULL_CONTEXT 路径被意外改变。

建议在 `ContextOrchestratorTest` 中增加一个简单测试：

```java
@Test
public void fullContextMode_delegatesToHybridAndRecordsDeferred() {
    ContextBuildResult result = ContextOrchestrator.defaultForText(
            ContextBuildInput.builder().mode(ContextMode.FULL_CONTEXT).build())
            .build(TestRequestSessions.textSession(...));
    assertTrue(result.fallbackUsed());
    assertTrue(result.frame().debugInfo().fallbackProviders()
            .contains("full_context_deferred"));
}
```

### 4.3 `renderExtraContext` 在 OBSERVE_ONLY 模式返回 `""`

```java
if (mode == ContextMode.OBSERVE_ONLY) {
    return "";
}
```

OBSERVE_ONLY 模式下 `renderedExtraContext` 为空字符串，但 `ContextFrameBuilder.renderedExtraContext("")` 会存下空串。`ContextFrame.renderedExtraContext()` 返回 `""`。下游 `ContextExtraPreProcessor` 会因 `text.trim().isEmpty()` 跳过注入。行为正确 ✅

### 4.4 `findFirstContent` 在多 section 同类型时只取第一个

```java
private static String findFirstContent(List<ContextSection> sections, ContextSectionType type) {
    return sections.stream()
            .filter(s -> s.type() == type)
            .findFirst()
            .map(ContextSection::content)
            .orElse("");
}
```

当前 9 个 Provider 的 type 各不相同，不存在同类型冲突。未来若有同类型多 section 需求时需重新评估此设计。当前正确 ✅

---

## 五、审查结论

**Phase 3 验收不通过，1 个关键 Bug 需修复后重新审查。**

| 优先级 | 问题 | 处理建议 |
|--------|------|---------|
| **Bug** | fallback 结果 section 被重复添加 | 合并 `success()` / `fallback()` 两个 if 为 `section != null` 单条件 |
| 建议 | FULL_CONTEXT 无测试覆盖 | 增加简单验收测试 |
