# Context 全权控制改造 Phase 2 验收审查

**审查日期：** 2026-07-11
**审查范围：** Phase 2 Provider 真实化与能力模块读接口
**审查依据：** `docs/plan_overall/2026-07-11-context-full-control-implementation-plan.md` Phase 2
**审查人：** Claude Code

---

## 一、总体评估

**有条件通过，1 个缺失文件需补充。** Provider 真实化改造基本正确、能力模块窄接口完备、ContextOrchestrator 已拆分为 prepare/assemble 两条链路、MemorySnapshot 的 sessionId 前缀问题已修正。全量单测通过。

**本阶段实际完成的工作：**

1. **11 个 Provider 全面改造** — 每个 Provider 现在同时输出旧 Section（供旧 build() 兼容）和新 Contribution（供影子装配用）
2. **ContextOrchestrator 拆分为 prepare/assemble** — 8 个请求级静态 Provider + 3 个迭代级动态 Provider
3. **ToolRegistry 窄接口** — `toolSpecificationsByNames`（名称严格解析）+ `enabledToolSpecifications`（全量 fallback）
4. **MemoryOrchestrator 窄接口** — `sessionMemorySnapshot`（短期）+ `longTermMemorySnapshot`（长期）+ `LongTermMemorySnapshot` 类型
5. **MemorySnapshot sessionId 修正** — 新增保留原始 sessionId 的构造函数，Context 路径不再看到 `memory:` 前缀
6. **ContextBuildInput 扩展** — 增加 ToolRegistry、ContextCancelChecker
7. **新增 3 个 Provider** — LongTermMemoryContextProvider、SessionMemoryContextProvider、CallerExtraContextProvider
8. `ToolSpecNotFoundException` 等支持类型

---

## 二、文件清单核对

### 2.1 本次新增/修改的生产文件

| 文件 | 变更 | 状态 |
|------|------|------|
| 所有 9 个旧 Provider | 新增 lifecycle() + 返回 Contribution | ✅ |
| `provider/LongTermMemoryContextProvider.java` | 新增 | ✅ |
| `provider/SessionMemoryContextProvider.java` | 新增 | ✅ |
| `provider/CallerExtraContextProvider.java` | 新增 | ✅ |
| `provider/ToolGroupContextProvider.java` | 改为调用 ToolRegistry 解析真实 ToolSpecification | ✅ |
| `provider/PromptContextProvider.java` | 调用 PromptManager.render() 渲染真实 System Prompt | ✅ |
| `provider/UserInputContextProvider.java` | 使用 SpeakerMessageFormatter 生成 CURRENT_USER Message | ✅ |
| `provider/VehicleStateContextProvider.java` | 删除 HYBRID mode 分支，返回真实动态 Contribution | ✅ |
| `provider/TimeContextProvider.java` | 删除 HYBRID mode 分支，返回真实动态 Contribution | ✅ |
| `context/ContextOrchestrator.java` | 拆分为 prepare/assemble 两条链路 | ✅ |
| `context/ContextBuildInput.java` | 增加 ToolRegistry 等字段 | ✅ |
| `context/ContextProvider.java` | 新增 lifecycle() 默认方法 | ✅ |
| `context/ContextProviderResult.java` | 新增 fromLegacySection() + Contribution 列表 | ✅ |
| `context/ContextProviderStatus.java` | 新增 | ✅ |
| `context/ContextCancelChecker.java` | 新增 | ✅ |
| `context/ContextPrepareResult.java` | 新增 | ✅ |
| `memory/MemoryOrchestrator.java` | 新增 sessionMemorySnapshot() + longTermMemorySnapshot() | ✅ |
| `memory/MemorySnapshot.java` | 新增 rawSessionId 构造器 | ✅ |
| `memory/LongTermMemorySnapshot.java` | 新增 | ✅ |
| `ai/.../tool/ToolRegistry.java` | 新增 toolSpecificationsByNames() + enabledToolSpecifications() | ✅ |
| `ai/.../tool/ToolSpecNotFoundException.java` | 新增 | ✅ |

### 2.2 缺失文件

| 计划中列出的文件 | 状态 | 说明 |
|-----------------|------|------|
| `context/ContextPreparer.java` | ❌ 缺失 | ContextOrchestrator 的 Javadoc 声称实现此接口，但独立接口文件不存在。`prepare()` 方法直接定义在 ContextOrchestrator 上 |
| `context/ContextAssemblyGateway.java` | ❌ 缺失 | 同上，`assemble()` 方法直接定义在 ContextOrchestrator 上 |
| `test/.../MemorySnapshotSessionIdBoundaryTest.java` | ❌ 缺失 | 计划 Task 2.2 Step 5 要求新增此测试 |

---

## 三、关键代码审查

### 3.1 Provider 真实化

**3.1.1 PromptContextProvider — 调用 PromptManager.render() ✅**

```java
renderedPrompt = promptManager.render(templateName);
```
失败时标记为 `REQUIRED_PROVIDER_FAILED`。输出 `TRUSTED_SYSTEM`、`CRITICAL`、`TARGET_SYSTEM` Contribution。

**3.1.2 ToolGroupContextProvider — 解析真实 ToolSpecification ✅**

三种分支：
- CHAT_ONLY_GROUP → 空工具集合 ✅
- allToolsFallback → `toolRegistry.enabledToolSpecifications()` ✅
- 明确名称 → `toolRegistry.toolSpecificationsByNames(names)` ✅

异常分支：`ToolSpecNotFoundException` 捕获后标记为 `TOOL_SPEC_RESOLUTION_FAILED`，不移回全量。✅

**3.1.3 UserInputContextProvider — 生成 CURRENT_USER MessageContribution ✅**

```java
String formattedText = SpeakerMessageFormatter.formatUserMessage(userId, rawInput);
UserMessage currentUserMessage = UserMessage.from(formattedText);
```
输出的 `MessageContextContribution` 标记为 `SOURCE_CURRENT_USER`。✅

**3.1.4 MemoryContextProvider 拆分为 LongTermMemory 和 SessionMemory ✅**

旧 `MemoryContextProvider.java` 保留为适配器，新拆出：
- `LongTermMemoryContextProvider` — REQUEST_STATIC，读 `MemoryOrchestrator.longTermMemorySnapshot(userId)` ✅
- `SessionMemoryContextProvider` — ITERATION_DYNAMIC，读 `MemoryOrchestrator.sessionMemorySnapshot(sessionId, maxMessages)` ✅

### 3.2 ContextOrchestrator prepare/assemble

```java
public static ContextOrchestrator defaultForText(ContextBuildInput input) {
    return new ContextOrchestrator(input,
        // request-static: 8 个 Provider
        List.of(Runtime, Persona, Prompt, UserInput, Intent, ToolGroup, LongTermMemory, CallerExtra),
        // iteration-dynamic: 3 个 Provider
        List.of(SessionMemory, VehicleState, Time));
}
```

`prepare()` 当前委托 `build()` 后适配为 ContextPrepareResult：
```java
public ContextPrepareResult prepare(RequestSession session, ContextCancelChecker cancelChecker) {
    ContextBuildResult buildResult = build(session);
    return ContextPrepareResult.success(buildResult.frame(), null, cancelChecker, List.of());
}
```

**发现 3.2.1：`prepare()` 第二个参数 `currentUserMessage` 传了 null**

计划要求 AgentLoop 在 iteration 0 前把 currentUserMessage 写入 ChatMemory。Phase 2 的 prepare() 通过 `ContextPrepareResult` 的 `currentUserMessage()` 返回 null。这个字段要到 Phase 5 AgentLoop 才真正消费，当前 null 不影响功能。但标注一下：后续 Phase 5 实现时此处需要填充真实的 currentUserMessage。

### 3.3 ToolRegistry 窄接口

**3.3.1 `toolSpecificationsByNames()` 严格解析 ✅**

```java
public List<ToolSpecification> toolSpecificationsByNames(List<String> names) {
    for (String name : names) {
        ToolDispatcher d = dispatchers.get(name);
        if (d == null) throw new ToolSpecNotFoundException(name);
        // ...
    }
}
```
任一名称缺失直接抛异常，不回退全量。✅

但这里有一个**潜在性能问题**：`toolSpecificationsByNames` 内部遍历 `allSpecs` 列表来匹配每个 dispatcher 找到的 name，这里用 `dispatchers.get(name)` 做 O(1) 的 name 查找，然后对每个找到的 name 遍历全量 `allSpecs` 做 O(n) 的规格匹配。如果 `selectedToolNames` 有 10 个且有 47 个 tools，就是 10×47=470 次循环。不是性能瓶颈，但可以用 Map 加速。

**3.3.2 `enabledToolSpecifications()` 返回全量 ✅**

```java
public List<ToolSpecification> enabledToolSpecifications() {
    return new ArrayList<>(allSpecs);
}
```
简单直接。✅

### 3.4 MemorySnapshot sessionId 修正

**之前的构造器：**
```java
// 旧（4-arg）：始终加 memory: 前缀
this.sessionId = SessionMemoryIds.shortTermMemoryId(sessionId);
```

**Phase 2 新增构造器：**
```java
// 新（5-arg）：rawSessionId=true 时保留原始 sessionId
this.sessionId = rawSessionId ? sessionId : SessionMemoryIds.shortTermMemoryId(sessionId);
```

`MemoryOrchestrator.sessionMemorySnapshot()` 使用 `new MemorySnapshot(sessionId, ..., true)` 保留原始 sessionId。旧 `getMemorySnapshot()` 继续使用旧构造器，不影响旧链路。✅

**发现 3.4.1：缺少 `MemorySnapshotSessionIdBoundaryTest`**

计划 Task 2.2 Step 5 明确要求新增此测试文件，验证只有 Store/Provider 边界产生 `memory:session-1` 前缀。当前未发现此文件。这是一个计划要求的测试缺口。

### 3.5 ContextFrame 新增 contributions

```java
// ContextFrame(..., List<ContextContribution> contributions) {
this.contributions = contributions != null
    ? Collections.unmodifiableList(new ArrayList<>(contributions))
    : List.of();
```

Builder 新增 `contributions(...)` 方法。旧字段通过旧 setter 设置。两套数据并存，但 Builder 不做交叉验证冲突，依赖测试检测。✅

---

## 四、缺失文件说明

### 4.1 `ContextPreparer.java` 和 `ContextAssemblyGateway.java`

计划 Section 2.2 将这两个列为独立接口文件。实际代码中 `ContextOrchestrator` 的 Javadoc 声称"实现 ContextPreparer 和 ContextAssemblyGateway"，但没有真正创建独立的接口文件。`prepare()` 和 `assemble()` 方法直接定义在 `ContextOrchestrator` 上。

**影响评估：** 低。功能完全正确。设计文档中也包含"推荐拆分为窄接口"的弹性表述。但 Phase 5 切换时，AgentRuntime 和 AgentLoop 需要窄接口来解耦。最晚在 Phase 5 之前需要创建这两个接口。

### 4.2 `MemorySnapshotSessionIdBoundaryTest.java`

计划明确要求新增此测试文件。当前缺失。

**影响评估：** 中。sessionId 前缀修复是重要的数据一致性保障，没有专门的边界测试，未来重构时难以保证前缀行为不被破坏。建议补上。

---

## 五、审查结论

**有条件通过。补充 `MemorySnapshotSessionIdBoundaryTest` 后即可进入 Phase 3。**

| 检查项 | 状态 |
|--------|------|
| PromptContextProvider 真实 System Prompt | ✅ |
| ToolGroupContextProvider 真实 ToolSpecification | ✅ |
| UserInputContextProvider CURRENT_USER Message | ✅ |
| MemoryContextProvider 拆分为长期/短期 | ✅ |
| Vehicle/Time Provider 去掉 mode 分支 | ✅ |
| CallerExtraContextProvider 新增 | ✅ |
| ToolRegistry 窄接口 | ✅ |
| MemoryOrchestrator 窄接口 | ✅ |
| MemorySnapshot rawSessionId 构造器 | ✅ |
| ContextOrchestrator prepare/assemble 拆分 | ✅ |
| ContextBuildInput 扩展 | ✅ |
| **MemorySnapshotSessionIdBoundaryTest** | ❌ **需补充** |
| **ContextPreparer/AssemblyGateway 接口** | ❌ **Phase 5 前需补充** |
| 全量单测 | ✅ BUILD SUCCESSFUL |
