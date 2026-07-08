# Context 模块引入总结

## 我们做了什么

这次我们给 AIAgent 引入了一个全新的 **Context 上下文管理模块**。简单来说，之前 Agent 每次处理用户的 TEXT 请求时，各种上下文信息（用户的 ID、当前意图、可用的工具、车辆状态、时间、历史记忆）散落在不同地方，没有统一的管理和观测手段。这次我们做了一个"上下文管家"——从请求进来开始，把所有相关信息收集、整理、打包，再送给大模型。

### 为什么要做这件事

之前 TEXT 请求的流程是这样的：Service 收到请求 → 创建 RequestSession → 直接调 AgentLoopOrchestrator 去跟 LLM 对话。中间缺了一个环节：**把"当前请求是谁发的、他想干什么、有什么工具可用"这些信息系统性地组织起来再送给模型**。这导致几个问题：

1. 排查问题时，不知道某次请求到底匹配了什么意图、选中了哪些工具
2. 后续如果要给模型注入更多上下文（比如车辆状态、长期记忆），需要在多个地方改代码
3. 缺少统一的观测数据，Trace 里看不到完整的上下文信息

Context 模块就是为了解决这些问题——在 RequestSession 和 AgentLoop 之间加了一层，把所有上下文集中管理。

## 整体架构

新链路变成了这样：

```
用户请求 → AgentRuntime.startSession() → RequestSession
                                        → AgentRuntime.execute()
                                            → ContextOrchestrator.build() → ContextFrame
                                            → 取消检查
                                            → AgentLoopOrchestrator（真正调 LLM）
```

中间的 ContextOrchestrator 就像一条流水线：9 个工人（Provider）各司其职，有人负责拿用户 ID，有人负责判断意图，有人负责查工具列表，最后组装成一个 ContextFrame 数据包。

这个数据包里的信息分成两类：一类是**要给 LLM 看的**，比如当前用户的身份、识别到的意图（"他想开空调"）、可以用的工具（"set_ac_status"）；另一类是**只给自己看的**，比如车辆状态快照、当前时间——这些已经有其他模块在负责注入了，我们不再重复塞给模型，只记录做观测用。

## 分阶段做了什么

### Phase 1：搭数据模型（基础类型）

这一阶段没有业务逻辑，就是先把"上下文"长什么样定义清楚。我们定义了：

- **ContextMode**：三种模式——纯观测（OBSERVE_ONLY）、一期混合模式（HYBRID_EXTRA_CONTEXT，只注部分上下文）、完整模式（FULL_CONTEXT，一期暂不启用）
- **ContextSection**：每一段上下文的最小单元，比如"运行时信息"是一段、"意图识别结果"是一段
- **ContextFrame**：最终的上下文数据包，把所有段打包在一起
- **ContextBudgetManager**：预算管理，防止上下文太长把模型输入窗口撑爆

这些类型都是不可变的——一旦创建就不能修改，保证数据安全。

### Phase 2：9 个上下文采集员（Provider）

这个阶段创建了 9 个 Provider，每个负责采集一类信息：

| Provider | 采什么 | 是否给 LLM 看 |
|----------|--------|-------------|
| RuntimeContextProvider | requestId、userId、sessionId 等运行时信息 | 是 |
| PersonaContextProvider | 当前请求用的是什么人格（chat/friendly/concise） | 是 |
| UserInputContextProvider | 用户说了什么（"打开空调"） | 否（避免重复注入） |
| IntentContextProvider | 意图识别结果（"他想操控空调"） | 是 |
| ToolGroupContextProvider | 当前可以调用的工具有哪些 | 是 |
| MemoryContextProvider | 长期记忆的归属信息 | 否（已有专门的 memory 模块处理） |
| VehicleStateContextProvider | 车辆当前状态快照 | 否（已有 VehicleStatusPreProcessor） |
| TimeContextProvider | 当前时间 | 否（已有 TimeContextPreProcessor） |
| PromptContextProvider | 当前用的是什么系统提示词模板 | 否（已有 injectSystemPrompt） |

其中 memory、vehicle、time、prompt 这四个在 HYBRID 模式下不注入给模型，因为已经有其他组件在处理了。我们只记录它们的数据用于观测和调试，避免重复。

这个阶段还做了一个重构：把系统中散落的 TEXT persona 到 Prompt 模板的映射关系集中到了 `PromptConstants.textPersonaTemplateName()` 方法里。以后新增 persona 只需改这一个地方。

### Phase 3：组装流水线（ContextOrchestrator）

把 Phase 2 的 9 个 Provider 串起来，形成一条流水线。ContextOrchestrator 做的事情：

1. 按固定顺序跑 9 个 Provider
2. 每个 Provider 出错了不影响其他的（try/catch 保护）
3. 根据模式（当前是 HYBRID）决定哪些段要拼接给 LLM
4. 把所有信息打包成 ContextFrame
5. 如果发生了降级（比如某个 Provider 没跑成功），记录到调试信息里

关键设计：**任何 Provider 失败都不会让整个请求崩溃**。即使 9 个全挂了，也会返回一个空的 ContextFrame，下游的 AgentLoop 照常执行——只是模型看不到额外的上下文信息。

### Phase 4：接入运行时

这是工作量最大的阶段，把 Context 模块真正接入到现有请求链路中。

**改了四个地方：**

1. **AgentExecutor**：新增了一个默认方法 `execute(session, contextFrame)`，这样上层可以直接把 ContextFrame 传进去，而不需要手动拆包塞 Map。

2. **AgentRuntime**：重构了构造函数的体系（从 5 个变成 8 个），新增了两个核心字段：`ContextOrchestrator` 和 `RuntimeCancelChecker`。`execute()` 方法改为三步走：**先构建上下文 → 检查是否被取消 → 再调 LLM**。如果上下文构建完成后用户取消了请求，就不调 LLM，直接返回取消结果。

3. **ContextExtraPreProcessor**：新写了一个预处理器，放在 preprocessor 链的首位。它的作用是把 `context_rendered_extra` 文本注入到 LLM 收到的消息中。只在首轮、HYBRID 模式下生效。

4. **AIAgentService**：在 Service 初始化时创建 ContextOrchestrator，并注入到 AgentRuntime 的构造函数中。

### Phase 5：加 Trace 观测和取消保护

做了两件事：

**Trace 观测**：在 ContextOrchestrator 构建完上下文后，把 11 个指标写入 OpenTelemetry Trace 的 root span，包括：是否启用、什么模式、有几个 Provider、选了多少工具、构建耗时、有没有降级错误等。这样在 Phoenix 面板上可以直接看到每次请求的上下文构建情况。

**取消保护**：在 Service 层（TEXT worker）增加了一处取消检查——在 `execute()` 返回之后、`tryComplete` 之前。这样即使 Runtime 内部的取消检查没拦截住（比如取消信号刚好在 execute 返回后到达），也能保证不会发送 late success 响应。

### Phase 6：加验收测试

追加了 7 个测试，覆盖：ContextFrame 中的用户信息透传、意图/工具组数据正确性、renderedExtraContext 不重复注入、以及四个典型请求场景（空调/车窗/座椅/闲聊）的工具选择验证。

## 没有做进本期的功能

- **完整工具描述渲染**：一期只把工具名（如 set_ac_status）传给模型，不带完整的参数说明和 schema。这是因为如果要渲染完整描述，需要把 ToolRegistry 或 ToolSpecification 列表传进 ContextBuildInput，改动范围较大，留给后续。

- **FULL_CONTEXT 模式**：代码中已有这个枚举，但行为降级成了 HYBRID。以后如果想启用完整模式（memory/vehicle/time/prompt 也注入模型），只需在 Service 初始化时改一下 ContextMode。

- **动态工具绑定**：当前 AgentLoopOrchestrator 在构造时就确定了模型可见的工具列表，做不到每次请求动态调整。这是一个更大的课题，不在本期范围内。

## 自动化测试结果

全部在 2026-07-07 执行通过：

- Context 模块单测：BUILD SUCCESSFUL
- Runtime 单测：BUILD SUCCESSFUL
- Trace 回归测试：BUILD SUCCESSFUL
- **全量 JVM 单测：BUILD SUCCESSFUL（44 个测试）**

## 后续建议

1. **完整工具渲染**：评估是否把 `ToolRegistry` 或 `List<ToolSpecification>` 纳入 ContextBuildInput，让模型看到的工具说明更完整
2. **FULL_CONTEXT**：在 vehicle/time/memory 的 preprocessor 全部迁移到 Context 模块后，切换为 FULL_CONTEXT 模式
3. **动态工具绑定**：设计 per-request 的 tool spec 传递机制，实现每个请求的工具白名单动态调整
