# ToolGroup 模块改进 任务总结

**任务日期：** 2026-07-10
**任务范围：** 计划文档 `2026-07-10-toolgroup-interface-improvement-plan.md` 四阶段全部完成
**提交人：** Claude Code

---

## 一、本轮改了什么（无术语）

ToolGroup 模块是 AI 助手中负责"根据用户说了什么，决定这轮需要哪些车控工具"的组件。本轮改进没有改变它选择工具的核心规则，而是做了三件基础工作：

1. **把接口修整齐了。** 之前选择工具的入口是一堆零散参数（意图结果 + 用户输入），现在统一打包成一个叫 `ToolGroupSelectionInput` 的对象，里面还预留了用户 ID、Persona ID 等字段，将来换成智能选择器（比如小 LLM）时不用再改接口。

2. **选不出来的情况有了明确兜底。** 之前如果意图识别器判断不出用户想干什么，就返回"纯聊天"模式（0 个工具）。现在改为返回"全量 47 个工具都可用"的兜底结果，同时打上 `allToolsFallback=true` 标记，这样下游的 Context 模块就知道"本轮是兜底，不是精确选择"。

3. **补了一套校验工具。** ToolGroupRegistry 里的 47 个工具名是手写的。现在加了一个校验接口，可以跟 LangChain4j 框架实际扫描出来的工具列表做双向对比，看看有没有漏掉或多写的工具名。

---

## 二、模块现状：改进后实现了什么

### 核心定位

ToolGroup 模块位于 IntentRouter（意图识别）和 Context（上下文管理）之间。它的输入是意图标签（如 VEHICLE_AC = 用户想调空调），输出是一份"候选工具清单"（如 15 个空调相关工具 + 2 个上下文 key）。

**关键约束：ToolGroup 选出的是"候选建议"，不是"真实限制"。** LLM 实际能调用的 47 个工具仍然由 AgentLoopOrchestrator 在启动时一次性绑定，ToolGroup 的挑选结果只作为文本提示注入到 LLM 的上下文消息中。

### 数据流向

```
用户说"打开空调"
  → IntentRouter → IntentTag.VEHICLE_AC
    → DefaultToolGroupSelector → ToolGroupSelectionResult（15 个 AC 工具名 + requiredContextKeys + risk=MEDIUM）
      → AgentRuntime → RequestSession
        → ContextOrchestrator → ToolGroupContextProvider 渲染文本
          → 注入 LLM 首轮消息
```

### 13 个工具组 + 兜底策略

| 场景 | 选中组 | 工具数 |
|------|--------|--------|
| VEHICLE_AC（空调） | AC_GROUP + BASIC_STATUS_GROUP | 15 |
| VEHICLE_SEAT（座椅） | SEAT_GROUP + BASIC_STATUS_GROUP | 11 |
| CHAT + 无车载关键词 | CHAT_ONLY_GROUP | 0 |
| CHAT + 说了"车"/"空调"等 | COMMON_VEHICLE_GROUP + BASIC_STATUS_GROUP | 45 |
| UNKNOWN + 无法判断 | **ALL_SAFE_DEMO_GROUP**（全量兜底） | 47 |
| IntentRouter 挂了 / Selector 抛异常 | ALL_SAFE_DEMO_GROUP（Runtime 层兜底） | 47 |

### SelectionResult 现在携带的元信息

每次选择结果 `ToolGroupSelectionResult` 不再只是"哪些组被选中"，还带上了：

- `selectedGroupIds` / `selectedToolNames`：候选组和工具名
- `requiredContextKeys`：本轮需要哪些上下文（如 `user_id`、`vehicle_status`）
- `highestRiskLevel`：最高风险等级（LOW/MEDIUM/HIGH）
- `allToolsFallback`：是否为全量兜底（Context 模块据此决定用轻量摘要还是展开渲染）
- `containsAggregationGroup`：是否包含聚合组

### 全量兜底时 Context 轻量渲染

当 `allToolsFallback=true` 时，`ToolGroupContextProvider` 不再逐个列出 47 个工具名，而是输出一段摘要：

```
【工具组上下文】
- allToolsFallback: true
- selectedToolCount: 47
- summary: 选择器无法确定明确工具组，本轮仅记录全量候选工具
```

避免了 47 个工具名全量注入 LLM 上下文窗口导致 token 暴涨。

---

## 三、技术细节

### 3.1 文件变更统计

| 类型 | 文件 | 说明 |
|------|------|------|
| 新增 | `ToolGroupSelectionInput.java` | 选择输入对象（6 字段 + Builder + of() 工厂） |
| 新增 | `ToolGroupRegistryValidationResult.java` | LangChain4j 一致性校验结果 |
| 新增 | `ToolGroupSelectionInputTest.java` | 3 测试 |
| 修改 | `ToolGroupSelector.java` | 新增 `select(ToolGroupSelectionInput)` 默认方法 |
| 修改 | `DefaultToolGroupSelector.java` | UNKNOWN/null 兜底改为全量 ALL_SAFE_DEMO_GROUP；生产路径统一用 `enriched()`；Runtime 异常/null 使用 Registry 驱动全量兜底 |
| 修改 | `ToolGroupSelectionResult.java` | +4 字段（requiredContextKeys / highestRiskLevel / allToolsFallback / containsAggregationGroup），+full() / enriched() / allToolsFallback(registry) 工厂；allToolsFallback 改为 Registry 驱动；riskLevel 校验 |
| 修改 | `ToolGroupRegistry.java` | +6 查询方法（allToolNames / requiredContextKeysFor / highestRiskLevelFor / containsAggregationGroup / isContextMarkerGroup / isAggregationGroup），+validateAgainstToolSpecifications() |
| 修改 | `AgentRuntime.java` | +ToolGroupRegistry 字段（全参构造函数新增，共 9 参）；selectToolGroupsSafely() 统一全量兜底 |
| 修改 | `RequestSessionFactory.java` | 注释更新（Factory 为最后防线，生产全量兜底由 Runtime 保证） |
| 修改 | `ToolGroupContextProvider.java` | allToolsFallback 时轻量渲染 |
| 修改 | `DefaultToolGroupSelectorTest.java` | +3 测试 |
| 修改 | `ToolGroupRegistryTest.java` | +21 测试 |
| 修改 | `AgentRuntimeTest.java` | 更新异常兜底断言 |
| 修改 | `ToolGroupContextProviderTest.java` | +2 测试（轻量渲染 + 非兜底保留逐行渲染） |
| 修改 | `docs/overview/toolgroup-module-overview.md` | 全面更新 |

### 3.2 关键设计决策

**1. ToolGroupSelector SAM 保持二参签名**

新接口 `select(ToolGroupSelectionInput)` 作为默认方法，旧二参 `select(IntentResult, String)` 保持为抽象方法（SAM）。原因：现有所有 lambda `(intentResult, userInput) -> ...` 无需改动，编译器不作不兼容报错。

**2. AgentRuntime 不新增构造函数数量**

9 个构造函数经过参数扩充后仍保持 9 个。中间构造函数在创建 `DefaultToolGroupSelector` 时同时持有 `ToolGroupRegistry.defaultRegistry()` 引用，传给全参构造函数统一存储。

**3. RequestSessionFactory 不注入 ToolGroupRegistry**

Factory 层保持轻量，null 降级仍然用 `CHAT_ONLY_GROUP + 空 toolNames`。生产全量兜底由 `AgentRuntime.selectToolGroupsSafely()` 保证，Factory 只是最后防线。

**4. 全量兜底 reason 统一 `_all_tools` 后缀**

如 `fallback:unknown_all_tools`、`tool_group_selector_null_all_tools`。不新增 Trace 字段，通过 reason 命名约定在现有 Trace 中识别全量兜底。

### 3.3 测试覆盖

- `toolgroup.*`：52 个测试（DefaultToolGroupSelector 13 + Registry 36 + SelectionInput 3）
- `runtime.AgentRuntimeTest`：含 selector 异常/null 兜底断言
- `runtime.AgentRuntimeToolGroupTraceTest`：ToolGroup 选择写入 Trace
- `context.provider.ToolGroupContextProviderTest`：3 测试（含全量兜底轻量渲染 + 非兜底保留逐行渲染）
- 全量回归：BUILD SUCCESSFUL，零失败

---

## 四、遗留问题与风险

| 问题 | 严重程度 | 说明 |
|------|---------|------|
| **工具绑定不动态** | 高 | ToolGroup 选工具但不真绑定。LLM 的实际 tool_specifications 仍是 persona 级别固定 47 个。要真正限制 LLM 可见工具，需要 per-request 过滤 `effectiveToolSpecs`。 |
| **toolName 手写维护** | 中 | 新增 `@Tool` 方法后需手动同步到 ToolGroupRegistry。已有 `validateAgainstToolSpecifications()` 校验接口，但不强制 fail fast，需人工或 CI 触发。 |
| **全量兜底暴露 HIGH 风险工具** | 中 | `ALL_SAFE_DEMO_GROUP` 包含 DOOR_GROUP 和 CHASSIS_GROUP（risk=HIGH）。`highestRiskLevel()` 已标记为 HIGH，但执行层（ToolDispatcher）不根据风险做拦截。 |
| **弱关键词可能误触发全车控** | 低 | 用户说"适合开车出去"命中"车"→45 个车辆工具被选中。 |
| **BASIC_STATUS_GROUP 语义未被消费** | 低 | requiredContextKeys 标记了 `user_id` 和 `vehicle_status`，但下游没有根据这些 key 额外注入上下文。 |

---

## 五、下一阶段建议

1. **动态工具绑定**：在 `AgentLoopOrchestrator.execute()` 入口处，根据 `ContextFrame.selectedToolNames` 过滤 `effectiveToolSpecs`，实现 per-request 工具白名单。这是本轮改进预留的核心能力消费点。

2. **风险拦截**：在 ToolDispatcher 或 SafetyGuard 中消费 `ToolGroupSelectionResult.highestRiskLevel()`，对 HIGH 风险工具的执行做确认或拦截。

3. **小 LLM / 子 agent 选择器**：基于 `ToolGroupSelectionInput` 接口实现一个轻量选择器，替换当前的硬编码规则。
