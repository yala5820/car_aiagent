# Context 模块现状总结

**生成日期：** 2026-07-09
**依据：** `docs/plan/` 计划文档 + `docs/act_summary/` 总结 + 实际代码

---

## 一、一句话定位

Context 模块是 AIAgent 在 **RequestSession 和 AgentLoopOrchestrator 之间的上下文装配层**。每次 TEXT 请求进来，它把散落在各处的信息（用户身份、意图、工具选择、车辆状态、时间、记忆归属等）统一采集、打包成 ContextFrame，然后把其中需要给 LLM 看的部分通过 PreProcessor 注入到首轮模型消息中。

---

## 二、它在系统中的位置

```
AIDL 请求 → AIAgentService → AgentRuntime.startSession() → RequestSession
                                  → AgentRuntime.execute()
                                      → ContextOrchestrator.build() → ContextFrame
                                      → RuntimeCancelChecker（检查取消）
                                      → AgentExecutor.execute(session, contextFrame)
                                          → AgentLoopOrchestrator（调 LLM）
```

Context 模块的边界很清晰：
- **上游**：AgentRuntime.startSession() 产生 RequestSession（含 IntentResult、ToolGroupSelectionResult）
- **核心**：ContextOrchestrator.build(session) 产出 ContextFrame
- **下游**：ContextFrame 通过 AgentExecutor 默认方法合并到 orchestratorContext map
- **注入**：ContextExtraPreProcessor（PreProcessor 链首位）读取 context_rendered_extra，在首轮生成 UserMessage

---

## 三、模块内部结构

### 3.1 核心数据模型

| 类 | 作用 | 关键特征 |
|----|------|---------|
| `ContextMode` | 三种模式枚举 | OBSERVE_ONLY / HYBRID_EXTRA_CONTEXT / FULL_CONTEXT |
| `ContextSectionType` | 10 种 section 类型 | RUNTIME / PERSONA / USER_INPUT / INTENT / TOOL_GROUP / MEMORY / VEHICLE_STATE / TIME / PROMPT / DEBUG |
| `ContextSection` | 单段上下文片段 | 不可变，含 type / providerName / renderable / content / charCount / truncated / metadata |
| `ContextFrame` | 最终上下文快照 | 不可变，20+ 字段，来自 RequestSession + 各 Provider |
| `ContextFrameBuilder` | 构造 ContextFrame | fromSession(session) 是主要入口，不重新生成 ID |
| `ContextBuildResult` | build() 返回值 | 含 frame / success / fallbackUsed / errorReason |
| `ContextDebugInfo` | 构建诊断信息 | 含 providerNames / fallbackProviders / providerErrors / buildMs |
| `ContextBuildInput` | Provider 依赖容器 | 含 mode / toolGroupRegistry / budgetManager / 各 Provider 接口 |

### 3.2 Provider 体系

#### ContextProvider 接口

```java
public interface ContextProvider {
    String name();                    // Provider 名称
    ContextSectionType type();        // 产出 section 的类型
    ContextProviderResult provide(RequestSession session, ContextBuildInput input);
}
```

#### ContextProviderResult 三种结果状态

| 状态 | success() | fallback() | section 是否有效 |
|------|-----------|------------|-----------------|
| success | true | false | 有效 |
| fallback | true | true | 有效（降级数据） |
| failure | false | false | null |

Provider 发生异常时由 ContextOrchestrator 的 catch 块捕获，记录到 debugInfo，不阻断其他 Provider。

### 3.3 标准 9-Provider 链

**按执行顺序：**

| 序号 | Provider | 渲染到 LLM | 内容 |
|------|----------|-----------|------|
| 1 | RuntimeContextProvider | ✅ HYBRID | requestId / userId / sessionId / personaId / clientMessageId / inputType |
| 2 | PersonaContextProvider | ✅ HYBRID | requestedPersonaId / effectivePersonaId |
| 3 | UserInputContextProvider | ❌ | rawUserInput / normalizedUserInput / inputLength |
| 4 | IntentContextProvider | ✅ HYBRID | intentTag / confidence / matchedKeywords / debugReason |
| 5 | ToolGroupContextProvider | ✅ HYBRID | selectedGroupIds / selectedToolCount / selectedToolNames / groupDescriptions |
| 6 | MemoryContextProvider | ❌ | memoryOwner / memoryInjectedByContext / sessionId |
| 7 | VehicleStateContextProvider | ❌ | vehicleSnapshotAvailable + JSON 快照 |
| 8 | TimeContextProvider | ❌ | 格式化时间字符串 |
| 9 | PromptContextProvider | ❌ | promptOwner / promptTemplateName / promptInjectedByContext |

❌ = renderable=false，在 HYBRID 模式下不进入 renderedExtraContext（因为这些内容已有其他模块负责注入）。

### 3.4 渲染规则

```java
// renderExtraContext() 的核心逻辑
sections.stream()
    .filter(ContextSection::renderable)     // 只取 renderable=true
    .map(ContextSection::content)
    .filter(text -> !text.isEmpty())
    .collect(Collectors.joining("\n\n"));    // 用空行拼接
```

三种模式的行为：

| ContextMode | 是否构建 sections | renderedExtraContext | 备注 |
|-------------|-----------------|---------------------|------|
| OBSERVE_ONLY | ✅ | ""（空字符串） | 只构建和 Trace，不注入模型 |
| HYBRID_EXTRA_CONTEXT | ✅ | renderable=true 的 section | 默认模式，当前生产使用 |
| FULL_CONTEXT | ✅ | 同 HYBRID 行为 | 降级为 HYBRID，debugInfo 标记 full_context_deferred |

### 3.5 预算管理

`ContextBudgetManager` 提供粗粒度字符预算：

| 预算项 | 默认值 |
|--------|--------|
| 单 section 上限 | 800 字符 |
| memory 摘要上限 | 500 字符 |
| tool 上下文上限 | 1200 字符 |
| 总上限 | 3000 字符 |
| token 估算 | ~2 字符/token |

当前 `ContextOrchestrator` 在 build() 中调 `budgetManager.trim()` 做裁剪，并把 `tokenEstimate` 写入 ContextFrame 和 Trace。

### 3.6 Trace 观测

ContextOrchestrator 构建完成后，通过 `ContextTraceRecorder` 向 OpenTelemetry root span 写入 11 个属性：

```
agent.context.enabled            → true/false
agent.context.mode               → HYBRID_EXTRA_CONTEXT / OBSERVE_ONLY / FULL_CONTEXT
agent.context.provider_count     → 9
agent.context.providers          → "RuntimeContextProvider,PersonaContextProvider,..."
agent.context.selected_tool_count → 1
agent.context.selected_tool_names → "set_ac_status"
agent.context.section_count      → 9
agent.context.token_estimate     → 12
agent.context.fallback_used      → true/false
agent.context.build_ms           → 3
agent.context.error              → null 或错误信息
```

---

## 四、对外接口

### 4.1 被调用的接口

| 调用方 | 调什么 | 用途 |
|--------|--------|------|
| `AgentRuntime.execute()` | `contextOrchestrator.build(session)` | 构建 ContextFrame |
| `AgentExecutor` （默认方法） | `contextFrame.toOrchestratorContext(base)` | 合并到 Orchestrator context map |
| `ContextExtraPreProcessor` | 读取 `context_mode` + `context_rendered_extra` | 注入首轮消息 |
| `AgentRuntime` 构造函数 | `ContextOrchestrator` 对象 | 依赖注入 |

### 4.2 Context 模块依赖的外部模块

| 依赖 | 注入方式 | 用途 |
|------|---------|------|
| `ToolGroupRegistry` | ContextBuildInput | 查工具组描述 |
| `PromptManager` | ContextBuildInput | 记录 prompt 模板名（当前仅元信息） |
| `MemoryOrchestrator` | ContextBuildInput | 记录记忆归属（当前仅元信息）；同时作为 SessionIdResolver 注入 AgentRuntime |
| `VehicleStatusProvider` | ContextBuildInput | 获取车辆状态快照（来自 AIAgentService） |
| `TimeProvider` | ContextBuildInput | 获取当前时间（Context 包内独立接口） |
| `ContextBudgetManager` | ContextBuildInput | 预算管理（有默认值） |
| `ActiveRequestRegistry` | RuntimeCancelChecker 匿名实现 | 检查取消状态 |

### 4.3 ContextFrame 输出的标准 Map Key

被 `AgentLoopOrchestrator` 和 `ContextExtraPreProcessor` 消费的上下文 map key：

| Key | 来源 | 消费方 |
|-----|------|--------|
| `context_frame` | ContextFrame 对象本身 | 调试/扩展用 |
| `context_mode` | mode.name() | ContextExtraPreProcessor |
| `context_rendered_extra` | renderedExtraContext 文本 | ContextExtraPreProcessor → 注入 LLM |
| `caller_extra_context` | 调用方原 extra_context（保留用） | 兼容旧调用方 |
| `selected_tool_names` | selectedToolNames 列表 | PreProcessor 链 / Trace |
| `selected_group_ids` | selectedGroupIds 名称列表 | PreProcessor 链 / Trace |

注意：Context 模块**不覆盖** `extra_context` key，调用方原有的 extra_context 会被保留到 `caller_extra_context`。

---

## 五、Context 在当前系统中实际发挥的作用

### 5.1 真正在做的

**1. 向 LLM 注入轻量运行时说明**

每次 TEXT 请求的首轮模型消息中，会增加一段类似这样的文本：

```
【运行时上下文】
- requestId: req-xxx
- userId: driver-a
- sessionId: conv-123
- personaId: chat
- inputType: TEXT

【意图上下文】
- intentTag: VEHICLE_AC
- confidence: HIGH
- matchedKeywords: 空调
- debugReason: matched:VEHICLE_AC

【工具组上下文】
- selectedGroupIds: AC_GROUP,BASIC_STATUS_GROUP
- selectedToolCount: 1
- selectedToolNames:
  - set_ac_status
```

这帮助 LLM 理解"当前请求是谁发的、他想干什么、有哪些工具可用"。

**2. 统一上下文观测**

11 个 Context Trace 字段让运维人员可以回答：这次请求跑了哪些 Provider、选中了什么工具、构建花了多久、有没有降级。

**3. 在 Context 构建后、AgentLoop 前提供取消插入点**

RuntimeCancelChecker 让 Service 层可以在 Context 构建完成但 LLM 还没调用的间隙检查取消状态，避免已取消的请求继续调 LLM 浪费资源。

### 5.2 名义上在做但实际上效果有限的

**1. "统一上下文管理"**

实际上 Context 并没有"管理"memory、prompt、tool 等模块——它们还是各管各的。Context 只是从它们那读取了一份**只读快照**。MemoryOrchestrator 依然自己处理记忆读写和压缩，PromptManager 依然自己处理模板渲染。

**2. "预算管理"**

ContextBudgetManager 的字符截断从未实际触发过——当前 renderedExtraContext 只有不到 500 字符，远低于 3000 字符上限。它是一个"备而不用"的安全网。

**3. "FULL_CONTEXT 模式"**

枚举存在但降级为 HYBRID。memory / vehicle / time / prompt 这几个 Provider 目前只是观测量，不参与 LLM 注入。如果要真正切换到 FULL_CONTEXT，需要迁移现有的 PreProcessor 逻辑。

---

## 六、还未实现的功能

### 6.1 完整工具描述渲染

当前只把 selected tool names（如 `set_ac_status`）和 group 级描述传给 LLM，**不包含**每个工具的完整参数说明和 schema。

如果要实现，需要：
- 向 ContextBuildInput 注入 ToolRegistry 或 List<ToolSpecification>
- ToolGroupContextProvider 读取选中工具的 description + 参数 schema
- 在预算管理中区分"工具名"和"完整工具说明"的开销

### 6.2 动态工具绑定

当前 `AgentLoopOrchestrator` 在构造时就确定了模型可见的 `effectiveToolSpecs`，做不到"每轮请求根据 ContextFrame 动态调整模型可见工具"。

Context 已经知道本轮选中了哪些工具（`selectedToolNames`），但没有办法把这些信息转化为 LangChain4j 实际绑定的 `ToolSpecification` 列表。

### 6.3 FULL_CONTEXT 模式

当前 FULL_CONTEXT 降级为 HYBRID。如果启用，需要梳理和迁移：
- `MemoryPreProcessor` → Context 控制记忆注入粒度
- `VehicleStatusPreProcessor` → Context 控制车辆状态渲染
- `TimeContextPreProcessor` → Context 控制时间格式
- `injectSystemPrompt` → Context 控制 system prompt 生成

### 6.4 精确 token 预算

当前用字符数估算 token（2 字符 ≈ 1 token），不是精确的模型 tokenizer。中文、英文、JSON、工具 schema 的 token 密度不同，当前方案无法区分。

### 6.5 Provider 动态开关

当前 9 个 Provider 是固定链，不支持根据请求类型或模式动态增删。比如 IMAGE 请求不需要 ToolGroupProvider，但当前没有这种按需加载机制。

### 6.6 LLM 驱动的 context 压缩

当前明确禁止调用 LLM 做 context 压缩。MemoryCompressor 是独立的语义压缩机制，Context 模块没有自己的压缩。

---

## 七、遗留问题

### 7.1 Context 与 PreProcessor 的职责重叠

当前 `ContextExtraPreProcessor` 是第一道 PreProcessor，负责把 `context_rendered_extra` 注入首轮消息。但它后面还有 `MemoryPreProcessor`、`VehicleStatusPreProcessor`、`TimeContextPreProcessor`。

这些 PreProcessor 各自独立注入——Context 并不知道它们注入了什么，它们也不知道 Context 的存在。目前靠"Context 不重复注入"来避免冲突，但这不是架构上的解耦。

### 7.2 Trace 属性常量是死代码

`TraceAttributeKeys` 中新增了 11 个 `CONTEXT_*` 常量，但 `ContextTraceRecorder` 直接写的硬编码字符串 `"agent.context.*"`，没有引用这些常量。改命名时需要两处同步。

### 7.3 调试信息字段分级缺失

当前 ContextFrame 中部分字段（requestId、userId）可能同时进入模型可见区（renderedExtraContext）和 Trace。缺少分级机制来区分"仅 Trace 可见"和"也能给 LLM 看"。

### 7.4 Context 对 IMAGE / VOICE / CONTROL 链路无影响

当前 Context 只接入了 TEXT 链路。IMAGE、VOICE、CONTROL 请求完全不走 ContextOrchestrator，也没有 ContextFrame。如果要统一上下文管理，后续需要评估是否要扩展到这些入口。

### 7.5 Memory 的压缩机制与 Context 无关

MemoryCompressor 在 ChatMemory 超限时做语义压缩，是 Memory 模块的内部逻辑。Context 模块对其无控制能力，`MemoryContextProvider` 目前只记录"记忆归谁管"的元信息，不参与实际的记忆管理或压缩决策。

---

## 八、文件清单

```
app/src/main/java/com/hirain/aiagent/context/
├── ContextMode.java                  # 三种模式枚举
├── ContextSectionType.java           # 10 种 section 类型
├── ContextSection.java               # 不可变上下文片段
├── ContextFrame.java                 # 上下文快照 + toOrchestratorContext()
├── ContextFrameBuilder.java          # 构造器，fromSession() 主入口
├── ContextBuildInput.java            # 依赖容器 + Builder
├── ContextBuildResult.java           # build() 返回值
├── ContextBuildException.java        # 构建异常
├── ContextProvider.java              # Provider 接口
├── ContextProviderResult.java        # 三种结果状态
├── ContextBudgetManager.java         # 字符预算 + token 估算
├── ContextDebugInfo.java             # 诊断信息
├── ContextTraceRecorder.java         # 写 Trace
├── ContextOrchestrator.java          # 核心执行器
├── VehicleStatusProvider.java        # 独立车辆状态接口
└── provider/
    ├── RuntimeContextProvider.java
    ├── PersonaContextProvider.java
    ├── UserInputContextProvider.java
    ├── IntentContextProvider.java
    ├── ToolGroupContextProvider.java
    ├── MemoryContextProvider.java
    ├── VehicleStateContextProvider.java
    ├── TimeContextProvider.java
    └── PromptContextProvider.java

app/src/main/java/com/hirain/aiagent/core/preprocessor/
└── ContextExtraPreProcessor.java     # 注入 Context 到首轮消息

app/src/main/java/com/hirain/aiagent/runtime/
├── AgentExecutor.java                 # +execute(session, contextFrame) 默认方法
├── AgentRuntime.java                  # +ContextOrchestrator + RuntimeCancelChecker + SessionIdResolver
└── RuntimeCancelChecker.java          # 只读取消检查接口

app/src/main/java/com/hirain/aiagent/trace/
└── TraceAttributeKeys.java            # +11 个 CONTEXT_* 常量
```

测试文件（8 个）：

```
app/src/test/java/com/hirain/aiagent/context/
├── ContextFrameBuilderTest.java       # 3 tests
├── ContextBudgetManagerTest.java      # 2 tests
├── ContextOrchestratorTest.java       # 5 tests
├── ContextProviderFailureTest.java    # 2 tests
├── ContextTraceRecorderTest.java      # 1 test
├── TestRequestSessions.java           # 辅助类
└── provider/
    └── ToolGroupContextProviderTest.java # 1 test

app/src/test/java/com/hirain/aiagent/runtime/
└── AgentRuntimeContextTest.java       # 6 tests

app/src/test/java/com/hirain/aiagent/core/
├── ContextExtraPreProcessorTest.java  # 3 tests
└── AgentLoopOrchestratorContextInjectionTest.java # 1 test
```
