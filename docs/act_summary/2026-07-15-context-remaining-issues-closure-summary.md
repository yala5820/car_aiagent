# Context 剩余问题收口执行总结

**执行日期：** 2026-07-15

**对应计划：** `docs/plan_overall/2026-07-13-context-remaining-13-issues-closure-plan.md`

## 一、工作目标

本轮目标是一次性收口 Context overview 中汇总的 13 类遗留问题，使 TEXT Context 从“完成统一装配但长会话和诊断仍有缺口”推进到“代码闭环并通过自动化门禁”。

工作边界保持不变：不修改 AIDL 和外部 App 接口，不修改 Prompt 文案，不修改 ToolGroupSelector 匹配规则，不升级依赖，不迁移 VOICE/Scene/VLM，不重构无关 Runtime/Safety 代码。

## 二、完成内容

### 2.1 Session 历史完整性

- 新增 `PersistentSessionChatMemory`，取消单个会话固定消息数淘汰。
- Session Store 和 Context 快照保留完整历史，不再在第 50 条边界切断 turn 或 ToolExchange。
- 新增 `SessionHistorySequenceValidator`，精确校验 Tool Call ID、工具名、重复 ID 和中间损坏。
- 只修复进程中断形成的尾部未闭合 turn；中间损坏硬失败。
- 新增 compare-and-set 替换能力，旧压缩计划不得覆盖并发新消息。

### 2.2 Provider、Tool 和写回语义

- LongTermMemory 未配置或读取异常时真实返回 FALLBACK，不再误报 SUCCESS。
- ToolContribution 正确区分 NONE、SELECTED 和 ALL_FALLBACK。
- Runtime 允许结构合法的 Demo allToolsFallback 进入 Context，普通聚合组仍失败关闭。
- 新增 `ToolDispatchOutcome`，TEXT 工具链使用结构化 registered/parse/invoke/dispatch 状态。
- ToolDispatcher 移除 JVM 不可用的 Android Log 依赖，并使用 Gson 解析参数。
- 普通模型文本只把 PostProcessor 后的最终版本写入 SessionMemory，保证下一轮历史与外部用户实际收到的回答一致。

### 2.3 集中 ContextPolicy

- 新增 `ContextPolicies`、`ContextSourcePolicy` 和 `ResolvedContextPolicy`。
- 统一管理所有生产 source 的 lifecycle、required、visibility、trust、priority 和 trim eligibility。
- Provider 和 Contribution 从同一个 resolved policy 读取属性。
- ContextOrchestrator 对生产 Provider/Contribution 做运行时一致性校验，未知 sourceKey 或属性漂移直接失败。

### 2.4 预算裁剪与真实 Memory 压缩

- 新增 `ContextAssemblyDraft`、`ContextAssemblyAttempt` 和 `ContextContributionDecision`。
- ContextMessageAssembler 先按 OPTIONAL、NORMAL、HIGH 删除允许裁剪的 optional Context Data。
- Prompt、CURRENT_USER、SessionMemory、摘要、required 数据和工具规格不可普通裁剪。
- 仍超限时由 Context 计算 SessionMemory 目标 token，并调用 MemoryOrchestrator 生成无副作用压缩计划。
- Memory 按完整 turn 划分可压缩历史，至少保护最近 2 轮。
- MemoryCompressor 调用摘要模型，将旧摘要和旧历史合并为单一摘要。
- 候选满足目标且产生预算收益后才 CAS 写回；STALE_PLAN、无收益、模型失败和取消均不覆盖历史。
- 压缩后重新运行动态 Provider 并二次装配；同一 Agent 请求跨全部 iteration 最多压缩一次。
- 摘要模型尚未真正启动时不消耗 compressionAttempted 状态。

### 2.5 Token 与 Trace

- HeuristicContextTokenEstimator 覆盖所有消息正文、Tool Call、Tool Result 和完整工具 schema。
- Trace 增加 estimated input、actual input、delta、ratio 和 usage available 字段。
- Demo FULL_DEBUG 直接写入完整文本、参数和结果，不进行业务层截断。
- Provider span 使用 produced 语义，最终 fragment/message/toolset 使用 included/trimmed 语义。
- 修复空 Message/Tool Contribution 被误报为模型可见产出的问题。
- 修复 iteration 1+ CURRENT_USER 虽未进入 ChatRequest 却被 Trace 标记 included 的问题。
- 预算 Trace 包含 before/after、TrimAction、压缩推荐、执行结果、snapshotMatch 和 reload 状态。

### 2.6 Legacy 清理

- 删除 `ContextSection`、`ContextSectionType` 和 `ContextDebugInfo`。
- ContextFrame 删除 sections、旧 debug、旧 token 和各类旧字符串字段，只保留请求事实与 Contributions。
- ContextBudgetManager 收敛为结构化 Contribution 裁剪资格工具。
- 删除 ContextTraceRecorder 无调用的 root/event/assembled 兼容 API 和对应旧测试。
- 清理 Context/Memory/Core 中失效的 Phase、shadow 和 hybrid 注释。
- 保留仍由非 TEXT 路径使用的旧 AgentLoop、PreProcessor 和相关配置。

## 三、验收结果

### 3.1 自动化门禁

最终源码执行结果：

| 门禁 | 结果 |
|---|---|
| `:app:testDebugUnitTest --rerun-tasks` | BUILD SUCCESSFUL |
| JVM 测试统计 | 62 个测试类，354 tests，0 failures，0 errors，0 skipped |
| `:app:assembleDebug` | BUILD SUCCESSFUL |
| `:app:lintDebug` | BUILD SUCCESSFUL |
| `git diff --check` | 无空白错误 |

Lint 首次执行发现 `AgentRuntime` 使用 API 34 才可用的 `Stream.toList()`，已改为 API 33 兼容的 `Collectors.toList()` 后通过。

### 3.2 重点回归

- 100 条 Session 历史不丢失。
- ToolExchange 尾部修复、中间损坏拒绝和 stale CAS。
- CHAT_ONLY、明确工具和 allToolsFallback 真实 Runtime/Context/Assembler 链。
- optional 裁剪、required 保留、工具 schema 超限。
- 压缩成功、无可压缩 turn、摘要替换、一次限制和重装配。
- Tool 参数失败、未注册、invoke 失败和结构化 Trace。
- PostProcessor 最终文本写回。
- FULL_DEBUG 超 10KB 内容、Provider produced 和最终 included 准确性。
- Token 估算单调性和实际 usage 误差字段。

## 四、设备级验收进展与未完成项

已启动 Android Automotive x86_64 模拟器并安装本轮 `app-debug.apk`。模拟器中已有的 TestApp 通过 AIDL 成功绑定 `AIAgentService`，实际 TEXT 请求经 Service、Runtime、Context、DashScope 和响应回调完整返回；DashScope 主对话请求返回 HTTP 200。设备端 `aiagent_memory.db/session_messages` 已确认保存 52 条消息，USER/AI 交替序列错误为 0；第 0 条旧消息与第 51 条最新回复同时存在，证明生产链已越过旧的 50 条边界且没有头部淘汰。

本次设备级冒烟通过项：

- AIAgent 前台 Service 启动并保持运行。
- 外部 TestApp 与 Service 的 AIDL 绑定成功。
- 基础 TEXT 请求获得非空模型回复。
- 同一 Session 13 轮节奏受控的连续请求全部进入真实 Runtime，并落入 SQLite。
- 先声明 `My name is Alice`，下一轮询问 `What is my name`，模型准确召回 Alice。
- 空调温度请求经确认后更新为 24 摄氏度，后续状态查询返回 24 摄氏度。
- 新建 Session 后询问上一问题，模型回答当前新会话没有上一问题，SQLite 使用独立 memoryId 保存 2 条消息。
- 切换到 `test_user_1` 并新建会话后询问姓名，不再返回 `default_user` 的 Alice，长期用户记忆隔离通过冒烟。
- Trace span 已生成并尝试通过 OTLP HTTP 导出。

仍未满足计划 Task 3.5 的完整验收条件：

- 车机连续 10 轮普通对话和回复质量。
- 包含持久化 ToolExchange 的超过 50 条历史；当前已验证 52 条 USER/AI 历史不截断，但工具中间消息没有作为 Session 历史持久化。
- 真实 Qwen 摘要模型触发、失败和取消。
- 真实 allToolsFallback、工具异常和 Safety veto。
- 同一 sessionId 切换 user 后保留短期历史并切换长期记忆；当前 TestApp 切用户时会清空会话选择，无法通过 UI 传回原 sessionId。新 Session 隔离和不同用户长期记忆隔离已通过。
- 至少 10 个真实请求的 estimated/actual token 校准。
- Phoenix 中完整消息、工具 schema、预算、压缩和 Trace 父子关系展示。本机未安装 Phoenix/Docker，6006 端口没有服务，设备日志明确报告 `Failed to connect to localhost/127.0.0.1:6006`。

Automotive 模拟器补齐了基础设备链路证据，但不能替代目标车机的长会话、工具、取消竞争和 Phoenix 展示验收。当前 TestApp 新 APK 还因原生库 ABI 不匹配无法重装到 x86_64 模拟器，本次使用的是模拟器中已有的兼容版本。

## 五、当前遗留风险

1. 启发式 Token 估算尚未获得真实 Qwen usage 校准，可能需要调整 Profile 安全系数。
2. Phoenix/exporter 是否限制超长 attribute 尚无设备证据；业务代码本身已取消截断。
3. 压缩摘要仍以头部 `【对话摘要】` UserMessage 持久化，未来可迁移为 SQLite 结构化字段。
4. DefaultToolGroupSelector 当前不主动产生 allToolsFallback；Runtime/Context 能力已就绪，生产是否触发取决于后续 ToolGroup 策略决定。
5. Context 只独占 TEXT 输入，VOICE/Scene/VLM 仍保留旧链。
6. AndroidManifest 重复权限和 deprecated API warning 为既存工程问题，本轮未修改。
7. 设备压力冒烟中，若仅以 Session 消息落库作为下一请求发送条件，约 2 秒内快速发送会抢占上一请求的 MemoryExtractor，并让 TestApp 显示“请求已取消”；每轮等待终态后连续 13 轮则全部成功。需在目标车机验收时确认客户端应依据哪一个终态信号放行下一请求。

## 六、最终结论

计划 Phase 1、Phase 2 和 Phase 3 的代码实现、自动化测试、APK 构建、Lint 和文档更新已经完成。TEXT Context 已形成“统一采集、集中策略、唯一装配、预算裁剪、Memory 压缩恢复、消息校验和完整 Trace”的闭环。

整个计划尚不能标记为设备验收完成。下一步需要在目标车机执行 Task 3.5 的长会话、工具、切换和取消清单，同时启动 Phoenix 完成 Trace 展示检查，并根据真实 Qwen token 数据做小范围 Profile 校准；当前没有理由再次大规模重写 Context 架构。
