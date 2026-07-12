# AIAgent TEXT Context 统一模型输入控制设计

> 日期：2026-07-11
> 状态：设计讨论已确认，待项目方复核
> 适用范围：TEXT 主 AgentLoop 及其每次 Tool Calling 迭代
> 不适用范围：scene、vision/VL、MemoryCompressor/MemoryExtractor 等内部辅助模型调用

## 1. 文档目的

本文档定义 AIAgent Context 模块下一阶段的目标架构：把 TEXT 请求的模型输入控制权从 `AgentLoopOrchestrator` 和 TEXT `PreProcessor` 链迁移到 `ContextOrchestrator + ContextMessageAssembler`。

改造完成后，任何进入 TEXT 主模型 `ChatRequest` 的 `ChatMessage` 和 `ToolSpecification` 都必须由 Context 模块统一选择、排序、预算和输出。AgentLoop 不再拥有 Prompt 拼接、临时上下文注入、历史消息排序或工具可见性决策。

Context 只接管模型输入策略和装配，不吸收 Memory、Prompt、Tool、Vehicle、Trace 等能力模块的底层实现。

## 2. 已确认的设计决策

| 决策项 | 结论 |
|---|---|
| 本轮接管范围 | 仅 TEXT 主 AgentLoop，覆盖每次 Tool Calling 迭代 |
| Context 与 AgentLoop 边界 | Context 拥有最终模型输入装配权；AgentLoop 拥有循环状态、模型调用、工具执行和 ChatMemory 写入权 |
| 工具边界 | Context 决定本轮可见 `ToolSpecification`；ToolRegistry 继续负责工具注册、规格来源和执行 |
| 压缩边界 | Context 判断是否需要压缩并给出目标预算；Memory 执行压缩算法和原子写回 |
| 可见性 | 内部运行元数据默认不进入 LLM，必须显式声明可见性与信任级别 |
| 迁移方式 | 先影子装配和差异验证，再切换为 Context 独占装配 |
| 最终运行模式 | 最终只保留完整 Context 链路；删除模式枚举、兼容分支和旧 TEXT 装配代码 |
| LangChain4j 使用方式 | 保留低层 `ChatModel + ChatRequest + ChatMessage + ToolSpecification + ChatMemory` 原语和自定义 AgentLoop |

## 3. 当前问题

### 3.1 模型输入控制权分散

当前 TEXT 模型输入由多条路径共同产生：

- `AgentLoopOrchestrator` 构造 SystemMessage。
- `AgentLoopOrchestrator` 向 ChatMemory 写入当前 UserMessage。
- `ContextExtraPreProcessor` 注入 Context 文本。
- `VehicleStatusPreProcessor` 注入车辆状态。
- `TimeContextPreProcessor` 注入时间。
- ChatMemory 提供历史 User/Ai/ToolResult 消息。
- `effectiveToolSpecs` 在 AgentLoop 构造期固定绑定工具规格。

Context 当前只生成 `renderedExtraContext`，再借助 PreProcessor 转成 UserMessage。它无法知道其他 PreProcessor 最终加入了什么，也无法控制完整 `ChatRequest` 的预算和顺序。

### 3.2 Provider 缺少真实业务语义

当前部分 Provider 只记录 owner 或元信息：

- Memory Provider 不提供真实 MemorySnapshot。
- Prompt Provider 不渲染真实 System Prompt。
- ToolGroup Provider 主要渲染工具名称文本，不控制实际 ToolSpecification。
- Vehicle、Time Provider 在 HYBRID 模式下不可渲染，真实注入仍依赖 PreProcessor。

### 3.3 Context 数据模型过于文本化

当前 `ContextSection` 以 `renderable + content + metadata` 为核心，不能准确表达：

- 数据是否允许进入模型。
- 数据是否可以进入 SystemMessage。
- 数据属于请求级还是迭代级。
- 数据是否必需。
- 数据如何参与预算和裁剪。
- 数据是否来源于不可信用户输入。

### 3.4 预算只覆盖局部字符串

当前预算只对 Context 渲染文本进行字符裁剪，没有覆盖：

- System Prompt。
- Session ChatMemory。
- 当前 UserMessage。
- ToolSpecification Schema。
- 模型输出预留空间。
- Tool Calling 消息原子关系。

### 3.5 Trace 缺少真实 Context 层级

当前 Context 只向 root span 写属性，没有 `context.prepare` 或 `context.assemble` span。现有 `prompt.assembly` 实际承担消息装配观测，与未来 Context 职责重叠。

同时，`TraceSession.startChildSpan()` 固定以 rootContext 为父上下文，导致 Prompt、LLM、Tool、Memory 等 span 实际处于同一层级，不能展示真实调用关系。

## 4. 目标与非目标

### 4.1 目标

1. TEXT 主模型每次调用前，Context 生成唯一的 `ContextAssemblyResult`。
2. `ContextAssemblyResult` 同时包含最终消息、工具规格、预算报告和诊断信息。
3. AgentLoop 不再生成、追加、重排或裁剪任何模型输入消息。
4. Prompt、Memory、Vehicle、Time、CallerExtra 等真实数据通过 Provider 进入 Context。
5. Context 每次迭代重新读取动态数据，工具执行后的车辆状态能在下一轮生效。
6. 完整输入预算包含消息、工具 Schema、输出预留和安全余量。
7. Context 可以触发 Memory 持久化压缩，但不实现压缩算法和存储事务。
8. Context Trace 能展示 prepare、assemble、budget、compression 与 AgentLoop 的真实父子关系。
9. 最终删除 Context 模式切换和旧 TEXT 消息装配链路。

### 4.2 非目标

1. 本轮不改造 scene、vision/VL 的消息链路。
2. 本轮不接管 MemoryCompressor、MemoryExtractor 等内部辅助 LLM 调用。
3. 本轮不把自定义 AgentLoop 迁移到 LangChain4j AiServices。
4. 本轮不重写 ToolRegistry、ToolDispatcher、PromptManager、MemoryCompressor 或车辆状态机。
5. 本轮不实现完整聊天历史查询系统；ChatMemory 继续表示模型记忆，而不是不可变历史。
6. 本轮不引入网络 Token 计算服务，也不要求第一版使用精确 tokenizer。

## 5. 架构原则

### 5.1 三层责任模型

```text
Memory / Prompt / Tool / Vehicle / Trace
        能力模块：拥有数据、算法、存储和执行
                       ↓
Context
        控制平面：拥有模型输入策略、可见性、顺序和预算
                       ↓
AgentLoop
        执行平面：拥有循环、模型调用、安全检查和工具执行
```

### 5.2 Context 不是新的巨型模块

Context 内部必须继续拆分职责：

- `ContextOrchestrator`：协调 prepare、assemble、预算重试和压缩触发。
- `ContextProvider`：从能力模块读取结构化数据。
- `ContextPolicy`：决定可见性、信任级别、优先级和必需性。
- `ContextMessageAssembler`：纯函数式装配消息和工具规格。
- `ContextBudgetManager`：计算预算压力并生成裁剪决策。
- `ContextTokenEstimator`：低成本、可缓存的 Token 估算。
- `ContextTraceRecorder`：记录 Context span、event 和属性。

`ContextMessageAssembler` 不得直接访问数据库、执行压缩、写 Trace、读取系统时间或调用车辆模块。

### 5.3 LangChain4j 边界

继续直接使用以下 LangChain4j 原语：

- `ChatModel` / `ChatRequest` / `ChatResponse`。
- `ChatMessage` / `SystemMessage` / `UserMessage` / `AiMessage`。
- `ToolSpecification` / `ToolExecutionRequest` / `ToolExecutionResultMessage`。
- `ChatMemory` / `MessageWindowChatMemory` / `ChatMemoryStore`。

AIAgent 负责 Context 策略、车载安全、取消、Trace、工具分组和循环控制。该设计属于 LangChain4j 官方支持的低层 Tool Calling 使用方式，不要求使用 AiServices。

禁止为上述 LangChain4j 原语再创建一套平行的消息或工具协议。Context 输出应直接使用 LangChain4j 类型。

## 6. 总体架构

```text
AIAgentService
    ↓
AgentRuntime.startSession()
    ├─ SessionIdResolver
    ├─ IntentRouter
    └─ ToolGroupSelector
    ↓
ContextOrchestrator.prepare(RequestSession)
    ├─ REQUEST_STATIC Providers
    ├─ ContextPolicy
    └─ ContextFrame
    ↓
AgentLoopOrchestrator.execute(ContextFrame)
    ├─ 将 Context 创建的 currentUserMessage 写入 session ChatMemory
    ├─ for each iteration
    │    ├─ ContextOrchestrator.assemble(ContextAssemblyRequest)
    │    │    ├─ ITERATION_DYNAMIC Providers
    │    │    ├─ ContextMessageAssembler
    │    │    ├─ ContextBudgetManager
    │    │    ├─ 必要时调用 MemoryOrchestrator 压缩
    │    │    └─ ContextAssemblyResult
    │    ├─ ChatRequest(messages, toolSpecifications)
    │    ├─ ChatModel.chat()
    │    ├─ SafetyGuard
    │    ├─ ToolExecutor
    │    └─ 写入 AiMessage / ToolExecutionResultMessage
    └─ MemoryExtractor / ResultCollector
```

Runtime 和 AgentLoop 应依赖 Context 的窄接口，避免直接访问 Provider、BudgetManager 或 Assembler 实现。推荐拆分为：

```java
public interface ContextPreparer {
    ContextPrepareResult prepare(RequestSession session);
}

public interface ContextAssemblyGateway {
    ContextAssemblyResult assemble(ContextAssemblyRequest request);
}
```

`ContextOrchestrator` 实现这两个接口。`AgentRuntime` 只依赖 `ContextPreparer`，`AgentLoopOrchestrator` 只依赖 `ContextAssemblyGateway`。

## 7. 核心数据契约

### 7.1 ContextContribution

Provider 不再只返回可渲染字符串，而是返回结构化 Contribution。

```java
public interface ContextContribution {
    ContextSectionType type();
    ContextVisibility visibility();
    ContextTrustLevel trustLevel();
    ContextPriority priority();
    ContextLifecycle lifecycle();
    boolean required();
    String providerName();
}
```

建议使用明确的 Contribution 子类型，而不是 `Object payload`：

- `SystemPromptContribution`。
- `UserInputContribution`。
- `ContextDataContribution`。
- `MemoryContribution`。
- `ToolContribution`。
- `TraceContribution`。

### 7.2 可见性

```text
MODEL_VISIBLE    允许参与消息或工具规格装配
POLICY_ONLY      仅供 ContextPolicy 决策
TRACE_ONLY       仅供诊断和 Trace
```

### 7.3 信任级别

```text
TRUSTED_SYSTEM   仅项目控制的 Prompt 和系统规则
TRUSTED_DATA     车辆状态、系统时间等可信数据
UNTRUSTED_DATA   用户长期记忆、caller extra、外部数据
```

`UNTRUSTED_DATA` 不得进入 SystemMessage。用户长期记忆虽然经过 Memory 提取和持久化，但其语义来源仍是用户输入，只能作为参考数据进入 Context Data Message。

### 7.4 生命周期

```text
REQUEST_STATIC      每个 TEXT 请求构建一次
ITERATION_DYNAMIC   每次 AgentLoop 迭代重新获取
```

### 7.5 优先级

```text
CRITICAL   不得删除或截断
HIGH       优先保留
NORMAL     可按完整条目缩减
OPTIONAL   预算超限时优先移除
```

优先级由 ContextPolicy 结合当前 Intent、ToolGroup 和 requiredContextKeys 动态确定，不能全部硬编码在 Provider 中。

### 7.6 ContextFrame 单一事实来源

当前 ContextFrame 同时保存独立字段和 sections，存在两份数据不一致的风险。最终设计只能保留一个规范数据源。

推荐 ContextFrame 保存：

- RequestSession 身份引用。
- currentUserMessage。
- 不可变 REQUEST_STATIC Contribution 列表。
- 已解析候选工具规格。
- ContextPolicy 快照。
- prepare 诊断信息。

便利读取器必须从规范 Contribution 派生，不能再存储重复副本。

### 7.7 ContextAssemblyRequest

```java
public final class ContextAssemblyRequest {
    private final ContextFrame frame;
    private final int iteration;
    private final TraceContext traceContext;
}
```

动态 Provider 使用 frame 中的 sessionId/userId 和注入的能力接口读取最新状态。AgentLoop 不通过 `Map<String, Object>` 传递核心上下文。

### 7.8 ContextAssemblyResult

```java
public final class ContextAssemblyResult {
    private final List<ChatMessage> messages;
    private final List<ToolSpecification> toolSpecifications;
    private final ContextBudgetReport budgetReport;
    private final ContextAssemblyDebugInfo debugInfo;
}
```

返回集合必须不可变。AgentLoop 只能读取并用于构造 ChatRequest，不得追加、删除、重排或替换其中内容。

## 8. Provider 最终职责

| Provider | 生命周期 | 默认可见性 | 责任 |
|---|---|---|---|
| RuntimeContextProvider | REQUEST_STATIC | TRACE_ONLY | 提供 request/session/user/clientMessage 等诊断信息 |
| PersonaContextProvider | REQUEST_STATIC | POLICY_ONLY | 解析有效 persona，驱动 Prompt 和策略 |
| PromptContextProvider | REQUEST_STATIC | MODEL_VISIBLE | 调用 PromptManager 渲染可信 System Prompt |
| UserInputContextProvider | REQUEST_STATIC | MODEL_VISIBLE | 创建当前 LangChain4j UserMessage |
| IntentContextProvider | REQUEST_STATIC | POLICY_ONLY | 提供 IntentResult，驱动优先级和工具策略 |
| ToolContextProvider | REQUEST_STATIC | MODEL_VISIBLE | 根据 ToolGroup 和 ToolRegistry 解析真实 ToolSpecification |
| LongTermMemoryProvider | REQUEST_STATIC | MODEL_VISIBLE | 获取当前用户长期记忆数据，不拼入 SystemMessage |
| SessionMemoryProvider | ITERATION_DYNAMIC | MODEL_VISIBLE | 获取最新 session ChatMemory 快照 |
| VehicleStateContextProvider | ITERATION_DYNAMIC | MODEL_VISIBLE | 每轮获取最新车辆状态 |
| TimeContextProvider | ITERATION_DYNAMIC | MODEL_VISIBLE | 每轮获取当前时间 |
| CallerExtraContextProvider | REQUEST_STATIC | MODEL_VISIBLE | 获取 caller extra，标记为不可信数据 |

原 `ToolGroupContextProvider` 不再向 LLM 重复渲染工具名称和分组描述。ToolGroup 结果用于选择 ToolSpecification 和 ContextPolicy；工具 Schema 只通过 `ChatRequest.toolSpecifications()` 发送。

Provider 只读取能力模块，不拥有能力实现：

- Prompt Provider 调用 PromptManager。
- Memory Provider 调用 MemoryOrchestrator 的只读快照接口。
- Tool Provider 调用 ToolRegistry 的只读规格查询接口。
- Vehicle Provider 调用 VehicleStatusProvider。

## 9. TEXT 请求数据流

### 9.1 请求准备

```text
AgentRuntime.startSession()
  → 解析 userId / sessionId / personaId
  → IntentRouter
  → ToolGroupSelector
  → ContextOrchestrator.prepare()
      → 执行 REQUEST_STATIC Providers
      → 渲染可信 System Prompt
      → 读取用户长期记忆
      → 解析 ToolSpecification
      → 创建 currentUserMessage
      → 生成 ContextFrame
  → Runtime 取消检查
  → AgentLoop 将 currentUserMessage 写入 session ChatMemory 一次
```

当前用户消息由 Context 创建，AgentLoop 只负责写入 ChatMemory。

### 9.2 每次 AgentLoop 迭代

```text
ContextOrchestrator.assemble(frame, iteration)
  → SessionMemoryProvider 读取最新 ChatMemory
  → VehicleProvider 获取最新车辆状态
  → TimeProvider 获取当前时间
  → ContextPolicy 计算可见性、必需性和优先级
  → ContextMessageAssembler 生成候选输入
  → ContextBudgetManager 检查完整预算
  → 必要时请求 Memory 持久化压缩
  → 重新读取 MemorySnapshot 并重新装配
  → 验证消息序列
  → 返回 ContextAssemblyResult
```

AgentLoop 随后只执行机械构建：

```java
ChatRequest request = ChatRequest.builder()
        .messages(result.messages())
        .toolSpecifications(result.toolSpecifications())
        .build();
```

## 10. 固定消息顺序与安全边界

每次调用模型都使用同一顺序：

```text
1. SystemMessage
   仅可信 Persona Prompt 和系统规则

2. Context Data UserMessage
   长期记忆、车辆状态、时间、caller extra
   明确标记为参考数据，不视为系统指令
   不写入 ChatMemory

3. Session ChatMemory
   历史 UserMessage / AiMessage
   当前 UserMessage
   当前轮 AiMessage(tool call)
   ToolExecutionResultMessage
```

硬性规则：

1. 每次请求只能有一个 SystemMessage。
2. requestId、sessionId、userId、clientMessageId、路由原因和 Provider 错误不进入模型。
3. IntentResult 和 ToolGroupSelectionResult 默认只参与策略，不渲染成消息。
4. caller extra 和长期记忆不得进入 SystemMessage。
5. ToolSpecification 不在 Context Data Message 中重复描述。
6. Context Data Message 不持久化到 ChatMemory，避免每轮重复积累。
7. ContextMessageAssembler 必须检查当前 UserMessage 不重复。

### 10.1 Tool Calling 第二轮

第一轮发送：

```text
SystemMessage
ContextDataMessage
历史消息
当前 UserMessage
```

模型返回工具调用后，AgentLoop 写入：

```text
AiMessage(toolExecutionRequest)
ToolExecutionResultMessage
```

第二轮重新装配：

```text
SystemMessage
更新后的 ContextDataMessage
历史消息
当前 UserMessage
AiMessage(toolExecutionRequest)
ToolExecutionResultMessage
```

车辆工具执行后，VehicleStateContextProvider 在下一轮读取新状态。

## 11. 工具可见性设计

Context Tool Provider 根据 `selectedToolNames` 从 ToolRegistry 解析真实 ToolSpecification。

规则：

1. Context 输出的工具规格必须全部存在于 ToolRegistry。
2. ToolRegistry 继续负责工具规格来源、注册和执行。
3. AgentLoop 不保存构造期固定的 effectiveToolSpecs。
4. ToolGroup 明确选中的工具优先级为 HIGH。
5. 选择器异常产生的全量兜底不能直接暴露所有工具。
6. 高风险车控工具必须有明确 ToolGroup 命中才可见。
7. 全量兜底最多保留配置允许的基础只读、低风险工具。
8. Tool Calling 下一轮继续使用本请求同一组工具规格，除非未来另行设计动态工具发现。

工具规格解析失败属于必需错误，不允许回退为全量工具。

## 12. Memory 与压缩边界

### 12.1 ChatMemory 语义

LangChain4j ChatMemory 是提供给模型的可变记忆，不是完整不可变历史。它允许淘汰、摘要和改写。

如果未来需要完整聊天记录查询，应新增独立 ConversationHistoryStore。该能力不属于本轮范围，不能阻塞 Context 改造。

### 12.2 Context 与 Memory 分工

```text
Context
  → 计算完整输入预算
  → 判断主要预算压力是否来自短期记忆
  → 计算 Memory 目标预算
  → 调用 MemoryOrchestrator.compactSessionToBudget(...)

Memory
  → 执行 MemoryCompressor
  → 保持工具消息原子性
  → 原子更新 live ChatMemory 与 SQLite
  → 返回压缩结果

Context
  → 重新读取 MemorySnapshot
  → 重新装配
  → 再次验证预算
```

同一请求最多触发一次持久化压缩，防止压缩循环。压缩失败后 Context 可以继续删除 OPTIONAL 内容；若 CRITICAL 内容仍超限，则明确失败。

长期记忆应由 Memory 提供结构化条目或稳定快照，Context 按完整条目选择，不得对最终长期记忆字符串做任意 substring 截断。

## 13. Token 预算设计

### 13.1 ContextBudgetPolicy

预算逻辑以 Token 为逻辑单位，但第一版不要求每轮运行精确 tokenizer。

```java
public final class ContextBudgetPolicy {
    private final int maxContextTokens;
    private final int reservedOutputTokens;
    private final int safetyMarginTokens;

    public int maxInputTokens() {
        return maxContextTokens - reservedOutputTokens - safetyMarginTokens;
    }
}
```

预算数值来自当前部署模型的受控配置，不从网络动态查询。不同模型使用不同 Policy；本轮只需要为 TEXT qwen-turbo 配置。

### 13.2 ContextTokenEstimator

采用三级策略：

1. 每轮使用低成本保守估算。
2. 对静态 System Prompt、Tool Schema、未变化长期记忆进行缓存。
3. 如果未来提供可靠的 Qwen 本地 tokenizer，只在接近阈值时执行精确计算。

第一版不得引入与 Qwen 不匹配的 tokenizer。模型返回真实 token usage 时，记录预估误差用于校准，不影响当前请求决策。

### 13.3 完整预算范围

```text
输入 Token
= SystemMessage
 + Context Data Message
 + Session ChatMemory
 + ToolSpecification Schema
```

模型输出预留和安全余量不属于输入内容，但必须从上下文窗口中预先扣除。

### 13.4 动态优先级

| 级别 | 示例 | 规则 |
|---|---|---|
| CRITICAL | SystemMessage、当前 UserMessage、当前工具调用与结果 | 永不删除、永不截断 |
| HIGH | 最近对话、当前意图必需车辆状态、明确选中的工具 Schema | 优先保留 |
| NORMAL | 长期记忆、普通车辆状态、时间 | 按完整条目缩减 |
| OPTIONAL | caller extra、较旧记忆、低相关工具候选 | 优先移除 |
| TRACE_ONLY | ID、路由原因、调试信息 | 不进入预算 |

车辆状态在车辆控制意图下可升级为 HIGH 或 REQUIRED，在普通闲聊下可降为 OPTIONAL。

### 13.5 预算处理顺序

```text
1. 生成完整候选输入并统计各来源 Token
2. 移除 OPTIONAL Contribution
3. 按完整条目缩减长期记忆
4. 缩减低优先级工具候选
5. 若主要压力来自短期记忆，触发一次持久化压缩
6. 重新装配和估算
7. 若仍超限，删除最旧的完整历史对话单元
8. 若 CRITICAL 内容本身超限，返回 CONTEXT_BUDGET_EXCEEDED
```

### 13.6 裁剪原子性

裁剪单位必须识别：

```text
ConversationTurn
  UserMessage
  AiMessage
  可选 ToolExchange

ToolExchange
  AiMessage(toolExecutionRequests)
  对应的全部 ToolExecutionResultMessage
```

不能产生孤立的 ToolExecutionResultMessage，不能截断 JSON Schema、SystemMessage、当前用户输入或工具结果正文。

### 13.7 ContextBudgetReport

每轮返回：

```text
estimatedInputTokens
reservedOutputTokens
messageTokens
toolTokens
budgetUtilization
droppedContributions
droppedMessageUnits
droppedTools
compressionTriggered
compressionSucceeded
finalWithinBudget
```

## 14. Provider 失败和取消

### 14.1 必需失败

以下情况终止请求：

- System Prompt 无法加载或渲染。
- 当前 UserMessage 无法创建。
- session ChatMemory 无法读取。
- ToolGroup 选中工具但 ToolRegistry 找不到规格。
- 必需车辆上下文无法读取。
- 压缩后消息序列非法。
- CRITICAL 内容仍超过预算。

标准错误：

```text
CONTEXT_REQUIRED_PROVIDER_FAILED
CONTEXT_TOOL_RESOLUTION_FAILED
CONTEXT_BUDGET_EXCEEDED
CONTEXT_MESSAGE_SEQUENCE_INVALID
```

### 14.2 可选降级

以下内容失败时可以省略并记录原因：

- 长期记忆。
- 非必需时间。
- 普通闲聊下的车辆状态。
- caller extra。

降级只允许减少 Context 内容，不允许切回旧 PreProcessor 或旧 AgentLoop 拼接路径。

### 14.3 取消检查点

```text
Context prepare 前
Context prepare 后
每次 assemble 前
压缩完成后
实际调用 LLM 前
```

取消仍属于 Runtime/AgentLoop 终态管理。Context 只返回取消前已完成的诊断，不抢占请求终态。

## 15. Trace 设计

### 15.1 当前 Trace 调整原则

- 删除与 Context 重复的 `prompt.assembly` span。
- 新增 `context.prepare` 和每迭代一次的 `context.assemble`。
- 保留 Prompt 渲染、Memory 读取/提取/压缩、LLM、Tool 和响应分发能力 span。
- Provider 默认记录为 Context span event，不为每个 Provider 创建 span。
- 修复 TraceSession 的父上下文 API，不能再把所有 span 固定挂到 root。

### 15.2 目标层级

```text
agent.request
├─ context.prepare
│  ├─ prompt.render
│  └─ memory.read.long_term
├─ agent.loop
│  ├─ context.assemble                 iteration=0
│  │  ├─ memory.read.session
│  │  ├─ context.provider event
│  │  ├─ context.budget event
│  │  └─ memory.compress              仅触发时
│  ├─ gen_ai.chat
│  ├─ tool.execute
│  ├─ context.assemble                 iteration=1
│  │  ├─ memory.read.session
│  │  ├─ context.provider event
│  │  └─ context.budget event
│  ├─ gen_ai.chat
│  └─ memory.extract
└─ response.dispatch
```

`agent.loop` 当前只有 span 名称常量，最终必须真正创建并作为 Context、LLM、Tool、MemoryExtract 的父 span。

### 15.3 Context Trace 属性

`context.prepare`：

```text
provider 总数、成功数、可选失败数、必需失败数
effective persona
prompt template 名称和内容 hash
长期记忆条目数
候选/最终工具数量
prepare 耗时
```

`context.assemble`：

```text
iteration
System/User/Ai/ToolResult 消息数量
各来源预估 Token
Tool Schema 预估 Token
预算上限和占用比例
删除的 Contribution、历史轮次和工具数量
压缩触发原因、压缩前后 Token
消息序列是否合法
assemble 耗时
```

### 15.4 内容采集策略

生产模式只记录数量、长度、Token、模板名、hash、工具名和错误类型。

调试模式可以记录经过 `TraceRedactor` 处理的 Prompt、消息和 Memory 摘要正文。未经脱敏的 Prompt、长期记忆、用户输入和车辆状态不得写入生产 Trace。

所有 span 名称和属性键必须集中在 `TraceSpanNames` 和 `TraceAttributeKeys`，禁止硬编码字符串。

## 16. 迁移方案

### Phase 0：基线与特征测试

- 固化 Persona、session memory、用户切换、Tool Calling、车辆状态和 Trace 行为。
- 使用 Fake ModelCaller 捕获旧链路实际 ChatRequest。
- 建立影子比较规范和允许差异表。

### Phase 1：Context 强类型契约

- 引入 Contribution、可见性、信任、生命周期、优先级。
- 引入 ContextBudgetPolicy、ContextTokenEstimator。
- 引入 ContextAssemblyRequest/Result。
- 实现纯 ContextMessageAssembler。
- 不改变生产模型输入。

### Phase 2：Provider 真实化

- Prompt、UserInput、LongTermMemory、SessionMemory、Vehicle、Time、CallerExtra、Tool Provider 接入真实能力接口。
- 删除只记录 owner 的空 Provider 语义。
- 保持旧链路作为唯一真实写入者。

### Phase 3：影子装配与差异验证

- 每次迭代生成 Context 候选输入，但不发送给模型。
- 比较消息类型、顺序、数量、工具规格、预算和工具链合法性。
- 记录预期差异：内部 ID 移除、长期记忆角色变化、动态工具收敛。

### Phase 4：预算、压缩和 Trace 接入

- 接入完整输入预算和一次性持久化压缩。
- 接入消息原子裁剪和全量工具兜底限制。
- 新增 Context span 并修复 Trace 父子上下文。
- 继续影子验证。

### Phase 5：Context 独占切换

- AgentLoop 只消费 ContextAssemblyResult。
- 移除 AgentLoop 的 System Prompt、transientMessages、allMessages 和固定工具规格逻辑。
- TEXT 配置不再使用消息型 PreProcessor。
- 每个阶段结束后执行完整回归。

### Phase 6：最终清理

- 删除 ContextMode 整个枚举和所有模式判断。
- 删除 ContextExtraPreProcessor。
- 从 TEXT 配置移除 VehicleStatusPreProcessor、TimeContextPreProcessor。
- 删除无调用者的 MemoryPreProcessor；若非 TEXT 链路仍使用某 PreProcessor，只保留该非 TEXT 用途。
- 删除 context_rendered_extra、context_mode、caller_extra_context 等兼容键。
- 删除 ContextFrame.toOrchestratorContext() 和核心 `Map<String, Object>` 适配路径。
- 删除 AgentLoop.buildSystemPromptMessage()。
- 删除 AgentLoop.effectiveToolSpecs 固定绑定。
- 删除 prompt.assembly span。
- 删除影子比较和旧链路测试，保留最终行为测试。
- 更新 Context Overview、架构图和模块总结。

最终代码中不再存在运行模式概念。`FULL_CONTEXT` 只作为迁移目标名称，不保留单值枚举或配置开关。

## 17. 测试矩阵

### 17.1 单元测试

- Contribution 可见性、信任、生命周期和优先级。
- ContextPolicy 动态优先级。
- 单一 SystemMessage。
- currentUserMessage 不重复。
- 固定消息顺序。
- ToolExchange 原子性。
- Provider 必需失败和可选降级。
- Token 估算缓存和安全余量。
- CRITICAL 内容不可裁剪。

### 17.2 Tool 测试

- ToolGroup 到 ToolSpecification 精确映射。
- ToolRegistry 缺失规格时明确失败。
- 全量兜底不暴露高风险工具。
- Schema 预算计入完整输入。
- 下一轮工具规格保持稳定。

### 17.3 Memory 测试

- session 短期记忆隔离不回退。
- 切换用户时短期对话保持、长期记忆切换。
- 压缩同一请求最多触发一次。
- 压缩后 live ChatMemory 与 SQLite 一致。
- 压缩不破坏 ToolExchange。
- 长期记忆不进入 SystemMessage。

### 17.4 多轮 AgentLoop 测试

- 第一轮工具调用。
- 工具结果回填。
- 第二轮读取更新后的车辆状态。
- 第二轮消息不存在重复 UserMessage。
- 安全否决后结果和 Memory 行为正确。
- 最大迭代、超时和模型异常行为不回退。

### 17.5 安全测试

- caller extra 无法提升为 SystemMessage。
- 用户长期记忆中的指令文本只作为数据。
- 内部 ID 和 Provider 错误不进入模型。
- 未明确命中的高风险工具不可见。
- 生产 Trace 不记录未经脱敏正文。

### 17.6 Trace 测试

- agent.request → context.prepare / agent.loop / response.dispatch 层级正确。
- agent.loop → context.assemble / gen_ai.chat / tool.execute / memory.extract 层级正确。
- memory.compress 在触发时是 context.assemble 子 span。
- 每次迭代都有且只有一个 context.assemble。
- prompt.assembly 不再出现。
- 预算、Provider 状态、消息计数和工具计数属性正确。

### 17.7 取消测试

- prepare 前取消。
- prepare 后取消。
- assemble 前取消。
- 压缩后取消。
- LLM 前取消。
- 已取消请求不会继续调用模型或执行工具。

### 17.8 构建与集成验证

- `testDebugUnitTest`。
- `assembleDebug`。
- `lintDebug`。
- 条件允许时执行设备端 AIDL TEXT 对话测试。
- Fake ModelCaller 捕获并断言最终 ChatRequest。

## 18. 最终验收标准

1. TEXT AgentLoop 的每个 `ChatRequest.messages()` 只能来自 `ContextAssemblyResult`。
2. `ChatRequest.toolSpecifications()` 与 Context Tool Provider 的最终选择完全一致。
3. 每次请求只有一个可信 SystemMessage。
4. 当前 UserMessage 不重复。
5. ToolExecutionResultMessage 不孤立。
6. session 短期记忆隔离和用户长期记忆切换不回退。
7. Tool Calling 第二轮可以读取工具执行后的最新车辆状态。
8. 完整输入估算包含消息、工具 Schema、输出预留和安全余量。
9. 压缩写回后 live ChatMemory 与 SQLite 一致。
10. CRITICAL 内容超限时明确失败，不发送损坏请求。
11. 生产 Trace 不泄露未经脱敏的 Prompt、Memory、用户输入或车辆状态。
12. Trace 展示正确的 Context、Prompt、Memory、LLM、Tool 父子链。
13. AgentLoop 中不存在 System Prompt 构建、临时消息拼接、历史排序和固定工具规格决策。
14. TEXT AgentConfig 不再配置消息型 PreProcessor。
15. 代码中不存在 ContextMode、HYBRID/OBSERVE 分支或旧 Map 兼容键。
16. scene、vision/VL 和 Memory 内部辅助模型调用行为保持不变。
17. 全部自动化测试、构建和 lint 通过；无法执行的设备测试必须在验收报告中明确标注。

## 19. 风险与缓解

| 风险 | 缓解措施 |
|---|---|
| 一次性切换导致消息行为难以定位 | 使用影子装配、Fake ModelCaller 和允许差异表 |
| Context 变成新的巨型模块 | Orchestrator、Policy、Assembler、Budget、Provider、Trace 分离；Assembler 保持纯函数 |
| Token 估算不准确 | 保守估算、安全余量、静态缓存、真实 usage 校准 |
| 压缩与 live ChatMemory 分裂 | Memory 提供 session 级原子压缩 API，同时更新缓存和 SQLite |
| 工具 Schema 过大 | ToolGroup 精确选择、风险分级、全量兜底限制、工具预算 |
| 工具消息被裁剪破坏 | ToolExchange 和 ConversationTurn 原子裁剪 |
| 用户数据提升为系统指令 | 信任级别、单一 SystemMessage、长期记忆和 caller extra 数据化 |
| Provider 失败被静默吞掉 | required/optional 分级和标准 Context 错误 |
| Trace 过度碎片化 | Provider 使用 event，只有真实 I/O、压缩和模型操作使用子 span |
| Trace 内容泄漏 | production/debug 两级采集和 TraceRedactor |
| 最终保留双链路 | Phase 6 硬性清理和静态搜索验收 |

## 20. 设计完成定义

本文档中的设计只有在以下条件同时满足时，才可以进入实施计划：

1. 项目方确认目标、范围、消息顺序、预算、压缩、Trace 和迁移边界。
2. 文档不存在未决占位项、双重事实来源或模式长期共存描述。
3. 所有最终验收标准都可以通过自动化测试、静态检查或明确的设备测试验证。
4. 实施计划按 Phase 拆分，每个 Phase 都包含独立测试和停止条件。
5. 实施计划不得把 scene、vision/VL 或内部 Memory LLM 调用混入本轮范围。
