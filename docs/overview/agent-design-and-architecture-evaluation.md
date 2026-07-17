# AIAgent Agent 设计与架构评估报告

> 评估日期：2026-07-16  
> 评估范围：以当前 TEXT Agent 真实运行流程为主，兼顾 Service、Runtime、Context、Memory、Tool、Safety、Trace 的协作边界  
> 评估目标：判断 Agent 的架构定位是否合理、运行闭环是否成立、自治边界是否清晰，以及下一阶段应优先补齐什么  
> 非评估目标：不逐个模块扫描代码 Bug，不重复阶段修改清单，不把“文件数量多”视为架构成熟度

---

## 1. 总体结论

AIAgent 当前最准确的架构定位是：

> **运行在 Android 车机后台、通过确定性控制面约束大模型决策的单 Agent 车载执行引擎。**

它不是普通的 LLM 聊天封装，也不是完全自主的通用 Agent，更不是多 Agent 系统。项目采用的是“确定性 Workflow + 模型驱动 Tool Loop”的混合架构：代码负责请求准入、意图路由、工具范围、上下文策略、安全审核、截止时间和终态；模型负责理解自然语言、选择允许范围内的工具、生成参数，并根据工具结果决定下一步。

这个定位适合车载场景。车控任务的目标边界、工具集合和风险规则相对明确，不需要让模型拥有无限规划能力；但自然语言表达复杂，又需要保留模型在有限空间内理解和决策的能力。因此，项目选择“受约束自治”而不是“最大自治”，方向是正确的。

当前架构已经完成了一个较完整的 Agent 主循环：

```text
用户请求
  → 规范化与请求准入
  → 意图识别与工具域选择
  → Context 准备与逐轮装配
  → LLM 判断
      ├─ 直接回答 → 后处理 → 结果返回
      └─ 工具调用 → Safety → Tool 执行 → 环境结果回灌 → 下一轮 LLM
  → Memory 持久化 / 提取
  → 唯一终态响应与 Trace 闭合
```

从 Demo 和工程原型角度看，项目已经不是“功能拼接”，而是形成了自己的 Agent Runtime、Context 系统和执行治理体系；从真实车辆控制角度看，主要短板也已经不再是缺少模块，而是动作闭环最后一段仍不够强：**模型可见工具尚未完全等价于执行授权、工具方法成功尚未完全等价于车辆动作成功、最终自然语言答复尚未被动作证据强约束。**

综合评分：

| 评估维度 | 评分 | 判断 |
|---|---:|---|
| 业务与 Agent 形态匹配度 | 9.0 / 10 | 受约束单 Agent 很适合车载自然语言控制 |
| 总体分层与职责边界 | 8.4 / 10 | 主链分层清晰，旧链路仍有重叠 |
| 运行流程完整性 | 8.6 / 10 | 请求、迭代、工具反馈、终态基本闭环 |
| Context 设计 | 9.0 / 10 | 已成为独立上下文系统，而非 Prompt 拼接 |
| Tool 与动作治理 | 7.2 / 10 | 选择和分发较成熟，授权与业务回执仍需加强 |
| Safety 与信任边界 | 7.0 / 10 | Demo 安全骨架完整，真实调用方与策略统一性不足 |
| Memory 与状态连续性 | 8.2 / 10 | 会话、长期记忆、压缩和提取链完整 |
| 可观测性 | 8.5 / 10 | Trace 深度优于一般 Demo |
| Agent 行为评测 | 6.3 / 10 | 工程测试较强，端到端行为基准仍不足 |
| 扩展性与长期维护 | 7.4 / 10 | 接口化良好，但双循环和多控制面会造成漂移 |

**总体设计评分：8.2 / 10。**

成熟度判断：TEXT Demo 已接近完整；内部联调具备较好基础；真实车控仍需要动作授权、动作回执、调用方身份和行为评测四个闭环。

---

## 2. 评估方法

本报告没有按模块逐项验收，而是沿着一次 Agent 请求的生命周期评估以下七个问题：

1. **目标适配性**：该问题是否真的需要 Agent，还是普通规则或单次模型调用即可解决；
2. **控制与自治边界**：哪些决定交给模型，哪些决定必须由确定性代码掌握；
3. **Context 与状态连续性**：模型每一轮是否得到正确、及时、预算可控的输入；
4. **工具与环境反馈**：工具是否清晰、可约束，结果是否作为真实环境证据回到循环；
5. **安全与权限**：外部数据是否可能变成指令，危险动作是否经过独立授权与确认；
6. **可靠性与终止性**：Agent 是否可取消、可超时、可限制迭代，并且只能产生一个终态；
7. **可观测与可评测性**：是否能够从结果、轨迹、状态变化和安全行为上衡量 Agent。

这种方法与当前常见的 Agent 评估思路一致：Agent 的价值不只看最终文本，还要看多轮轨迹、工具调用、环境状态变化和停止条件。Anthropic 将 Workflow 与 Agent 区分为“预定义代码路径”和“模型动态控制过程”，并强调环境真实反馈、停止条件、简单可组合模式和完整评测；这与本项目的领域约束设计具有较高可比性：

- [Building effective agents](https://www.anthropic.com/engineering/building-effective-agents)
- [Demystifying evals for AI agents](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents)

安全评估还参考了 NIST 对 Agent Hijacking 的核心判断：Agent 会把可信系统指令与不可信外部数据放入统一模型输入，因此需要明确的信任边界，并通过任务级、重复尝试的攻击场景评估防护能力：

- [Strengthening AI Agent Hijacking Evaluations](https://www.nist.gov/news-events/news/2025/01/technical-blog-strengthening-ai-agent-hijacking-evaluations)

---

## 3. Agent 架构定位

### 3.1 它是什么

AIAgent 是一个领域约束型、工具增强型、带持久状态的单 Agent 系统：

- **领域约束型**：能力集中在车载对话、车辆控制、天气和视觉问答；
- **工具增强型**：模型通过 LangChain4j Tool Calling 产生结构化工具请求；
- **带持久状态**：会话历史、用户长期记忆和摘要保存在 SQLite 中；
- **单 Agent**：一条请求由一个核心模型循环负责，没有 Planner/Worker/Critic 等多个自治角色；
- **确定性外壳**：模型不能决定请求 Deadline、准入规则、ToolGroup 选择状态、安全规则和最终响应抢占。

### 3.2 它不是什么

- 不是完全由 LangChain4j 编排的 Agent。项目使用 LangChain4j 的模型、消息、ChatMemory 和 Tool Calling 原语，但编排权属于自研 Runtime、Context 和 AgentLoop；
- 不是开放式通用 Agent。模型的行动空间由车载领域、ToolGroup 和 Safety 限制；
- 不是多 Agent。Persona 是配置差异，不等于多个 Agent 协作；
- 不是只做问答的 RAG 系统。它的核心价值是语言理解后对车辆环境产生动作，并利用动作结果继续推理。

### 3.3 这种定位是否合理

合理，而且比直接采用高度自治框架更适合当前项目。

车载任务同时具有两种性质：

- “用户到底想做什么、参数如何表达”适合由模型处理；
- “能否执行、可以调用什么、何时停止、结果是否可信”必须由确定性代码处理。

当前架构把这两类责任拆开，避免模型既当理解器又当安全策略和生命周期管理器。这是本项目最重要的设计优点。

---

## 4. 总体架构分析

### 4.1 三个平面

当前系统可以比目录分层更准确地概括为三个平面：

```text
┌────────────────────────────────────────────────────────────┐
│ 控制平面                                                   │
│ AIDL / Admission / RequestSession / Intent / ToolGroup     │
│ Deadline / Cancel / Terminal CAS / Confirmation            │
└───────────────────────┬────────────────────────────────────┘
                        │ 约束本次请求
┌───────────────────────▼────────────────────────────────────┐
│ 推理与上下文平面                                           │
│ Context Provider → Policy → Budget → Messages / ToolSpecs  │
│                     LLM ↔ Tool Loop                        │
└───────────────────────┬────────────────────────────────────┘
                        │ 读写环境状态
┌───────────────────────▼────────────────────────────────────┐
│ 状态与基础设施平面                                         │
│ Session Memory / Long-term Memory / Vehicle State / SOA    │
│ ToolRegistry / SQLite / DashScope / OpenTelemetry          │
└────────────────────────────────────────────────────────────┘
```

这种分法能够解释项目为何需要自研 Runtime 和 Context：LangChain4j 负责模型交互原语，但车机请求生命周期、工具权限、车辆状态和 Android IPC 不属于通用模型框架的职责。

### 4.2 分层优点

1. `AIAgentService` 处理 Android 生命周期、AIDL 和响应派发，不直接承担 LLM 多轮推理；
2. `AgentRuntime` 将请求转换为不可变 `RequestSession`，固定 user、session、persona、intent、ToolGroup、deadline 和 trace；
3. `ContextOrchestrator` 独占 TEXT 模型输入的采集与装配权，避免多个模块绕开预算随意拼 Prompt；
4. `TextAgentLoopOrchestrator` 专注模型—工具循环；
5. `ToolSafetyEngine` 独立于模型提示词进行确定性审核；
6. Memory、Trace、ToolRegistry 以基础能力参与主链，而不是散落在业务入口。

### 4.3 分层不足

当前架构是逐步演进形成的，因此还存在新旧边界重叠：

- `AgentLoopOrchestrator` 与 `TextAgentLoopOrchestrator` 并存，两套循环承担相近但不完全相同的责任；
- `AgentConfig` 中保留 PreProcessor、ToolExecutor、timeout 等旧配置，而 TEXT 主链已有 Context、ToolRegistry 和统一 Deadline；
- TEXT、IMAGE、VOICE、CONTROL 从 Service 开始走不同控制面；
- Service 同时负责 IPC、请求准入、确认状态机调度、线程投递、超时回调和终态响应，长期看偏重。

这些问题当前不会否定主架构，但会增加以后修改 Safety、Trace、错误语义时的同步成本。下一步更适合逐步抽取共享执行管线，而不是立即进行大规模重写。

---

## 5. 真实运行流程评估

### 5.1 阶段一：AIDL 入口与请求生命周期

TEXT 请求进入 `AIAgentService.handleTextRequest()` 后，首先生成或规范化 requestId、userId、personaId，并创建 30 秒绝对 Deadline。`ActiveRequestRegistry` 提供单槽准入、活动请求去重和完成请求短期缓存；主线程定时器、Worker、取消请求通过 CAS 竞争唯一终态。

#### 设计评价

这是成熟度较高的请求控制设计。它解决了 Agent 系统比普通接口更容易出现的几个问题：模型调用时间长、取消后底层请求仍运行、超时与成功同时到达、旧请求尚未退出新请求就进入。特别是“终态通知”和“执行槽位释放”分开，槽位等 Worker 真正退出后才释放，语义是正确的。

#### 设计取舍

单全局 TEXT 槽位适合当前 Demo：它降低了共享车辆状态、确认动作和模型调用并发带来的风险。但它也意味着一个慢请求会阻塞所有用户和 Session。将来如果需要多用户并发，不应简单移除单槽限制，而应改成：

```text
每个会话串行执行
  + 只读请求允许有限并发
  + 所有车辆写动作进入全局 Action Arbiter
```

这样才能既提高吞吐，又保留车辆动作的全局顺序和冲突治理。

### 5.2 阶段二：RequestSession、Intent 与 ToolGroup

`AgentRuntime.startSession()` 在 AgentLoop 前完成 IntentRouter 和 ToolGroupSelector，并将结果写入不可变 `RequestSession`。选择结果不是简单的工具列表，而是 `SELECTED / CHAT_ONLY / CLARIFICATION_REQUIRED / FAILED_CLOSED` 四种状态；失败状态在 Context 和模型调用前结束。

#### 设计评价

`RequestSession` 是很重要的边界。它把一次请求的身份、路由和截止时间冻结下来，使后续 Context、Trace 和 AgentLoop 不必反复读取可变的 `AgentRequest`。ToolGroup 在模型调用前缩小工具空间，也能降低 Token、工具误选和攻击面。

#### 主要限制

当前关键词 IntentRouter 本质上是单标签分类器：多个领域同时命中时只保留一个最高分结果，平局按固定优先级选择。因此它更适合单目标指令，而不适合“打开空调并关闭车窗”这样的复合任务。

此外，当前 Intent 主要区分业务领域，没有把以下语义设为强类型：

- 查询状态；
- 执行动作；
- 条件动作；
- 多动作组合；
- 表达不完整、必须澄清。

这使“车窗现在开了多少”和“把车窗打开”可能获得相似工具域。当前主要依靠 Prompt 让模型不要误操作，控制强度仍不够。

### 5.3 阶段三：Context Prepare

`ContextOrchestrator.prepare()` 在请求级执行 8 个静态 Provider：Runtime、Persona、Prompt、UserInput、Intent、ToolGroup、LongTermMemory 和 CallerExtra。每个 Provider 都有统一输出契约、required 判定、失败语义和 Trace，最终形成不可变 `ContextFrame`。

#### 设计评价

这是当前架构中完成度最高的部分之一。Context 不再等同于“把字符串拼进 system prompt”，而是成为模型输入的数据管理系统：

- 每个来源有明确所有者；
- 来源与最终消息格式解耦；
- 必需源失败可在模型前终止；
- 不可信数据和可信系统指令具有不同 Trust 标记；
- Provider 运行结果可观测。

这种设计使 Prompt、长期记忆、CallerExtra 和 ToolSpec 不必互相知道，也避免 Service、Memory、ToolGroup 各自直接修改最终消息数组。

### 5.4 阶段四：逐轮 Context Assembly

每轮 Agent 迭代开始时，Context 再运行 3 个动态 Provider：SessionMemory、VehicleState 和 Time。静态与动态 Contribution 合并后，由 `ContextMessageAssembler` 生成唯一的 ChatMessage 和 ToolSpecification 列表。

如果预算超限，系统先按策略裁剪可选来源；仍超限时，Context 请求 Memory 对完整历史 Turn 做一次摘要压缩，通过快照比较和 CAS 写回，然后重新运行动态 Provider 并重新装配。

#### 设计评价

逐轮重算是正确的。车辆状态、工具结果和会话历史都会在 Agent 执行过程中变化，如果只在请求开始时构建一次 Prompt，后续模型看到的就是旧环境。当前的 Request Static / Iteration Dynamic 划分，符合 Agent 必须不断读取环境反馈的要求。

预算设计也比较成熟：不是简单按消息条数丢弃历史，而是保护系统 Prompt、当前用户、会话结构和工具规格，优先裁剪低优先级可选数据，必要时再通过摘要恢复。对 LangChain4j ToolMessage 的完整性也有单独序列校验，这一点很重要，因为工具请求与工具结果不能被任意拆开。LangChain4j 官方 ChatMemory 文档同样强调 Tool 消息需要特殊处理：[Chat Memory](https://docs.langchain4j.dev/tutorials/chat-memory/)。

#### 仍需关注

Trust 标记目前主要影响上下文封装和诊断，并不天然构成执行权限。即使 CallerExtra 或长期记忆被标为 `UNTRUSTED_DATA`，模型仍会读取它们；真正的防线必须位于工具授权和 Safety，而不能只依靠模型理解“这段内容是不可信数据”。

### 5.5 阶段五：模型—工具循环

`TextAgentLoopOrchestrator` 每轮使用 Context 输出构造 `ChatRequest`。模型返回普通文本时进入 PostProcessor、Terminator 和 ResultCollector；返回 ToolExecutionRequest 时，系统先写入 ToolCall 消息，再进行整批 Safety 预检，然后按顺序执行工具，把 ToolExecutionResultMessage 写回 SessionMemory，并进入下一轮。

#### 设计评价

该循环已经具备 Agent 的核心特征：

```text
模型决策
  → 环境动作
  → 真实工具结果进入历史
  → 模型根据新证据继续决策
```

最大迭代次数、Deadline、取消检查和 ToolExchange 闭合共同保证循环不会无限运行。整批 Safety 预检也避免了“第一个工具已执行，第二个才发现需要确认”的高风险部分执行。

#### 核心结构性缺口：工具可见性尚未成为最终授权

Context 只把 ToolGroup 对应的 ToolSpecification 发送给模型，但模型返回工具请求后，AgentLoop 直接调用全局 `ToolRegistry.dispatchWithOutcome()`，没有在分发点重新验证：

```text
requestedToolName 是否属于本轮 requestTools
```

正常模型通常不会调用未提供的工具，但这只是协议预期，不是权限边界。模型异常输出、提示注入、框架解析差异或未来代码变更都可能绕过“仅展示相关工具”的意图。

正确的架构应明确区分：

- **Tool Discovery / Exposure**：告诉模型有哪些工具；
- **Tool Authorization**：系统确认本次请求是否被允许执行该工具；
- **Tool Safety**：在已授权动作上检查参数、车辆状态和风险；
- **Tool Dispatch**：调用具体实现。

当前已经有 Exposure、Safety 和 Dispatch，但 Authorization 仍需要独立成为执行前硬门。

### 5.6 阶段六：Safety 与高风险确认

Tool 调用不直接分发，而是先经过独立 `ToolSafetyEngine`。规则可以返回 ALLOW、DENY 或 REQUIRE_CONFIRMATION。高风险动作不会在原请求中继续执行，而是创建 30 秒 PendingAction；下一条普通 TEXT 确认请求绕过 LLM，重新检查车辆状态后原子消费并执行。

#### 设计评价

这是合理的安全架构：

- Safety 不依赖系统提示词；
- 确认文字不交给 LLM 自由解释；
- 确认前重新读取车辆状态，避免确认期间环境变化；
- PendingAction 原子消费，避免重复确认导致重复动作；
- 多工具批次只要包含确认型动作，就拒绝整批执行，避免部分成功。

#### 主要不足

1. PendingAction 主要绑定 session 和原 request，尚未绑定不可伪造的 Binder caller identity；
2. AIDL Service 是 exported 能力入口，但服务级签名权限和调用方 UID/证书校验尚未形成清晰边界；
3. ToolGroup 风险等级、Safety 高风险集合和具体规则分别维护，新增工具时可能出现策略漂移；
4. 当前安全主要基于虚拟车辆状态，真实车环境还需要驾驶状态、挡位、速度、乘员、车门和 SOA 权限等可信数据。

因此，现状可以评价为“Demo 级确定性 Safety 已成型”，还不能直接等同于量产车控安全。

### 5.7 阶段七：工具结果、Memory 与最终答复

`ToolDispatcher` 已返回结构化 `ToolDispatchOutcome`，能够区分工具是否注册、参数是否解析、反射方法是否调用成功，并将结果写入 Trace。工具结果进入 ChatMemory 后，模型下一轮生成最终自然语言答复；普通答复经过后处理后写入会话，并同步触发长期记忆提取。

#### 设计评价

模型看到工具结果再回答，是正确的 Agent 结果链。会话持久化与外部最终答复使用同一份后处理文本，也避免了“用户看到的内容”和“Memory 记住的内容”不一致。

#### 核心结构性缺口：调用成功不等于任务成功

当前结构化结果主要描述技术分发过程，例如反射是否调用成功。但车辆工具仍大量返回自然语言字符串；业务拒绝、参数不满足、动作未生效也可能是 Java 方法正常返回。因此目前存在三层结果被混在一起的问题：

```text
Dispatch 成功：方法被正常调用
Action 接受：车辆控制层接受了请求
Goal 完成：车辆实际状态达到了用户目标
```

真实车控 Agent 需要独立的 `ActionReceipt` 或等价结果模型，至少包含：

- `actionId`；
- `status = APPLIED / REJECTED / FAILED / PARTIAL / UNKNOWN`；
- 原因码和可展示说明；
- 执行前后状态或状态回读证据；
- 数据来源与时间戳。

#### 核心结构性缺口：最终答复没有结果真实性门

当前终止器在模型不再请求工具时结束循环，结果收集器主要收集最终文本。系统 Prompt 可以要求模型不要虚报成功，但代码没有验证“已打开、已关闭、已调节”等完成表述是否对应成功 ActionReceipt。

这意味着 Agent 当前可以证明“模型调用了某个方法”，还不能在所有路径上证明“用户目标已经完成并且答复与事实一致”。这是下一阶段最关键的设计闭环。

#### Memory 路径取舍

长期记忆提取目前位于用户响应返回前的主路径。它有助于保证本轮信息及时入库，但也会继续占用 30 秒 Deadline 和全局执行槽。记忆提取属于增强能力，不应反转已经生成好的用户结果。更合理的长期形态是：先完成用户终态，再通过有界后台任务提取；提取失败进入 Trace 和重试队列，但不改变主请求结果。

### 5.8 阶段八：响应、终态与 Trace

Runtime 将 AgentResult 映射为 RuntimeResult，再由 Service 映射成 AgentResponse。正常完成、失败、超时和取消通过 ActiveRequest 的 CAS 竞争唯一终态；TraceResponseDispatcher 负责记录响应派发并关闭请求 Trace。

#### 设计评价

这是较完整的生命周期闭环。Trace 不只记录 HTTP 请求，还覆盖 Context Provider、预算、模型、Safety、Tool Dispatch、Writeback 和响应派发，使开发者能够回放 Agent 的执行轨迹。对于非确定性系统，这是比普通日志更有价值的基础设施。

#### 当前不足

Trace 已经能解释“发生了什么”，但还没有形成稳定的 Eval Dataset 和自动评分。可观测性是评测的原料，不等于评测本身。

---

## 6. 架构设计的主要优点

### 6.1 自治程度与业务风险匹配

模型只负责语言理解和有限工具决策，生命周期、工具域、安全和终态由代码控制。项目没有为了追求“更 Agent”而引入不必要的多 Agent、自由规划或递归任务分解。

### 6.2 Context 具有唯一输入装配权

模型最终收到的消息和工具规格都从 Context 输出，减少了模块绕过预算、重复注入 Prompt 和消息顺序破坏。Context 还支持静态/动态来源、信任等级、优先级、裁剪与摘要恢复。

### 6.3 工具治理具有领域结构

46 个工具不是无差别全部暴露，而是通过 13 个 ToolGroup 按意图缩小范围；ToolRegistry/Dispatcher 负责实现级注册，ToolGroup 负责模型可见性，Safety 负责执行前审核。这种职责划分总体合理。

### 6.4 记忆不是单一消息列表

项目区分 Session 生命周期、持久 ChatMemory、用户长期记忆、记忆提取和摘要压缩，并保护 ToolExchange 的完整性。它已经具备“会话状态”和“用户状态”两个不同维度。

### 6.5 可靠性设计进入真实主链

Deadline、底层 Call 取消、单槽准入、重复请求处理、终态 CAS 和最大迭代次数共同限制了 Agent 的资源和时间边界。

### 6.6 安全与可观测性均为主链能力

Safety 不依赖模型自律；Trace 也不是事后拼日志，而是沿 Context、LLM、Tool 和响应生命周期传播。这两点使项目具备继续向真实业务演进的基础。

---

## 7. 架构层面的主要缺口

以下是系统性设计缺口，不等同于普通代码 Bug。

### 7.1 接入真实车辆前必须完成

#### A. 工具执行授权

在 Dispatch 前验证工具属于本轮授权集合，并同时核验调用方、操作模式、风险策略和车辆状态。ToolGroup 负责选择，但最终授权应由不可绕过的执行门负责。

#### B. 结构化动作回执与状态回读

把 Dispatch、Action Accepted 和 Goal Completed 分开。最终成功必须由车辆控制层回执或可信状态回读证明。

#### C. 最终答复真实性

对写动作请求增加结果守卫：只有 APPLIED 才能回答“已完成”；REJECTED、FAILED、PARTIAL、UNKNOWN 分别使用与事实一致的结果语义。

#### D. AIDL 调用方信任边界

使用签名权限、可信包/证书或 Binder UID 校验；PendingAction 必须绑定 caller、user、session、request 和过期时间，并只允许原主体确认。

### 7.2 提升 Agent 能力完整性

#### E. 操作模式建模

在领域 Intent 之外增加 `QUERY / COMMAND / CONDITIONAL / AMBIGUOUS`，查询请求不能获得写工具，歧义请求必须澄清。

#### F. 有界复合任务

不需要立即建设通用 Planner。可先支持确定性的多领域动作拆分、逐项 Safety、执行顺序和 `PARTIAL` 结果；无法安全拆分时返回澄清。

#### G. 工具策略单一事实源

为每个工具统一声明领域、读写属性、风险、确认规则、状态前置条件、允许调用方和结果类型，并在启动时检查所有 ToolSpecification 均已覆盖。

#### H. 故障恢复语义

模型调用、只读查询和车辆写动作需要不同的重试策略：模型瞬时网络错误可以有限重试；只读工具可按幂等规则重试；写动作在没有 actionId 和状态查询前不能盲目重试。

### 7.3 提升工程可持续性

#### I. 收敛共享执行管线

先抽取“授权—Safety—Dispatch—ActionReceipt—Writeback—Trace”共享管线，再逐步让 TEXT、场景和其他输入类型复用。无需一次性合并全部 AgentLoop。

#### J. 将非关键副作用移出主响应路径

长期记忆提取、部分统计和低优先级持久化应使用有界后台任务，避免阻塞用户结果和全局执行槽。

#### K. 建立 Agent 行为评测体系

工程单元测试只能证明组件按预期运行，不能证明模型在不同表达下稳定完成任务。需要同时评估最终结果和执行轨迹。

---

## 8. Agent 行为评测建议

建议把每个 Eval Case 定义为：

```text
初始车辆状态
+ 用户 / Session / Persona
+ 用户请求
+ 允许工具和安全策略
+ 预期动作轨迹
+ 预期最终状态
+ 允许的最终答复语义
```

每个 Case 运行多次 Trial，因为模型输出具有随机性。评分应至少包括：

| 评测层 | 主要指标 |
|---|---|
| 任务结果 | 目标状态达成率、查询正确率、澄清正确率 |
| 决策轨迹 | Intent/ToolGroup 命中率、工具选择率、参数正确率、冗余调用率 |
| 动作真实性 | ActionReceipt 与最终答复一致率、虚假完成率、UNKNOWN 误报成功率 |
| 安全 | 未授权工具拦截率、高风险确认绕过率、状态前置条件拦截率 |
| Prompt Injection | 不可信 Context 诱导工具执行成功率，单次与重复攻击成功率 |
| 可靠性 | 超时残留调用、取消后动作数、重复执行率、最大迭代终止率 |
| 状态与记忆 | Session 隔离、用户隔离、ToolExchange 完整率、摘要事实保留率 |
| 性能成本 | P50/P95 延迟、模型轮数、Token、工具调用数、压缩触发率 |

最有价值的第一批场景不是海量聊天问题，而是 30～50 个高信息量车控用例：单动作、查询、歧义、多动作、状态冲突、高风险确认、取消竞态、超时、工具失败、提示注入和长对话。

---

## 9. 建议的演进路线

### 阶段一：动作可信闭环

目标：让系统能够证明“为什么允许执行、实际发生了什么、为什么可以这样回复”。

1. Dispatch 前工具授权白名单；
2. 统一 Tool Policy；
3. 结构化 ActionReceipt；
4. 最终答复结果守卫；
5. 为上述链路增加 Trace 和单元测试。

### 阶段二：身份与任务语义闭环

目标：让系统明确“谁请求、谁确认、请求是查询还是动作、多个动作如何处理”。

1. AIDL 调用方鉴权；
2. PendingAction 绑定完整主体；
3. QUERY/COMMAND/AMBIGUOUS；
4. 有界复合动作和 PARTIAL 结果；
5. 写动作幂等与状态查询。

### 阶段三：评测与可扩展闭环

目标：使 Agent 的模型升级、Prompt 修改和工具扩展都能被量化验证。

1. 建立固定 Eval Dataset；
2. 从 Trace 自动提取 trajectory；
3. 代码评分结果、状态、安全和性能；
4. 多 Trial 回归；
5. 再根据真实吞吐需求演进并发模型和统一其他输入控制面。

---

## 10. 项目设计特点总结

AIAgent 当前最有辨识度的设计特点不是“使用了 LangChain4j”，而是以下组合：

1. **Android 车载无 UI Agent 服务**：以长期运行的前台 Service 和 AIDL 作为产品边界；
2. **自研 Agent 控制面**：使用 LangChain4j 原语，但由 Runtime、Context 和 AgentLoop 掌握业务编排；
3. **确定性约束下的模型自治**：代码限定请求、工具域、安全和终态，模型只在有限空间内决策；
4. **Context 工程化**：11 个 Provider、12 类 Source Policy、逐轮动态重建、预算裁剪和 Memory 摘要恢复；
5. **领域化工具治理**：46 个 Tool、13 个 ToolGroup、反射注册分发和独立 Safety；
6. **完整状态体系**：Session、长期用户记忆、压缩、提取和虚拟车辆状态机；
7. **可靠性与观测前置**：Deadline、取消底层 Call、唯一终态和 OpenTelemetry/Phoenix 轨迹贯穿主链。

这使项目区别于普通“LLM + Function Calling”示例：它已经开始解决 Agent 工程中真正困难的问题——上下文所有权、工具范围、状态连续性、长任务终止、安全确认和非确定性执行的可观测性。

---

## 11. 最终判断

### 设计是否合理

合理。当前“确定性控制面 + 领域受限 Tool Loop”的路线比开放式通用 Agent 更符合车载控制的风险和任务结构。Context、请求生命周期、ToolGroup、Memory、Safety 和 Trace 的方向均正确。

### 架构是否过度设计

整体不算过度设计。Context、请求控制和 Safety 的复杂度都有真实业务理由；真正需要控制的是新旧两套循环和多输入控制面继续平行增长。后续应优先收敛共享执行语义，而不是继续新增横向抽象。

### 当前离完整 Agent 还有多远

- 从“能完成车载对话和 Demo 车控”的角度：主流程已经基本完整；
- 从“可稳定内部试用”的角度：需要补行为评测和部分任务语义；
- 从“可承担真实车辆写动作”的角度：仍缺执行授权、ActionReceipt、答复真实性和调用方身份四个硬边界。

### 最值得做的下一件事

不是新增 Planner、多 Agent 或更多工具，而是一次性打通：

```text
本轮工具授权
  → Safety
  → ActionReceipt
  → 状态验证
  → 最终答复真实性
```

完成这条链后，AIAgent 才会从“具备完整 Agent 结构”进一步变成“能够对动作结果负责的车载 Agent”。

---

## 12. 本报告的代码依据

本次评估主要沿以下主链文件核验，没有用计划文档替代当前实现：

- `app/src/main/java/com/hirain/aiagent/AIAgentService.kt`
- `app/src/main/java/com/hirain/aiagent/runtime/AgentRuntime.java`
- `app/src/main/java/com/hirain/aiagent/runtime/RequestSession.java`
- `app/src/main/java/com/hirain/aiagent/runtime/ActiveRequestRegistry.java`
- `app/src/main/java/com/hirain/aiagent/intentrouter/KeywordIntentRouter.java`
- `app/src/main/java/com/hirain/aiagent/toolgroup/DefaultToolGroupSelector.java`
- `app/src/main/java/com/hirain/aiagent/toolgroup/ToolGroupRegistry.java`
- `app/src/main/java/com/hirain/aiagent/context/ContextOrchestrator.java`
- `app/src/main/java/com/hirain/aiagent/context/ContextPolicies.java`
- `app/src/main/java/com/hirain/aiagent/context/ContextMessageAssembler.java`
- `app/src/main/java/com/hirain/aiagent/core/TextAgentLoopOrchestrator.java`
- `app/src/main/java/com/hirain/aiagent/safety/ToolSafetyEngine.java`
- `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java`
- `app/src/main/java/com/hirain/aiagent/ai/langchain4j/tool/ToolRegistry.java`
- `app/src/main/java/com/hirain/aiagent/ai/langchain4j/tool/ToolDispatcher.java`
- `app/src/main/java/com/hirain/aiagent/trace/TraceSession.java`
