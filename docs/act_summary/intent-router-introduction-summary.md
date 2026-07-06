# IntentRouter 引入阶段总结

## 工作目标

在 AgentRuntime 内引入轻量级 IntentRouter，为进入 Runtime 的文本请求生成粗粒度 `IntentResult`，用于可观测性和后续策略准备。不改变 AgentLoop 执行路径，不引入 LLM/NLP 模型。

## 修改内容

### Task 1-2：Intent 基础类型与 KeywordIntentRouter

`app/src/main/java/com/hirain/aiagent/intentrouter/` 下新增 5 个文件：

| 文件 | 说明 |
|------|------|
| `IntentTag.java` | 11 枚举值：CHAT、VEHICLE_AC/WINDOW/SEAT/DOOR/CHASSIS/FRAGRANCE/DMS、VISION_QA、WEATHER、UNKNOWN |
| `IntentConfidence.java` | 4 级：HIGH/MEDIUM/LOW/NONE |
| `IntentResult.java` | 不可变结果，`of()` + `unknown()` 工厂方法，matchedKeywords 使用 unmodifiableList 防护 |
| `IntentRouter.java` | `@FunctionalInterface`：`IntentResult route(String text, String sourceInputType)` |
| `KeywordIntentRouter.java` | LinkedHashMap 关键词表 + 正则规则，稳定优先级打破平局，5 种 debugReason。P2：DMS 关键词改为小写 `"dms"`；P3：matchedKeywords 只记录 winner 命中词 |

`app/src/test/java/com/hirain/aiagent/intentrouter/` 下新增 1 个测试文件，共 **9 个测试用例**。

### Task 3：RequestSession 写入 IntentResult

`app/src/main/java/com/hirain/aiagent/runtime/RequestSession.java`：
- 新增 `IntentResult intentResult` 字段 + `intentResult()` getter

`app/src/main/java/com/hirain/aiagent/runtime/RequestSessionFactory.java`：
- `create()` 签名新增 `IntentResult` 参数，null 时自动降级为 `IntentResult.unknown(..., "missing_intent_result")`

`app/src/test/java/com/hirain/aiagent/runtime/RequestSessionFactoryTest.java`：
- 新增 `create_storesIntentResult` 测试
- 更新 2 个旧测试调用点改为 4 参

### Task 4：AgentRuntime 接入 IntentRouter

`app/src/main/java/com/hirain/aiagent/runtime/AgentRuntime.java`：
- 新增 `IntentRouter` 成员，新增 4-arg 全可注入构造器
- `startSession()` 中调用 `routeIntentSafely()` + `writeIntentToTrace()`
- `routeIntentSafely()` 捕获 router 异常降级为 `UNKNOWN/NONE`
- `writeIntentToTrace()` 将 tag/confidence/matched_keywords/source_input_type/debug_reason 写入 trace attribute

`app/src/test/java/com/hirain/aiagent/runtime/AgentRuntimeTest.java`：
- 新增 `startSession_routesIntentAndExecuteStillUsesOriginalExecutor`（验证 router 不影响 executor）
- 新增 `startSession_routerExceptionFallsBackToUnknownAndExecuteContinues`（验证异常容错）

## 验证结果

### 单测结果（全部 BUILD SUCCESSFUL）

| 测试范围 | 命令 | 结果 |
|---------|------|------|
| IntentRouter 模块 | `.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.intentrouter.*"` | ✅ 9 测试通过 |
| Runtime 模块 | `.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.runtime.*"` | ✅ 8 测试通过 |
| Trace 布线 | `.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.trace.AIAgentServiceTraceWiringTest"` | ✅ |
| 全量 JVM 单测 | `.\gradlew.bat testDebugUnitTest` | ✅ |

### 静态验收

- `git diff AIAgentService.kt`：仅有上一阶段（AgentRuntime 引入）的未提交改动，本轮 IntentRouter 未修改 ✅
- `AIAgentService.kt` 中的 `handleVoiceRequest/handleImageRequest/handleControlRequest` 未迁移 ✅

## 架构变更

```
变更前:
AgentRuntime.startSession(request, traceContext)
  → sessionFactory.create(request, persona, traceContext, null)
  → RequestSession (无 intentResult)

变更后:
AgentRuntime.startSession(request, traceContext)
  → intentRouter.route(text, inputType)     // KWR 关键词匹配
  → writeIntentToTrace(traceContext, intentResult)  // 写 5 个 trace attribute
  → sessionFactory.create(request, persona, traceContext, intentResult)
  → RequestSession (含 intentResult)
```

## 未迁移范围

- **VOICE**：本阶段不迁移，不修改 AIAgentService.handleVoiceRequest
- **IMAGE**：不进入 IntentRouter
- **CONTROL**：不进入 IntentRouter
- **ToolGroup / PolicyEngine / ContextOrchestrator / Eval**：本阶段不接入

## 本阶段遵守的约束

- 不根据 IntentResult 分流到不同 executor
- 不根据 IntentResult 直接调用 tool
- 不改变 AgentLoopOrchestrator 执行路径
- 不改变 PromptManager / MemoryOrchestrator / ToolRegistry / ToolDispatcher / VehicleStateMachine
- 不限制 LLM 可见工具范围
- 不修改 AIAgentService.kt
- IntentResult 不写入 orchestratorContext

## 后续建议

1. **PolicyEngine**：根据 intent 设置 timeout 或安全策略
2. **ToolGroup**：根据 intent 限制工具可见范围
3. **ContextOrchestrator**：根据 intent 调整 context assembly
4. **Eval**：建立 intent 标签回归集
