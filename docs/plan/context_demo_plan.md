Phase 4 目标：基于当前已完成的 AgentRuntime、IntentRouter、ToolGroup、ConversationManager、Persona、ActiveRequestRegistry 架构，引入完整 Context 模块。Context 必须以 RequestSession 为核心输入，生成 ContextFrame，统一承载本轮 TEXT AgentLoop 的 runtime 元信息、usersessionpersonaclientMessageId、IntentResult、ToolGroupSelectionResult、selectedToolNames、memoryvehicletimeprompt 相关上下文摘要和 tracedebug 信息。本阶段优先以兼容模式接入现有 AgentLoop，不破坏已有 AIDL、会话管理、取消、persona、多用户记忆隔离和 ToolRegistry 行为。

需要实现：

1. 新增 context 包，至少包含：
   - ContextOrchestrator
   - ContextFrame
   - ContextFrameBuilder
   - ContextProvider
   - ContextProviderResult
   - ContextSection
   - ContextSectionType
   - ContextBuildResult
   - ContextBuildException
   - ContextBudgetManager
   - ContextDebugInfo
   - ContextTraceRecorder
   - providerRuntimeContextProvider
   - providerPersonaContextProvider
   - providerUserInputContextProvider
   - providerIntentContextProvider
   - providerToolGroupContextProvider
   - providerMemoryContextProvider
   - providerVehicleStateContextProvider
   - providerTimeContextProvider
   - providerPromptContextProvider

2. 新增 ContextMode，至少包含：
   - OBSERVE_ONLY
   - HYBRID_EXTRA_CONTEXT
   - FULL_CONTEXT
   第一版默认使用 HYBRID_EXTRA_CONTEXT。

3. ContextFrame 必须以 RequestSession 为核心输入，不允许重新生成 requestId、userId、sessionId、personaId、clientMessageId。

4. ContextFrame 至少包含：
   - requestId
   - clientMessageId
   - userId
   - sessionId
   - personaId
   - inputType
   - rawUserInput
   - normalizedUserInput
   - IntentResult
   - ToolGroupSelectionResult
   - selectedGroupIds
   - selectedToolNames
   - effectivePersonaId
   - memoryContext 或 memorySummary
   - vehicleContext 或 vehicleStateSnapshot
   - timeContext
   - renderedExtraContext
   - ContextMode
   - tokenEstimate
   - debugInfo

5. AgentRuntime 在 startSession 完成、RequestSession 创建完成之后，在 execute 之前调用 ContextOrchestrator.build(requestSession)。

6. AgentRuntime 执行链路调整为：
   startSession → IntentRouter → ToolGroupSelector → RequestSession → ContextOrchestrator.build → AgentExecutor.execute(session, contextFrame) → RuntimeResult。

7. AgentExecutor 需要新增兼容式输入方式，可以是：
   - execute(RequestSession session, ContextFrame contextFrame)
   或：
   - execute(AgentExecutionInput input)
   但必须保留旧接口或兼容旧实现，避免破坏现有 TEXT 链路。

8. 第一版 AgentExecutor 可以继续调用现有 AgentLoopOrchestrator.execute(userInput, extraContext)，其中 extraContext 使用 contextFrame.renderedExtraContext。

9. Context 第一版不要强制替换 AgentLoop 内部的 system prompt  MemoryPreProcessor  VehicleStatusPreProcessor  TimeContextPreProcessor。
   默认 HYBRID_EXTRA_CONTEXT 模式下，renderedExtraContext 主要包含：
   - runtime 元信息摘要
   - intent 摘要
   - toolgroup 摘要
   - selected tool descriptions
   - debug hints
   避免重复注入 system prompt、memory、vehicle、time。

10. PromptContextProvider 必须复用现有 PromptManager，不允许删除 PromptManager。
    第一版可以只记录 persona prompt source，不强制接管 system prompt 注入。

11. MemoryContextProvider 必须复用现有 MemoryOrchestrator  SessionChatMemoryProvider。
    不允许修改记忆数据库结构、SessionManager、SessionMemoryStore、MemoryCompressor、MemoryExtractor。
    不允许手动重复拼接完整短期 ChatMemory 历史。

12. PersonaContextProvider 必须使用 RequestSession 中的 personaId。
    不允许在 Context 中重新 normalize personaId。
    不允许改变 textOrchestrators[personaId] 的选择逻辑。

13. ToolGroupContextProvider 必须使用 ToolGroupSelectionResult 生成 selectedGroupIds、selectedToolNames、selected tool descriptions。
    ContextFrame 中 selectedToolNames 必须与 ToolGroupSelectionResult 保持一致。
    renderedExtraContext 中不得渲染全量 47 个工具说明，只能渲染 selectedToolNames 对应工具说明。

14. 如果当前 ModelCaller  LangChain4j 工具绑定支持动态工具集合，则尝试让本轮 LLM 只绑定 selectedToolNames。
    如果动态绑定改动较大，本阶段至少保证 prompt  extraContext 只出现 selected tools，真实 dispatch 仍保持不变。不要为此重构整个 ModelCaller。

15. ContextBudgetManager 第一版只做粗粒度预算：
    - 限制 context section 字符长度
    - 限制 memory 摘要长度
    - 限制 selected tool descriptions 长度
    - 不实现复杂 tokenizer
    - 不调用 LLM 做压缩

16. ContextTraceRecorder 或 TraceManager 需要新增记录：
    - agent.context.enabled
    - agent.context.mode
    - agent.context.provider_count
    - agent.context.providers
    - agent.context.selected_tool_count
    - agent.context.selected_tool_names
    - agent.context.section_count
    - agent.context.token_estimate
    - agent.context.fallback_used
    - agent.context.build_ms
    - agent.context.error

17. Context 构建前后应配合 ActiveRequestRegistry 检查取消状态。
    如果请求已取消，不应继续进入 AgentLoop。
    Context 构建失败应封装为 RuntimeResult.failed 或降级到兼容 extraContext，不能绕过现有终态抢占机制。

18. ContextProvider 失败时必须可诊断：
    - 记录 provider 名称
    - 记录错误原因
    - 写入 debugInfo  Trace
    - 尽量 fallback，不允许无诊断崩溃

边界要求：

1. 本阶段不修改 AIDL 接口。
2. 本阶段不修改 AgentRequest  AgentResponse Parcelable 字段。
3. 本阶段不修改 ConversationManager 的 createlistswitchdeletegetActive 行为。
4. 本阶段不修改 ActiveRequestRegistry 的 CAS 终态抢占机制。
5. 本阶段不修改 IntentRouter 关键词规则。
6. 本阶段不修改 ToolGroupSelector 映射规则。
7. 本阶段不修改 ToolRegistry  ToolDispatcher 的真实 dispatch 机制。
8. 本阶段不修改 VehicleStateMachine 行为。
9. 本阶段不引入 PolicyEngine。
10. 本阶段不引入 Task  Skill。
11. 本阶段不重构 IMAGE  CONTROL 链路。
12. 本阶段不强制迁移 VOICE 旧链路。
13. 本阶段不调用 LLM 构建 Context。
14. 本阶段不重复注入 system prompt、persona prompt、memory、vehicle state、time context。
15. 本阶段不得破坏 chatfriendlyconcise 三种 TEXT persona 的选择与 ChatMemory 隔离。
16. 本阶段不得破坏 userId + sessionId + personaId 的短期记忆隔离。
17. 本阶段不得破坏 cancelAgentRequest、timeout、late result suppression。

验收标准：

1. TEXT 请求仍能正常执行。
2. chatfriendlyconcise 三种 persona 均能正常执行。
3. userId、sessionId、personaId、clientMessageId 能正确进入 ContextFrame。
4. 会话切换后 ContextFrame 中 sessionId 正确变化。
5. cancelAgentRequest 仍能取消运行中请求。
6. timeout 仍然只发送一次最终响应。
7. VOICE  IMAGE  CONTROL 行为不被破坏。
8. AgentRuntime 在 AgentLoop 前生成 ContextFrame。
9. ContextFrame 包含 RequestSession 的关键元信息。
10. ContextFrame 包含 IntentResult。
11. ContextFrame 包含 ToolGroupSelectionResult。
12. ContextFrame 包含 selectedToolNames。
13. ContextFrame 包含 ContextMode。
14. 空调请求 selectedToolNames 只包含 AC_GROUP + BASIC_STATUS_GROUP 相关工具。
15. 车窗请求 selectedToolNames 只包含 WINDOW_GROUP + BASIC_STATUS_GROUP 相关工具。
16. 座椅请求 selectedToolNames 只包含 SEAT_GROUP + BASIC_STATUS_GROUP 相关工具。
17. 普通聊天 selectedToolNames 不应是全量 47 个工具。
18. renderedExtraContext 不应出现全量工具说明。
19. system prompt 不重复注入。
20. persona prompt 不重复注入。
21. memory 不重复注入。
22. vehicle state 不重复注入。
23. time context 不重复注入。
24. Trace 中可见 context.enabled  context.mode  selected_tool_count  build_ms  fallback_used。
25. ContextProvider 异常时可 fallback 或返回可诊断失败，不允许无信息崩溃。