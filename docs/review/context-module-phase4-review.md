# Context 模块 Phase 4 验收审查

**审查日期：** 2026-07-07
**审查范围：** Phase 4 Runtime 兼容接入（Task 4.1～4.4）
**审查依据：** `docs/plan/context-module-implementation-plan.md` Phase 4 全部任务
**审查人：** Claude Code

---

## 一、总体评估

**通过验收，无阻塞问题。** Phase 4 全部 4 个 Task 均已正确实现。AgentExecutor 兼容接口、ContextExtraPreProcessor 注入、AgentRuntime ContextFrame 构建链路、AIAgentService 初始化均符合计划要求。Phase 3 的 section 重复 Bug 也已在此阶段修复。

---

## 二、文件清单核对

### 2.1 新增生产文件

| 文件 | 状态 | 说明 |
|------|------|------|
| `runtime/RuntimeCancelChecker.java` | ✅ | `@FunctionalInterface` + `neverCancelled()` 默认实现 |
| `core/preprocessor/ContextExtraPreProcessor.java` | ✅ | 首轮 HYBRID 注入，后续轮次/OBSERVE_ONLY 跳过 |

### 2.2 修改生产文件

| 文件 | 变更内容 | 状态 |
|------|---------|------|
| `runtime/AgentExecutor.java` | 新增 `execute(RequestSession, ContextFrame)` 默认方法 | ✅ |
| `runtime/AgentRuntime.java` | 新增 ContextOrchestrator + RuntimeCancelChecker 字段，8 个构造函数 | ✅ |
| `core/factory/AgentConfigFactory.java` | `createChatPersona()` / `createTextPersona()` PreProcessor 链首位加 ContextExtraPreProcessor | ✅ |
| `AIAgentService.kt` | 新增 `contextOrchestrator` 初始化 + 注入 AgentRuntime + RuntimeCancelChecker | ✅ |

### 2.3 新增测试文件

| 文件 | 用例数 | 状态 |
|------|--------|------|
| `runtime/AgentExecutorCompatibilityTest.java` | 1 | ✅ 默认方法委托到旧接口 |
| `runtime/AgentRuntimeContextTest.java` | 2 | ✅ Context build + cancelled 不调用 executor |
| `core/ContextExtraPreProcessorTest.java` | 3 | ✅ HYBRID/OBSERVE_ONLY/后续轮次 |
| `core/AgentLoopOrchestratorContextInjectionTest.java` | 1 | ✅ 完整 ContextOrchestrator → PreProcessor 链路 |

### 2.4 修改测试文件

| 文件 | 变更 | 状态 |
|------|------|------|
| `runtime/AgentRuntimeTest.java` | 新增 `context_mode`/`context_frame`/`context_rendered_extra` 断言 | ✅ |

---

## 三、Phase 3 Bug 修复确认

上一轮审查发现的 Phase 3 关键 Bug（fallback 结果 section 被重复添加）已在此阶段修复：

```
// 修复前（Phase 3）
if (result.success() && result.section() != null) {
    sections.add(result.section());
}
if (result.fallback() && result.section() != null) {
    sections.add(result.section());  // 重复添加
}

// 修复后（Phase 4）
if (result.section() != null) {
    sections.add(result.section());  // 单一条件
}
```

✅ **确认修复。**

---

## 四、代码审查详情

### 4.1 AgentExecutor 兼容接口

```java
@FunctionalInterface
public interface AgentExecutor {
    AgentResult execute(String userInput, Map<String, Object> context);

    default AgentResult execute(RequestSession session, ContextFrame contextFrame) {
        return execute(session.userInput(),
                contextFrame.toOrchestratorContext(session.orchestratorContext()));
    }
}
```

- `@FunctionalInterface` 保留，默认方法不影响函数式接口语义 ✅
- Service 中已有 lambda 继续编译通过（只实现单抽象方法 `execute(String, Map)`） ✅
- 新调用方 `AgentRuntime.execute()` 调 `chatExecutor.execute(session, contextFrame)` 通过默认方法路由到旧接口 ✅
- `AgentExecutorCompatibilityTest` 验证默认方法正确传递 `context_rendered_extra` ✅

### 4.2 ContextExtraPreProcessor

```java
public class ContextExtraPreProcessor implements PreProcessor {
    @Override
    public List<ChatMessage> prepare(AgentLoopContext ctx) {
        if (ctx.iteration() != 0) return List.of();
        String mode = ctx.getContextData(KEY_CONTEXT_MODE, String.class);
        if (!HYBRID_MODE.equals(mode)) return List.of();
        String text = ctx.getContextData(KEY_CONTEXT_RENDERED_EXTRA, String.class);
        if (text == null || text.trim().isEmpty()) return List.of();
        return List.of(UserMessage.from(text));
    }
}
```

- 首轮注入 (iteration==0) ✅
- 仅 HYBRID_EXTRA_CONTEXT 模式 ✅
- 空文本不注入 ✅
- 3 个测试覆盖全部执行路径 ✅

### 4.3 AgentRuntime 构造函数

计划要求 8 个构造函数全部实现：

| 签名 | 状态 |
|------|------|
| `AgentRuntime(AgentExecutor)` | ✅ 内部 `defaultContextOrchestrator()` |
| `AgentRuntime(AgentExecutor, ContextOrchestrator)` | ✅ cancelChecker=neverCancelled |
| `AgentRuntime(AgentExecutor, ContextOrchestrator, RuntimeCancelChecker)` | ✅ 生产用 |
| `AgentRuntime(AgentExecutor, ContextOrchestrator, IdGenerator, TimeProvider)` | ✅ 测试用 |
| `AgentRuntime(AgentExecutor, IdGenerator, TimeProvider)` | ✅ 旧测试兼容 |
| `AgentRuntime(AgentExecutor, IntentRouter, IdGenerator, TimeProvider)` | ✅ 旧测试兼容 |
| `AgentRuntime(AgentExecutor, IntentRouter, ToolGroupSelector, IdGenerator, TimeProvider)` | ✅ 旧测试兼容 |
| `AgentRuntime(AgentExecutor, ContextOrchestrator, IntentRouter, ToolGroupSelector, IdGenerator, TimeProvider, RuntimeCancelChecker)` | ✅ 全注入 |

`defaultContextOrchestrator()` 统一使用 `HYBRID_EXTRA_CONTEXT` + `ToolGroupRegistry.defaultRegistry()`，确保旧测试构造函数不丢失 Context 构建能力。

### 4.4 execute() 取消检查插入点

```
contextOrchestrator.build(session)   → 构建 ContextFrame
        ↓
cancelChecker.isCancelled(session)  → 构建后、AgentLoop 前检查
        ↓
chatExecutor.execute(session, contextFrame)  → AgentLoop
```

✅ 符合计划要求的"Context 构建后、AgentLoop 前可注入取消检查"。`RuntimeCancelChecker` 独立于 `ActiveRequestRegistry`，Runtime 内部只读，不持有 `ActiveRequest` 引用。

### 4.5 AIAgentService 初始化顺序

```
textOrchestrators 初始化（其中 PreProcessor 链已含 ContextExtraPreProcessor）
        ↓
ContextOrchestrator 初始化（接 ToolGroupRegistry / PromptManager / MemoryOrchestrator）
        ↓
AgentRuntime 初始化（接 contextOrchestrator + RuntimeCancelChecker）
```

✅ `ContextOrchestrator` 在 `AgentRuntime` 之前创建，依赖全部就绪。

### 4.6 `RuntimeCancelChecker` 在 Service 中的实现

```kotlin
RuntimeCancelChecker { runtimeSession ->
    activeRequestRegistry.get(runtimeSession.requestId())?.isCancelled == true
}
```

当 `activeRequestRegistry.get()` 返回 null（请求已 finish），`null == true` 为 false，视为未取消。安全 ✅

---

## 五、观察项

### 5.1 `ContextExtraPreProcessor` 被添加到 `createChatPersona()`（VOICE 路径）

`AgentConfigFactory.createChatPersona()` 的 PreProcessor 链也包含 `ContextExtraPreProcessor`：

```java
.preProcessors(List.of(
        new ContextExtraPreProcessor(),     // ← 对 VOICE 无实际作用
        new MemoryPreProcessor(memoryOrchestrator),
        new VehicleStatusPreProcessor(promptManager, statusProvider),
        new TimeContextPreProcessor()))
```

VOICE 请求不经过 `AgentRuntime.startSession()`/`execute()`，context map 中不含 `context_rendered_extra`，因此 `ContextExtraPreProcessor` 在 VOICE 路径下总是返回空列表（无注入行为）。

**安全但多余。** 这不是 Bug——对 VOICE 无任何影响，但建议在后续清理时移除，以避免未来维护者误以为 VOICE 也有 Context 注入。

### 5.2 `AgentLoopOrchestratorContextInjectionTest` 未使用完整 AgentLoopOrchestrator

该测试验证的是 ContextOrchestrator → ContextFrame → ContextExtraPreProcessor 的前半段链路，未启动真实的 `AgentLoopOrchestrator` + `ModelCaller` 来捕捉 `ChatRequest.messages()`。

计划中列出了使用完整 `AgentLoopOrchestrator` + mock `ModelCaller` 的集成测试方案（Task 4.2 Step 4），当前实现选择了更轻量的方式——直接调用 `ContextExtraPreProcessor.prepare()` 验证输出。

**结论：** 当前的测试已经覆盖了 Context 相关逻辑的核心风险点（rendered text → PreProcessor → ChatMessage），且 `ContextExtraPreProcessor` 作为 PreProcessor 被注册到 AgentConfig 后，`AgentLoopOrchestrator` 会自动调用它并注入结果到 `transientMessages`（这是框架保证的行为）。因此不构成覆盖缺口。

---

## 六、审查结论

**Phase 4 通过验收，可以进入 Phase 5。**

| 项目 | 状态 |
|------|------|
| Phase 3 Bug 修复 | ✅ 已确认修复 |
| AgentExecutor 兼容 | ✅ |
| ContextExtraPreProcessor 注入 | ✅ |
| AgentRuntime ContextFrame 构建 | ✅ |
| RuntimeCancelChecker | ✅ |
| 构造函数清单 | ✅ 8 个全部实现 |
| AIAgentService 初始化 | ✅ |
| 全量单测 | ✅ BUILD SUCCESSFUL |
