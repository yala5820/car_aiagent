# ToolGroup 引入阶段总结

## 工作目标

在 AgentRuntime 内引入轻量级 ToolGroup 选择层，基于 IntentResult 生成候选工具组列表。只记录和观测，不限制 LLM 可见工具，不改变现有 AgentLoop 行为。

## 修改内容

### Phase 1：ToolGroup 元数据与 Registry

`app/src/main/java/com/hirain/aiagent/toolgroup/` 下新增 4 个文件：

| 文件 | 说明 |
|------|------|
| `ToolGroupId.java` | 13 枚举值：CHAT_ONLY/BASIC_STATUS/AC/WINDOW/SEAT/DOOR/CHASSIS/FRAGRANCE/DMS/WEATHER/VISION/COMMON_VEHICLE/ALL_SAFE_DEMO |
| `ToolGroup.java` | 不可变元数据，含 groupId/groupName/description/toolNames/requiredContextKeys/riskLevel/enabled |
| `ToolGroupSelectionResult.java` | of() + fallback() 工厂，不可变 List 防护 |
| `ToolGroupRegistry.java` | defaultRegistry() 含全部 40+ 真实 toolName，COMMON_VEHICLE/ALL_SAFE_DEMO 动态合并 |

`app/src/test/java/com/hirain/aiagent/toolgroup/ToolGroupRegistryTest.java`：9 个测试（枚举覆盖 + 不可变性 + 查询 + toolName 分类 + COMMON_VEHICLE/ALL_SAFE_DEMO 合并）

### Phase 2：DefaultToolGroupSelector

`app/src/main/java/com/hirain/aiagent/toolgroup/` 下新增 2 个文件：

| 文件 | 说明 |
|------|------|
| `ToolGroupSelector.java` | `ToolGroupSelectionResult select(IntentResult, String userInput)` 接口 |
| `DefaultToolGroupSelector.java` | 基于 IntentTag 的 11 种映射 + UNKNOWN 弱关键词降级 |

`app/src/test/java/com/hirain/aiagent/toolgroup/DefaultToolGroupSelectorTest.java`：9 个测试用例覆盖全部 11 种 IntentTag 映射 + CHAT 弱关键词 fallback + KWR 端到端集成

### Phase 3：Runtime Session 集成

| 文件 | 变更 |
|------|------|
| `runtime/RequestSession.java` | 新增 `ToolGroupSelectionResult toolGroupSelectionResult` 字段 + getter |
| `runtime/RequestSessionFactory.java` | `create()` 签名新增第 5 参数，null 降级，不放入 orchestratorContext |
| `runtime/AgentRuntime.java` | 新增 `ToolGroupSelector` 成员 + 5-arg 构造器；startSession 中调用 selectToolGroupsSafely |

### Phase 4：Trace 与回归验证

| 文件 | 说明 |
|------|------|
| `runtime/AgentRuntime.java` | 新增 `writeToolGroupsToTrace()` + `selectToolGroupsSafely()` |
| `runtime/AgentRuntimeToolGroupTraceTest.java` | 真实 TraceSession 验证 5 个 agent.tool_group.* attribute（复用 `trace/TestTraceSupport.java` 公共测试工具） |

## 选择器映射规则

| IntentTag | 选中 ToolGroup |
|-----------|---------------|
| VEHICLE_AC | AC_GROUP + BASIC_STATUS_GROUP |
| VEHICLE_WINDOW | WINDOW_GROUP + BASIC_STATUS_GROUP |
| VEHICLE_SEAT | SEAT_GROUP + BASIC_STATUS_GROUP |
| VEHICLE_DOOR | DOOR_GROUP + BASIC_STATUS_GROUP |
| VEHICLE_CHASSIS | CHASSIS_GROUP + BASIC_STATUS_GROUP |
| VEHICLE_FRAGRANCE | FRAGRANCE_GROUP + BASIC_STATUS_GROUP |
| VEHICLE_DMS | DMS_GROUP + BASIC_STATUS_GROUP |
| WEATHER | WEATHER_GROUP |
| VISION_QA | VISION_GROUP |
| CHAT | CHAT_ONLY_GROUP |
| UNKNOWN + 弱车载关键词 | COMMON_VEHICLE_GROUP + BASIC_STATUS_GROUP |
| UNKNOWN + 无关键词 | CHAT_ONLY_GROUP |

## 验证结果

### 单测结果（全部 BUILD SUCCESSFUL）

| 测试范围 | 结果 |
|---------|------|
| toolgroup 模块（18 测试：ToolGroupRegistryTest 9 + DefaultToolGroupSelectorTest 9） | ✅ |
| runtime 模块（21 测试） | ✅ |
| intentrouter 模块（9 测试） | ✅ |
| trace wiring | ✅ |
| 全量 JVM 单测（--rerun-tasks） | ✅ |

### 静态验收

- `AgentLoopOrchestrator.java`：无 diff ✅
- `ToolRegistry.java`：无 diff ✅
- `ToolDispatcher.java`：无 diff ✅
- `VehicleStateMachine.java`：无 diff ✅
- `AIAgentService.kt`：本轮未修改 ✅

## 未迁移范围

- 不根据 ToolGroup 限制 LLM 可见工具（留到 Phase 4 Context）
- 不修改 AgentLoopOrchestrator 工具调用逻辑
- 不修改 ToolRegistry/ToolDispatcher/VehicleStateMachine
- VOICE/IMAGE/CONTROL 不迁移
- riskLevel 仅为字符串元信息，不做安全裁决

## 后续建议

1. **Phase 4 Context**：根据 ToolGroup 限制 LangChain4j 实际绑定给 LLM 的 tool specification
2. **PolicyEngine**：根据 selectionReason/riskLevel 设置超时或安全策略
3. **Eval**：建立 intent → selectedToolGroups 回归集
4. **工具名维护**：ToolGroup 的 toolName 与 `@Tool(name=...)` 是人工维护关系，新增或改名工具时需同步更新 `ToolGroupRegistry`
