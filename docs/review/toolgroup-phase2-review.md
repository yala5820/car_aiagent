# ToolGroup Phase 2-4 验收审查报告

**审查日期：** 2026-07-06  
**审查范围：** ToolGroup Phase 1 质量修正 + Phase 2-4 全部交付  
**审查人：** Claude Code  

---

## 一、总体评估

**实现质量良好。** 代码严格遵循计划文档，DefaultToolGroupSelector 11 种 IntentTag 映射完整，Runtime 集成正确（startSession 顺序、异常降级、Trace 写入），静态检查确认 5 个受限文件无任何 diff。所有 44 个单元测试通过。

**发现 1 个中危问题和 2 个低危问题，无致命阻塞。**

---

## 二、中危问题

### 2.1 AgentRuntime.java 孤立的 Javadoc 注释块

**位置：** `runtime/AgentRuntime.java:148-150`

第 148-150 行有一个悬空的 Javadoc 注释，不附着于任何方法：

```java
    /**
     * 将 IntentResult 写入 Trace root span attribute，便于调试观测。
     */
    /**
     * 安全调用 ToolGroupSelector，捕获所有异常降级为 fallback。
     */
    private ToolGroupSelectionResult selectToolGroupsSafely(...) {
```

`writeIntentToTrace` 方法原本在此位置，后移动到第 183 行，但原 Javadoc 遗留未删。实际附着于 `selectToolGroupsSafely` 的是第二个注释块。

**修复方式：** 删除第 148-150 行的孤立 Javadoc（其正确位置的方法 `writeIntentToTrace` 在第 183 行无 Javadoc，可选择移过去）。

---

## 三、低危问题

### 3.1 总结文档的测试数量不准确

**位置：** `docs/act_summary/toolgroup-introduction-summary.md` 验证结果表

| 说法 | 实际 |
|------|------|
| toolgroup 模块（8 测试） | **14** 测试（ToolGroupRegistryTest 9 + DefaultToolGroupSelectorTest 5） |
| runtime 模块（11 测试） | **21** 测试（AgentRuntimeTest 8 + TraceTest 1 + RFTest 4 + RMapperTest 4 + RResultTest 4） |

建议更新为准确数字，避免后续引用时误导。

### 3.2 TestTraceSupport 提取未在总结中体现

计划中 Phase 4 的 Trace 测试原本是内嵌 `CapturingExporter` 的方式。实现时将 CapturingExporter + TraceSession 构建逻辑提取为 `trace/TestTraceSupport.java`，作为公共测试工具供 `AIAgentServiceTraceWiringTest` 和 `AgentRuntimeToolGroupTraceTest` 共用。这是一个比计划更优的设计决策，但总结文档未提及此文件的存在。

---

## 四、已核实无误

| 检查项 | 状态 |
|--------|------|
| DefaultToolGroupSelector 11 种 IntentTag 映射全部实现 | ✅ |
| UNKNOWN + 弱关键词 → COMMON_VEHICLE + BASIC_STATUS | ✅ |
| UNKNOWN + 无关键词 → CHAT_ONLY_GROUP | ✅ |
| AgentRuntime 4 个构造函数链式委托正确 | ✅ |
| startSession 执行顺序：IntentRouter → ToolGroupSelector → writeTrace → createSession | ✅ |
| selectToolGroupsSafely 捕获异常降级为 fallback | ✅ |
| writeToolGroupsToTrace 5 个 agent.tool_group.* attribute | ✅ |
| RequestSession 新增 toolGroupSelectionResult 字段 | ✅ |
| RequestSessionFactory 新增第 5 参数 + null 降级 | ✅ |
| toolGroupSelectionResult 不进入 orchestratorContext | ✅ |
| AgentLoopOrchestrator.java 无 diff | ✅ |
| ToolRegistry.java 无 diff | ✅ |
| ToolDispatcher.java 无 diff | ✅ |
| VehicleStateMachine.java 无 diff | ✅ |
| AIAgentService.kt 无 diff | ✅ |
| 44 个单测全部 BUILD SUCCESSFUL | ✅ |
| 编译 BUILD SUCCESSFUL | ✅ |

---

## 五、审查结论

**通过验收。** 建议在进入 Phase 3 前修复 2.1 的孤立 Javadoc（1 分钟即可完成），并更正总结文档中的测试计数。
