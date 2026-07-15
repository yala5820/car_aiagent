# Context 模块设备部分验收记录

## 一、验收范围

本记录对应 `docs/plan_overall/2026-07-13-context-remaining-13-issues-closure-plan.md` 的设备验收部分。目标是区分三类结论：已经由真实 Android 运行链路证明的能力、仅由 JVM 自动化测试证明的能力，以及当前环境无法完成的外部验收项。

未完成项目的逐项操作、预期结果和取证要求见 [Context 模块剩余人工验收清单](2026-07-15-context-manual-acceptance-checklist.md)。

本次环境为 Android Automotive x86_64 模拟器，AIAgent 通过前台 Service 运行，外部 `com.hirain.aiagent.test` TestApp 通过 AIDL 调用。模型请求实际到达 DashScope OpenAI 兼容接口并返回 HTTP 200。

## 二、已通过项目

| 验收项 | 结果 | 证据与结论 |
|--------|------|------------|
| Service 与 AIDL 链路 | 通过 | AIAgent 前台 Service 正常运行，TestApp 成功绑定并调用 `processAgentRequest` |
| 真实 TEXT 请求 | 通过 | 请求经过 Runtime、Context 和 DashScope，模型回复成功返回调用方 |
| 同 Session 短期记忆 | 通过 | 先告知姓名 Alice，再询问姓名，模型能够准确召回 |
| 新 Session 隔离 | 通过 | 新建 Session 后询问上一问题，模型回答没有此前问题；新旧 Session 分别落库 |
| 不同用户长期记忆隔离 | 通过 | 切换至 `test_user_1` 后询问姓名，未返回默认用户的 Alice 记忆 |
| SQLite 超过 50 条历史 | 通过 | Session `S_20260630_162202` 达到 52 条消息时，序列错误为 0，首条和第 52 条均保留；后续继续增长至至少 58 条 |
| 连续串行对话 | 通过 | 采用每次落库后等待 8 秒的节奏，连续 13 轮请求均完成 |

SQLite 52 条验收的核心结论：

- `total = 52`
- `sequence_errors = 0`
- 最早一条消息仍然存在，没有发生旧版固定 50 条头部淘汰
- 最新一条消息存在，USER/AI 顺序保持有效

## 三、未完成或证据不足项目

| 验收项 | 当前状态 | 原因或所需条件 |
|--------|----------|----------------|
| 持久化 ToolExchange | 未证明 | 测试提示词只得到普通 AI 文本，SQLite 未出现真实 Tool Call/Tool Result，不能把模型口头确认视为工具执行证据 |
| 超长 ToolExchange 跨 50 边界 | 未验证 | 需要模型真实触发工具并形成持久化工具交换后再构造长历史 |
| 真实超预算压缩 | 未验证 | 当前 52 条消息 token 量仍未超过 Context 预算，未触发真实摘要模型和压缩重装配 |
| allToolsFallback 设备链路 | 未验证 | 默认 Selector 当前不会主动产生该结果；Runtime/Context 能力已有 JVM 测试 |
| 工具参数错误与 Safety 拒绝 | 未验证 | 需要稳定触发真实工具调用和对应异常/拒绝路径 |
| 同 sessionId 切换 user | 未验证 | 当前 TestApp 切换用户时会清空会话选择，无法从 UI 把原 sessionId 传给新用户；代码语义由 JVM 测试覆盖 |
| 快速请求终态 | 部分异常 | 约 2 秒后发送下一请求时，TestApp 会取消前一请求，出现“请求已取消”和 MemoryExtractor `IOException: Canceled`；需调用方与服务端共同确认终态协议 |
| Qwen token 估算校准 | 未验证 | 需要至少 10 个真实请求的 estimated/actual token 样本 |
| Phoenix Trace 展示 | 阻塞 | 本机未安装 Phoenix/Docker，6006 端口无服务；设备日志明确报告连接 `localhost:6006` 失败 |
| 目标车机与真车 SOA | 未验证 | 当前只有 Automotive 模拟器，不能替代目标硬件和真实车辆执行回执 |

## 四、环境限制

模拟器中已有兼容版本 TestApp 可正常运行；尝试安装当前 TestApp APK 时因原生库 ABI 与 x86_64 不匹配而失败，因此本轮使用模拟器中已有版本。该限制不影响已记录的 AIDL、TEXT 和 SQLite 证据，但限制了同 sessionId 换 user 等需要专用客户端参数控制的场景。

Phoenix 本机服务不可用，OTLP Span 已在应用侧生成并尝试导出，但无法检查 Phoenix 中的完整消息、工具 schema、预算字段、压缩字段和父子 Span 关系。

## 五、验收结论

Context 的生产 TEXT 链路已经能够在 Android 运行环境中正常工作，基础会话记忆、Session 隔离、用户长期记忆隔离以及超过 50 条的 SQLite 历史均获得设备证据。该结论支持“代码改造完成、基础设备验收通过”，但不支持“全部设备验收完成”。

计划最终关闭仍依赖目标车机或具备参数控制能力的专用 AIDL 客户端，以及可用的 Phoenix 服务。重点补验项目是持久化 ToolExchange、真实超预算压缩、同 sessionId 换 user、快速请求终态、Qwen token 校准和完整 Trace 展示。
