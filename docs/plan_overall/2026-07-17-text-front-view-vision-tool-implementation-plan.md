# AIAgent TEXT 前向视觉问答 Tool 详细实施计划

> **执行方式：** 本文档用于后续子 Agent 在 Goal 模式下逐项实施。执行者必须严格按 Phase 和 Task 顺序推进，完成每个 Phase 的测试门槛后才能进入下一阶段。
>
> **当前状态：** 仅完成技术路线讨论与计划编写，尚未开始业务代码实现。
>
> **日期：** 2026-07-17  
> **项目阶段：** Demo  
> **主链范围：** `TEXT -> AIAgentService -> AgentRuntime -> ContextOrchestrator -> TextAgentLoopOrchestrator -> ToolRegistry`

---

## 1. 工作目标

本计划要在不接入真实摄像头的前提下，为当前 TEXT Agent 主链增加一条可验证的前向视觉问答能力：

```text
用户发送 TEXT：“前方是什么？”
  -> 视觉意图策略判定
  -> Context 只暴露前向视觉 Tool
  -> 主模型调用 front_camera_interaction
  -> Tool 根据配置选择一张 assets 测试图片
  -> qwen-vl-max 分析图片并返回结构化视觉证据
  -> Tool 结果写回当前 Agent Loop
  -> qwen-turbo 依据视觉证据生成最终回答
  -> AgentResponse 返回调用方
```

计划完成后，系统必须具备以下能力：

1. Agent 业务能力统一从 TEXT 请求进入当前 Runtime / Context / TextAgentLoop 主链。
2. 前向视觉问答作为 Tool，而不是 Skill、Subagent 或新的独立 Agent 入口。
3. Demo 图片通过 assets 配置文件管理，可以按图片 ID 切换测试图片。
4. 只有 Debug 构建中持有有效 Eval permit 的请求，才可以通过 `extraContext["vision_demo_image_id"]` 覆盖默认图片；普通 Debug 调用方和全部 Release 请求均忽略该覆盖值。
5. 当前仓库没有准备好正式图片时，Service 仍可启动、构建和通过自动化测试；真实视觉请求返回明确的“测试图片尚未配置”，不得静默使用其他图片。
6. 明确视觉请求使用 60 秒端到端绝对期限；普通 TEXT 和确认请求继续使用 30 秒。
7. 视觉 Tool 内部的 VLM HTTP Call 复用现有 `RequestCallRegistry` 和 `RequestExecutionContext`，支持超时和用户取消；VLM 使用执行上下文中的原始用户问题，不把模型生成的 Tool 参数当成可信问题来源。
8. `VisionIntentPolicy` 输出 `REQUIRED / OPTIONAL / NONE`，不再让简陋的关键词 Router 单独决定是否强制执行视觉 Tool。
9. `REQUIRED` 请求必须取得成功的视觉 Tool 证据才能返回视觉结论；没有 Tool Call、图片不可用、VLM 失败、超时或取消时均不得伪装成功。
10. 图片字节和 Base64 不进入 SessionMemory、LongTermMemory、日志或 Trace；仅保存结构化视觉摘要和低敏元数据。所有视觉轮次均跳过 LongTermMemory 提取，避免把一次性道路画面固化为用户偏好。
11. 旧 `IMAGE`、`VOICE` 业务入口停止执行并返回结构化不支持响应，但暂时保留 `AgentRequest` 中的兼容字段。
12. `CONTROL` 继续作为 Service 控制通道保留，主动场景感知链、Camera 定时采集链和相关 TTS 暂不重构。

---

## 2. 已确认的设计决策

以下内容已经在计划编写前由用户确认，实施过程中不得自行反向修改：

### 2.1 输入入口

- `TEXT` 是唯一进入 AgentRuntime / Context / AgentLoop 的业务入口。
- `CONTROL` 保留，不迁移进 AgentRuntime：
  - `StartListen`
  - `StopListen`
  - `ClearChatMemory`
- `IMAGE`、`VOICE` 停止执行业务链，但本轮不删除 Parcelable/AIDL 字段。
- 未知 `inputType` 与 IMAGE/VOICE 一样返回结构化 `UNSUPPORTED_INPUT_TYPE`，不能只写日志后无响应。

### 2.2 视觉能力形态

- 前向视觉问答是一个复合 Tool。
- Tool 内部组合“测试图片获取 + VLM 分析 + 结构化结果”，但不具备独立规划、会话或 Agent 身份。
- 不新增 Skill。
- 不新增 Subagent。
- 不新增独立 LLM IntentRouter。

### 2.3 Demo 图片

- 本轮只实现 Demo assets 图片，不实现 CameraSdk 按需取帧。
- 图片通过白名单配置管理，不允许调用方传绝对路径或任意文件路径；配置声明的 MIME 必须与图片文件签名一致。
- 当前正式图片数据尚未确定，本轮只创建空配置、目录说明和代码能力，不提交占位二进制图片。
- 现有 `assets/documents/audi.jpg` 属于旧链路遗留资产，本轮不得把它继续作为静默默认图，也不得未经用户确认删除或迁移。
- 图片未配置时必须返回结构化失败，不影响 Service 启动。

### 2.4 模型链

```text
qwen-turbo：判断/调用 Tool
qwen-vl-max：分析测试图片，产出结构化视觉证据
qwen-turbo：依据 Tool 证据生成最终自然语言回答
```

- VLM 不是第二个 Agent。
- VLM 不读取 SessionMemory，不自行管理多轮会话。
- 主模型不得添加 VLM 证据中不存在的物体、距离、速度或危险判断。
- Tool 失败时不允许主模型自由生成视觉答案。

### 2.5 路由和强制执行

- 现有 `KeywordIntentRouter` 保留，继续作为粗粒度候选路由层。
- 新增独立的 `VisionIntentPolicy`：
  - `REQUIRED`：明确要求观察前方，必须调用视觉 Tool。
  - `OPTIONAL`：可能需要视觉能力，向主模型开放 Tool，但由模型决定是否调用。
  - `NONE`：普通聊天或泛化图片讨论，不开放视觉 Tool。
- 只有 `REQUIRED` 才触发硬证据约束。
- 当前 LangChain4j 1.16.3 已确认支持 `ChatRequest.Builder.toolChoice(ToolChoice.REQUIRED)`；实施时优先使用原生 Tool Choice，不通过 Prompt 假装强制。
- 如果模型在 `REQUIRED` + `ToolChoice.REQUIRED` 下仍返回普通文本，允许防御性重试一次；第二次仍无 Tool Call 时失败关闭。

### 2.6 Deadline

- 普通 TEXT：30 秒。
- 文本确认请求：30 秒。
- 实际暴露视觉 Tool 的 TEXT 请求：60 秒。
- Service、Runtime、Context、Loop、主模型 HTTP、VLM HTTP 必须使用同一个绝对 deadline，不能分别重新计时。

### 2.7 隐私、记忆和 Trace

- 禁止记录图片 Base64。
- 禁止把图片字节写入 SQLite Memory。
- 禁止把图片写到临时文件或外部存储。
- 允许记录：图片 ID、来源、MIME、字节数、加载时间、耗时、结果状态。
- SessionMemory 保存用户问题、结构化 Tool 结果和最终回答；Tool 结果只包含文本证据和元数据，不含图片。
- 可恢复的确定性视觉失败必须写入安全的失败 AssistantMessage 以闭合本轮 SessionMemory；timeout/cancel 不伪造完成消息，继续由现有历史序列修复策略处理可能的部分交换。
- REQUIRED 无 Tool、错误 Tool 或重复 Tool Call 时，必须为模型已经声明的每个 Tool Call 写入“未执行”ToolResult 后再结束，不能留下孤立 ToolCall AiMessage。
- REQUIRED/OPTIONAL 只要进入视觉策略或执行过视觉 Tool，本轮就跳过 LongTermMemory 提取；不得把单次道路画面当作用户长期偏好。

---

## 3. 架构设计与最终调用时序

### 3.1 目标分层

```text
AIAgentService
  |- TEXT / CONTROL / UNSUPPORTED 入口兼容路由
  |- ActiveRequestRegistry 准入与唯一终态响应
  `- 绑定有效 RequestDeadline / RequestExecutionContext

AgentRuntime
  |- KeywordIntentRouter：粗粒度候选意图
  |- VisionIntentPolicy：视觉 REQUIRED / OPTIONAL / NONE
  |- ToolGroupSelector：候选 ToolGroup
  |- RequestDeadlinePolicy：30s / 60s
  `- RequestSession：冻结本请求所有规划结果

ContextOrchestrator
  |- PromptContextProvider：追加可信视觉证据规则
  |- ToolGroupContextProvider：输出真实视觉 ToolSpecification
  `- 继续独占所有主模型 messages / toolSpecifications

TextAgentLoopOrchestrator
  |- REQUIRED：ToolChoice.REQUIRED
  |- OPTIONAL：ToolChoice.AUTO
  |- Tool 成功后：ToolChoice.NONE，进入总结轮
  |- 校验视觉 Tool 结构化业务结果
  `- 无证据时失败关闭

FrontViewVisionTool
  |- DemoFrontViewImageProvider：按 imageId 读取 assets
  |- QwenVisionAnalyzer：调用 qwen-vl-max
  `- FrontViewVisionResult：结构化 JSON Tool 结果
```

### 3.2 REQUIRED 请求成功时序

```text
1. AIDL 收到 inputType=TEXT。
2. Service 使用 30 秒初始 deadline 完成单槽位准入；准入前不运行 Router。
3. 确认文本分支仍按原有 30 秒路径处理。
4. 普通 TEXT 进入 AgentRuntime.startSession：
   a. KeywordIntentRouter 生成 IntentResult。
   b. VisionIntentPolicy 生成 VisionIntentDecision(REQUIRED)。
   c. ToolGroup 选择收敛为 VISION_GROUP。
   d. RequestDeadlinePolicy 从同一个 startedAtMs 生成 60 秒 deadline。
   e. RequestSession 冻结上述结果。
5. ActiveRequest.bindSession 同步更新最终 sessionId、身份字段和有效 deadline。
6. Service 根据 RequestSession.deadline 安装唯一超时任务，并绑定 RequestExecutionContext。
7. Context 输出 VISION_GROUP 的 `front_camera_interaction` ToolSpecification。
8. Loop 第一次请求使用 ToolChoice.REQUIRED。
9. 主模型返回唯一视觉 Tool Call。
10. Tool 从 RequestExecutionContext 读取可信原始用户问题；仅当 `BuildConfig.DEBUG && evalPermit != null` 时读取受控覆盖 ID，否则使用配置默认 ID。
11. Tool 读取白名单图片并校验大小、声明 MIME 与文件签名，QwenVisionAnalyzer 在剩余期限内调用 qwen-vl-max。
12. Tool 返回不含图片的 FrontViewVisionResult JSON。
13. Loop 验证 `success=true && status=SUCCESS`，记录视觉证据成功。
14. 下一轮主模型调用使用 ToolChoice.NONE，防止重复调用视觉 Tool。
15. 主模型只根据结构化 Tool 结果生成最终回答。
16. 条件式 PostProcessor 追加 Demo 来源/非实时提醒。
17. 最终回答与 Tool 文本结果写入 SessionMemory；图片数据不写入。
18. Service 通过终态 CAS 发送唯一 AgentResponse，清理 Call、timeout、Trace 和请求槽位。
```

### 3.3 REQUIRED 失败时序

以下任一情况必须返回失败，不得让主模型补写“看到的内容”：

- 没有默认图片，也没有合法覆盖 ID。
- imageId 不在配置白名单中。
- assetPath 越界、图片不存在、为空或超过大小限制。
- MIME 不支持，或配置声明 MIME 与文件签名不一致。
- RequestExecutionContext 缺失可信原始问题，或执行上下文已越过生命周期。
- VLM HTTP 失败或返回空文本。
- VLM 返回无法解析的结构化结果。
- 请求被取消。
- 60 秒绝对期限耗尽。
- 模型在 REQUIRED 下连续两次不产生 Tool Call。
- 模型请求多个重复视觉 Tool Call。
- Tool 返回 JSON 业务失败，即使反射 dispatch 本身成功。

对“无 Tool、错误 Tool、重复 Tool、Tool 业务失败”这类非 timeout/cancel 的确定性失败，Loop 必须先闭合 Tool exchange（如模型已声明 Tool Call），再把统一安全失败文本写为 AssistantMessage；该文本必须与最终 AgentResponse 一致。

### 3.4 OPTIONAL 请求

- 视觉 Tool 对模型可见，`toolChoice=AUTO`。
- 模型调用 Tool 后，执行链与 REQUIRED 相同，并在成功后切换 `toolChoice=NONE`。
- 模型不调用 Tool 时允许返回澄清或非视觉回答。
- Prompt 必须明确：没有 Tool 证据时不得声称“我看到”“画面中存在”“前方有”。
- 本阶段不尝试通过关键词扫描最终答案来伪装语义校验；OPTIONAL 的语义真实性通过 Prompt、Trace 和 Eval 样例约束，文档不得宣称其已经达到形式化证明。

---

## 4. 工作边界

### 4.1 本轮必须完成

- 规则型 VisionIntentPolicy 及覆盖误判/漏判的路由测试集。
- VisionDecision 在 Runtime、RequestSession、Context、Trace 中的稳定传递。
- 视觉 ToolGroup 的 REQUIRED / OPTIONAL / NONE 暴露规则。
- 普通 30 秒、视觉 60 秒的统一绝对期限。
- 可空启动的 Demo 图片配置系统。
- assets 白名单图片读取。
- qwen-vl-max 多模态调用封装。
- 结构化视觉证据协议。
- 主模型 Tool Choice 和视觉执行证据门禁。
- VLM Call 取消和 deadline 接入。
- 视觉子 Span 与低敏 Trace 属性。
- IMAGE/VOICE 兼容式停止处理。
- CONTROL 和主动场景链非回归保护。
- 自动化测试、文档更新和图片补齐后的人工验收清单。

### 4.2 本轮明确不做

- 不接入真实摄像头。
- 不实现 CameraSdk 按需拍照、回调等待、帧号绑定或图片新鲜度判断。
- 不删除 Camera 定时调度、`ProcessCaptureGot`、`SceneMatch`、主动场景 Agent 或场景 TTS。
- 不重构主动场景能力。
- 不修改车控 Tool、VehicleStateMachine 或 SoaService。
- 不实现复合任务执行，例如“看看前面并打开车窗”。该类请求本轮返回拆分请求的澄清提示。
- 不引入 LLM Router、小模型 Router、Embedding Router 或向量检索。
- 不引入 Skill、Subagent、多 Agent 协作或新任务队列。
- 不修改 AIDL 方法签名。
- 不删除 `AgentRequest.inputType/imagePath/sceneType` 字段。
- 不重新生成或替换 `AIAgentSdk.jar`。
- 不新增第三方依赖；结构化 JSON 使用项目现有 Gson 2.8.9。
- 不修改 Gradle、AGP、Kotlin、LangChain4j、OkHttp 或 OpenTelemetry 版本。
- 不提交未经用户确认的测试图片。
- 不把缺少真实测试图片当作代码实现失败。

### 4.3 执行中必须暂停询问用户的情况

实施 Agent 遇到以下情况必须停止对应 Task 并询问，不能自行替换技术路线：

1. DashScope OpenAI 兼容接口明确拒绝 LangChain4j `ToolChoice.REQUIRED`，且不是测试 Mock 或参数拼装错误。
2. 发现 Launcher/TestApp 当前仍强依赖 IMAGE 或 VOICE，停止处理会破坏用户尚未说明的线上流程。
3. 必须修改 AIDL 字段顺序、删除 Parcelable 字段或重新生成 SDK 才能继续。
4. 必须读取任意外部文件路径才能实现图片切换。
5. 必须把 Base64/图片写入 Trace、日志或 Memory 才能调试。
6. 必须修改主动场景链、CameraSdk 或 CONTROL 语义才能编译。
7. 现有依赖无法构建多模态 `UserMessage(TextContent, ImageContent)`，需要升级 LangChain4j。
8. 计划中的结构化 Tool JSON 与现有 ToolDispatcher 返回协议发生不可兼容冲突。
9. 执行过程中发现计划列出的关键类或主链已经被其他改动替换，导致文件职责明显不再成立。

---

## 5. 计划文件清单

> 文件名允许执行者在同一职责范围内做轻微命名修正，但不得合并掉独立安全边界，也不得新增计划外架构层。任何职责级变化必须先询问用户。

### 5.1 新增生产代码

#### 视觉决策与运行策略

- Create: `app/src/main/java/com/hirain/aiagent/vision/routing/VisionRequirement.java`
  - 枚举 `REQUIRED / OPTIONAL / NONE`。

- Create: `app/src/main/java/com/hirain/aiagent/vision/routing/VisionIntentDecision.java`
  - 不可变结果，包含 requirement、matchedSignals、reason、compoundIntentDetected。

- Create: `app/src/main/java/com/hirain/aiagent/vision/routing/VisionIntentPolicy.java`
  - 纯 Java 接口，输入用户文本和已有 IntentResult，输出 VisionIntentDecision。

- Create: `app/src/main/java/com/hirain/aiagent/vision/routing/RuleBasedVisionIntentPolicy.java`
  - 当前生产规则实现，不访问 Android、不调用模型、不访问 ToolRegistry。

- Create: `app/src/main/java/com/hirain/aiagent/runtime/RequestDeadlinePolicy.java`
  - 根据视觉决策和最终工具暴露结果生成 30/60 秒 RequestDeadline。

#### Demo 配置与图片读取

- Create: `app/src/main/java/com/hirain/aiagent/tools/vision/demo/VisionDemoConfig.java`
  - 不可变配置模型；包含 schemaVersion、defaultImageId、maxImageBytes、图片定义白名单。

- Create: `app/src/main/java/com/hirain/aiagent/tools/vision/demo/VisionDemoConfigLoader.java`
  - 从固定 assets 路径读取 JSON，使用 Gson 解析并完成 fail-closed 校验。

- Create: `app/src/main/java/com/hirain/aiagent/tools/vision/demo/VisionAssetReader.java`
  - 最小二进制读取接口，便于 JVM 测试使用 Fake。

- Create: `app/src/main/java/com/hirain/aiagent/tools/vision/demo/AndroidVisionAssetReader.java`
  - Android AssetManager 适配，只允许读取配置白名单中的固定 assets 路径。

- Create: `app/src/main/java/com/hirain/aiagent/tools/vision/demo/FrontViewImage.java`
  - 不可变图片载体，包含 imageId、source、mimeType、bytes、sizeBytes、loadedAtMs。

- Create: `app/src/main/java/com/hirain/aiagent/tools/vision/demo/FrontViewImageProvider.java`
  - 图片提供者接口。

- Create: `app/src/main/java/com/hirain/aiagent/tools/vision/demo/DemoFrontViewImageProvider.java`
  - 解析默认/覆盖 imageId、校验白名单和大小、读取图片。

#### VLM 和 Tool 结果

- Create: `app/src/main/java/com/hirain/aiagent/tools/vision/model/VisionAnalysis.java`
  - VLM 结构化分析：summary、observations、uncertainties。

- Create: `app/src/main/java/com/hirain/aiagent/tools/vision/model/VisionAnalyzer.java`
  - 视觉分析接口，便于 Tool 单测注入 Fake。

- Create: `app/src/main/java/com/hirain/aiagent/tools/vision/model/QwenVisionAnalyzer.java`
  - 构造 LangChain4j 多模态消息、调用 qwen-vl-max、解析并校验结构化输出。

- Create: `app/src/main/java/com/hirain/aiagent/tools/vision/model/VisionModelFactory.java`
  - 构建接入 `RequestCallRegistry`、deadline 和 TracingOkHttpInterceptor 的 qwen-vl-max ChatModel。

- Create: `app/src/main/java/com/hirain/aiagent/tools/vision/FrontViewVisionResult.java`
  - Tool 对外结构化 JSON 契约及稳定状态码。

- Create: `app/src/main/java/com/hirain/aiagent/tools/vision/FrontViewVisionTool.java`
  - 唯一 `@Tool(name="front_camera_interaction")` 实现，组合 Provider、Analyzer 和 Trace。

- Create: `app/src/main/java/com/hirain/aiagent/tools/vision/VisionResultParser.java`
  - Loop 侧只解析稳定 Tool 契约，不通过中文文本猜测成功。

- Create: `app/src/main/java/com/hirain/aiagent/tools/vision/VisionResponseMessages.java`
  - 保存 Loop 与 RuntimeResponseMapper 共用的安全失败文本，避免 SessionMemory 与 AgentResponse 文案分裂。

#### Trace

- Create: `app/src/main/java/com/hirain/aiagent/trace/VisionTraceRecorder.java`
  - 创建 `vision.image.load / vision.model / vision.result` 子 Span，严格禁止记录图片内容。

#### TEXT 最终回答

- Create: `app/src/main/java/com/hirain/aiagent/core/postprocessor/VisionGroundingPostProcessor.java`
  - 仅在本轮成功执行 Demo 视觉 Tool 后追加固定 Demo/非实时提醒；普通聊天不追加。

### 5.2 修改生产代码

- Modify: `app/src/main/java/com/hirain/aiagent/intentrouter/KeywordIntentRouter.java`
  - 保持粗粒度 Router 定位；不继续堆叠复杂视觉执行规则。
  - 只修正会直接破坏 VisionIntentPolicy 测试的明显泛化关键词，避免把本类改造成第二套视觉策略。

- Modify: `app/src/main/java/com/hirain/aiagent/toolgroup/DefaultToolGroupSelector.java`
  - 保留现有二参 SAM 兼容路径。
  - 实际视觉 Tool 暴露由 AgentRuntime 结合 VisionIntentDecision 收敛。

- Modify: `app/src/main/java/com/hirain/aiagent/runtime/AgentRuntime.java`
  - 注入 VisionIntentPolicy 和 RequestDeadlinePolicy。
  - 路由一次后生成 Intent、VisionDecision、ToolGroup、有效 deadline。
  - 写入视觉路由 Trace。

- Modify: `app/src/main/java/com/hirain/aiagent/runtime/RequestSession.java`
  - 新增不可变 VisionIntentDecision 字段。

- Modify: `app/src/main/java/com/hirain/aiagent/runtime/RequestSessionFactory.java`
  - 接受并保存 VisionIntentDecision；旧构造/测试路径默认 `NONE`。

- Modify: `app/src/main/java/com/hirain/aiagent/runtime/RequestDeadline.java`
  - 保留 `DEFAULT_TIMEOUT_MS=30_000`；新增视觉 60 秒常量/工厂。

- Modify: `app/src/main/java/com/hirain/aiagent/runtime/ActiveRequest.java`
  - `bindSession` 同步 RequestSession 的最终有效 deadline，避免准入初始 30 秒与视觉 60 秒分裂。

- Modify: `app/src/main/java/com/hirain/aiagent/runtime/RequestExecutionContext.java`
  - 在原 requestId/deadline 外携带只读、低权限的执行选项副本：可信原始用户问题，以及经过 Service 授权后才存在的 Demo 图片 ID。
  - 不复制整个 extraContext；仅复制明确允许的字段，普通 Debug 与 Release 请求的覆盖 ID 均不得进入执行上下文。
  - Scope 关闭后必须完全清除，不能串请求。

- Modify: `app/src/main/java/com/hirain/aiagent/context/provider/PromptContextProvider.java`
  - 仍保持唯一 System Contribution 所有者。
  - 视觉 OPTIONAL/REQUIRED 时把视觉证据约束模板追加到 Persona 系统 Prompt。

- Modify: `app/src/main/java/com/hirain/aiagent/context/provider/CallerExtraContextProvider.java`
  - 排除 `vision_demo_image_id` 等保留执行键，不能把内部测试选择项作为普通 caller 文本注入模型。

- Modify: `app/src/main/java/com/hirain/aiagent/core/TextAgentLoopOrchestrator.java`
  - 设置 ToolChoice。
  - 跟踪视觉 Tool 执行状态。
  - 实施 REQUIRED 无 Tool Call 重试与失败关闭。
  - 解析视觉 Tool 业务成功，而不是只看反射 dispatchSuccess。
  - 对确定性视觉失败闭合 Tool exchange，并用共用安全失败文本闭合 SessionMemory 轮次。

- Modify: `app/src/main/java/com/hirain/aiagent/core/AgentResult.java`
  - 增加稳定的视觉证据不可用错误类型，避免全部塌缩成 MODEL_CALL_FAILED。

- Modify: `app/src/main/java/com/hirain/aiagent/runtime/RuntimeResponseMapper.java`
  - 将视觉失败映射为稳定 status/errorType，并复用 VisionResponseMessages，保证外部文本与 SessionMemory 一致。

- Modify: `app/src/main/java/com/hirain/aiagent/core/factory/AgentConfigFactory.java`
  - TEXT PostProcessor 接入 VisionGroundingPostProcessor。
  - 删除最终无引用的旧 chat/vision persona 工厂方法时必须先完成引用审计。

- Modify: `app/src/main/java/com/hirain/aiagent/trace/TraceAttributeKeys.java`
  - 增加低敏视觉属性和 Tool Choice 属性。

- Modify: `app/src/main/java/com/hirain/aiagent/trace/TraceSpanNames.java`
  - 增加三个视觉子 Span 名称。

- Modify: `app/src/main/java/com/hirain/aiagent/trace/TraceManager.java`
  - 提供 VisionTraceRecorder 工厂，不直接向 Tool 暴露 SDK 生命周期管理。

- Modify: `app/src/main/java/com/hirain/aiagent/AIAgentService.kt`
  - 接入新视觉 Tool。
  - 使用 Runtime 生成的有效 deadline。
  - 停止 IMAGE/VOICE 旧链。
  - 保持 CONTROL、Camera 定时任务、主动场景和 TTS。

- Modify: `app/src/main/java/com/hirain/aiagent/prompt/PromptConstants.java`
  - 增加视觉证据约束模板常量；清理确认无引用的旧 VL warning 常量。

- Modify: `app/src/main/assets/prompts/task/front_view_qa.txt`
  - 从“实时摄像头”改成明确 Demo 图片语义，并要求 VLM 输出严格 JSON。

- Modify/Create: `app/src/main/assets/prompts/messages/front_view_grounding_policy.txt`
  - 主模型视觉证据约束，不包含具体测试图片内容。

- Modify: `app/src/main/assets/prompts/messages/vl_warning.txt`
  - 改成 Demo 测试图片、非实时道路画面的固定提醒；若最终改用新模板，则在无引用后删除旧模板。

### 5.3 删除生产代码

以下删除必须在 Phase 4 进行，且删除前先执行全仓引用搜索：

- Delete: `app/src/main/java/com/hirain/aiagent/tools/vision/vl/VlManager.java`
  - 被 FrontViewVisionTool + DemoFrontViewImageProvider + QwenVisionAnalyzer 替代。

- Delete: `app/src/main/java/com/hirain/aiagent/core/postprocessor/VlWarningPostProcessor.java`
  - 被条件式 VisionGroundingPostProcessor 替代。

- Delete: `app/src/main/java/com/hirain/aiagent/core/preprocessor/VlWarningPreProcessor.java`
  - 仅在确认无生产/测试引用后删除。

不删除：

- `AgentLoopOrchestrator`：主动场景链仍使用。
- `SceneMatch` 和 scene persona。
- CameraSdk、Camera listener、定时 capture。
- VRServiceManager、TTS、CONTROL 相关状态。
- `assets/documents/audi.jpg`：未经用户确认不处理。

### 5.4 新增 assets 配置

- Create: `app/src/main/assets/vision/demo/vision-demo-config.json`

初始内容必须允许“没有图片”这一合法开发状态：

```json
{
  "schemaVersion": 1,
  "defaultImageId": "",
  "maxImageBytes": 5242880,
  "images": []
}
```

- Create: `app/src/main/assets/vision/demo/images/README.md`
  - 说明用户后续如何添加图片和配置。
  - 不提交伪造二进制图片。

后续图片配置格式固定为：

```json
{
  "schemaVersion": 1,
  "defaultImageId": "urban_vehicle",
  "maxImageBytes": 5242880,
  "images": [
    {
      "imageId": "urban_vehicle",
      "assetPath": "vision/demo/images/urban_vehicle.jpg",
      "mimeType": "image/jpeg"
    }
  ]
}
```

### 5.5 新增/修改测试

- Create: `app/src/test/java/com/hirain/aiagent/vision/routing/RuleBasedVisionIntentPolicyTest.java`
- Create: `app/src/test/java/com/hirain/aiagent/runtime/RequestDeadlinePolicyTest.java`
- Modify: `app/src/test/java/com/hirain/aiagent/runtime/RequestDeadlineTest.java`
- Modify: `app/src/test/java/com/hirain/aiagent/runtime/RequestSessionFactoryTest.java`
- Modify: `app/src/test/java/com/hirain/aiagent/runtime/AgentRuntimeTest.java`
- Modify: `app/src/test/java/com/hirain/aiagent/runtime/AgentRuntimeToolGroupTraceTest.java`
- Modify: `app/src/test/java/com/hirain/aiagent/runtime/RequestExecutionContextTest.java`
- Modify: `app/src/test/java/com/hirain/aiagent/runtime/ActiveRequestRegistryTest.java`
- Modify: `app/src/test/java/com/hirain/aiagent/runtime/RuntimeResponseMapperTest.java`
- Create: `app/src/test/java/com/hirain/aiagent/context/provider/PromptContextProviderTest.java`
- Create: `app/src/test/java/com/hirain/aiagent/context/provider/CallerExtraContextProviderTest.java`
- Create: `app/src/test/java/com/hirain/aiagent/tools/vision/demo/VisionDemoConfigLoaderTest.java`
- Create: `app/src/test/java/com/hirain/aiagent/tools/vision/demo/DemoFrontViewImageProviderTest.java`
- Create: `app/src/test/java/com/hirain/aiagent/tools/vision/model/QwenVisionAnalyzerTest.java`
- Create: `app/src/test/java/com/hirain/aiagent/tools/vision/FrontViewVisionResultTest.java`
- Create: `app/src/test/java/com/hirain/aiagent/tools/vision/FrontViewVisionToolTest.java`
- Modify: `app/src/test/java/com/hirain/aiagent/core/TextAgentLoopOrchestratorTest.java`
- Modify: `app/src/test/java/com/hirain/aiagent/trace/TraceRedactorTest.java`
- Create: `app/src/test/java/com/hirain/aiagent/trace/VisionTraceRecorderTest.java`
- Modify: `app/src/test/java/com/hirain/aiagent/toolgroup/ToolGroupRegistryTest.java`

### 5.6 文档

- Create: `docs/overview/front-view-vision-tool-overview.md`
- Create: `docs/testresult/2026-07-17-front-view-vision-manual-acceptance-checklist.md`
- Modify: `README.md`
- Modify: `docs/overview/tool-system-design-and-current-state-overview.md`
- Modify: `docs/overview/agent-design-and-architecture-evaluation.md`
  - 只更新已经落地的视觉证据闭环，不重写整个评估报告。

### 5.7 执行前最终审查修订（2026-07-17）

本节记录最后一次源码对照审查已经写入正文的修订点，后续 Goal 执行者不得退回旧假设：

1. `KeywordIntentRouter` 是单标签胜出模型，复合意图检测必须补充独立文本组合信号。
2. Tool 的 `text` 参数由主模型生成，不等同于原始用户问题；VLM 必须使用 RequestExecutionContext 中冻结的可信问题。
3. 图片覆盖权限不是 `BuildConfig.DEBUG` 单条件，必须同时持有当前请求的有效 Eval permit。
4. REQUIRED 异常 Tool Call 必须逐个写回未执行 ToolResult；确定性失败要用统一安全 AssistantMessage 闭合 SessionMemory。
5. 所有视觉轮次直接跳过 LongTermMemory 提取，不通过输出关键词猜测是否应跳过。
6. 图片配置 MIME 必须与文件签名一致，路径必须规范化后仍位于固定 assets 子目录。
7. Base64 使用 Java 标准库以保持 JVM 可测；VLM 输出必须设置长度与结构上限。
8. 60 秒依据 Runtime 计划暴露结果生成；Context 若无法解析真实 ToolSpecification 必须失败关闭，不再次修改 deadline。
9. Phase 2 只是停止注册旧 VlManager，源码删除仍延后到 Phase 4 引用审计之后。

---

## 6. 实施前基线检查

后续 Goal 执行者在修改代码前必须完成以下工作：

- [ ] 阅读本计划全文，不得只读取某一个 Phase。
- [ ] 读取当前 `AGENTS.md`。
- [ ] 执行 `git status --short`，记录并保护用户已有改动。
- [ ] 确认以下真实主链仍存在：
  - `AIAgentService.handleTextRequest`
  - `AgentRuntime.startSession / execute`
  - `ContextOrchestrator.defaultForText`
  - `TextAgentLoopOrchestrator.execute`
  - `ToolGroupContextProvider`
  - `ToolRegistry.dispatchWithOutcome`
- [ ] 确认 LangChain4j 1.16.3 的 `ChatRequest.Builder.toolChoice(...)` 和 `ToolChoice.REQUIRED/AUTO/NONE` 能编译。
- [ ] 确认 `RequestCallRegistry` 仍由自定义 OkHttp adapter 使用。
- [ ] 确认 CONTROL 三个分支仍存在。
- [ ] 确认主动场景链仍直接使用 CameraData，不依赖新 Demo Provider。
- [ ] 运行自动化基线：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

- [ ] 记录测试数和失败数；如果基线已经失败，先报告现状，不能把既有失败归因于本计划。
- [ ] 如果 Gradle wrapper 锁因权限失败，按 Codex 权限流程申请提升权限后重跑同一命令，不修改仓库代码规避机器权限问题。

---

## 7. Phase 1：视觉决策、Tool 暴露与 30/60 秒请求规划

**阶段目标：** 在不调用 VLM、不读取图片的情况下，先建立可靠的视觉候选决策、ToolGroup 暴露和统一 deadline。Phase 1 完成后，系统应能仅凭 JVM 测试证明不同文本会进入 REQUIRED / OPTIONAL / NONE，且视觉候选请求使用 60 秒。

### Task 1.1：定义 VisionIntentDecision 契约

**Files:**

- Create: `vision/routing/VisionRequirement.java`
- Create: `vision/routing/VisionIntentDecision.java`
- Create: `vision/routing/VisionIntentPolicy.java`
- Test: `RuleBasedVisionIntentPolicyTest.java`

- [ ] `VisionRequirement` 只包含 REQUIRED、OPTIONAL、NONE。
- [ ] `VisionIntentDecision` 必须不可变，并防御性复制 matchedSignals。
- [ ] 字段至少包含：
  - `VisionRequirement requirement`
  - `List<String> matchedSignals`
  - `String reason`
  - `boolean compoundIntentDetected`
- [ ] 提供 `none(reason)` 等稳定工厂，不允许 null requirement。
- [ ] 接口不依赖 Android、LangChain4j、ToolRegistry 或网络。

**验收标准：** 决策对象可以在纯 JVM 测试构造和比较；空文本稳定返回 NONE。

### Task 1.2：实现 RuleBasedVisionIntentPolicy

**Files:**

- Create: `RuleBasedVisionIntentPolicy.java`
- Test: `RuleBasedVisionIntentPolicyTest.java`

规则不得继续采用“命中任意一个图片词就 REQUIRED”。必须组合以下信号：

- 空间/现实指向：`前方、前面、车外、挡风玻璃外、路面、路边、眼前、那里、那个`。
- 感知动作：`看看、看一下、识别、观察、读一下、是什么、有什么、能否看到`。
- 前向对象：`前车、行人、路牌、交通标志、红绿灯、车道、障碍物`。
- 明确设备：`前置摄像头、前视摄像头、摄像头画面`。
- 否定/排除：`不要看摄像头、不需要看画面、不是让你看前方`。
- 泛化图片任务：`生成图片、画一张、图片格式、照片编辑、拍照技巧`。
- 车辆控制信号：同时使用已有 IntentResult 的车辆 IntentTag，以及策略内部最小化的“车载对象 + 控制动作”组合信号。不能只依赖单标签 Router，因为 Router 的优先级胜出结果可能遮蔽同一句中的第二意图。
- 设备知识/故障排除：`工作原理、有什么作用、介绍、区别、怎么安装、坏了怎么办、故障`；在没有现实感知动作时应排除为 NONE。

判定优先级：

1. 空输入 -> NONE。
2. 明确否定或泛化图片创作/知识请求 -> NONE。
3. 设备知识、原理或故障排除请求且没有现实感知动作 -> NONE。
4. 明确设备词 + 现实感知动作，或明确命令使用前向摄像头 -> REQUIRED。
5. 空间指向 + 感知动作，或现实指向 + 前向对象 -> REQUIRED。
6. 单一强感知信号、指代性追问或语境不完整 -> OPTIONAL。
7. 文本组合信号或 IntentResult 表明同时存在视觉和车辆控制 -> `compoundIntentDetected=true`，本阶段不执行复合任务。
8. 其余 -> NONE。

测试语料至少覆盖：

- [ ] REQUIRED：`前方是什么？`
- [ ] REQUIRED：`帮我看看前面的路牌。`
- [ ] REQUIRED：`挡风玻璃外那个红色标志是什么意思？`
- [ ] REQUIRED：`用前置摄像头看一下前面。`
- [ ] OPTIONAL：`那个红色的东西是什么？`
- [ ] OPTIONAL：`你能看一下吗？`
- [ ] NONE：`介绍一下前方碰撞预警。`
- [ ] NONE：`帮我生成一张汽车图片。`
- [ ] NONE：`怎么拍好一张道路照片？`
- [ ] NONE：`不要看摄像头，介绍一下交通标志。`
- [ ] NONE：`介绍一下前置摄像头的工作原理。`
- [ ] NONE：`前视摄像头坏了怎么办？`
- [ ] COMPOUND：`看看前面有什么，然后打开车窗。`
- [ ] COMPOUND：`识别前面的路况并切换运动模式。`
- [ ] COMPOUND：构造 Router 只返回 VISION_QA、但文本仍含“打开车窗”的用例，证明复合检测不依赖单标签胜出结果。
- [ ] 空白/null、标点、大小写和多余空格。

**边界：** 不在本 Task 引入分词器、正则 DSL 或外部配置系统；规则集中在一个类中保持可审计。

### Task 1.3：将 VisionDecision 接入 AgentRuntime 和 ToolGroup

**Files:**

- Modify: `AgentRuntime.java`
- Modify: `RequestSession.java`
- Modify: `RequestSessionFactory.java`
- Modify: `DefaultToolGroupSelector.java`（仅必要兼容）
- Test: `AgentRuntimeTest.java`
- Test: `RequestSessionFactoryTest.java`

- [ ] AgentRuntime 构造器支持注入 VisionIntentPolicy；现有测试构造器使用默认 RuleBased 实现或明确 NONE Fake。
- [ ] 每个请求只执行一次 KeywordIntentRouter 和一次 VisionIntentPolicy。
- [ ] 视觉决策收敛规则：
  - REQUIRED -> `VISION_GROUP`，仅 `front_camera_interaction`。
  - OPTIONAL + 基础结果为 CHAT/UNKNOWN -> `VISION_GROUP`，Tool 可见但不强制。
  - NONE + KeywordIntentRouter 错误给出 VISION_QA -> 强制改为 CHAT_ONLY，避免误调用。
  - 视觉+车控复合 -> CLARIFICATION_REQUIRED，固定提示用户拆成两次请求。
  - 明确非视觉车辆/天气 Intent -> 保持原有 ToolGroup。
- [ ] 不修改 ToolGroupSelector 的二参 SAM，现有 lambda 测试必须继续编译。
- [ ] RequestSession 保存 VisionIntentDecision；缺失时默认 NONE，不得默认 REQUIRED。
- [ ] Intent 与 VisionDecision 同时写 Trace，允许在诊断中看到二者不一致。

**验收标准：** `帮我生成汽车图片` 即使旧 Router 命中 VISION_QA，也不得向模型暴露前向视觉 Tool。

### Task 1.4：实现统一 30/60 秒 RequestDeadlinePolicy

**Files:**

- Create: `RequestDeadlinePolicy.java`
- Modify: `RequestDeadline.java`
- Modify: `AgentRuntime.java`
- Modify: `ActiveRequest.java`
- Modify: `AIAgentService.kt`
- Test: `RequestDeadlinePolicyTest.java`
- Test: `RequestDeadlineTest.java`
- Test: `ActiveRequestRegistryTest.java`

- [ ] 保持 Service 在准入前不执行 Router，避免 BUSY 请求产生无意义规划成本。
- [ ] Service 以原 startedAtMs 和标准 30 秒 deadline 创建 RequestAdmission。
- [ ] 确认文本继续使用该 30 秒 deadline。
- [ ] 普通 TEXT 准入后由 AgentRuntime 根据最终 VisionDecision/ToolGroup 计算有效 deadline：
  - 最终选择结果未计划暴露视觉 Tool -> 30 秒。
  - REQUIRED 或 OPTIONAL 且 selectedToolNames 包含 `front_camera_interaction` -> 60 秒。
- [ ] 这里的 60 秒依据是 Runtime 的“计划暴露结果”，不是尚未发生的 Context ToolSpecification 解析；若后续真实 ToolRegistry 缺失该工具，Context 必须快速失败关闭，但 deadline 仍保持 60 秒且不得二次改写。
- [ ] RequestSession 保存有效 deadline。
- [ ] `ActiveRequest.bindSession` 将 provisional deadline 原子替换为 RequestSession.deadline；字段至少使用 volatile/AtomicReference 保证 cancel/timeout 线程可见，并测试 bind 与 cancel 并发时不会回退 deadline 或复活终态。
- [ ] Service 只能在 RuntimeSession 创建后写入 `request.timeout_ms` 和安装 timeout runnable。
- [ ] `mainHandler.postDelayed`、`RequestExecutionContext.bind`、Runtime/Context/Loop 全部读取同一个 RequestSession.deadline。
- [ ] startedAtMs 不得在路由完成后重新生成，60 秒从 AIDL TEXT 请求开始计时。

**验收标准：** Trace、ActiveRequest、RequestSession 和 HTTP Call 的 deadlineAtMs 完全一致。

### Task 1.5：Context 接入视觉可信规则和内部键过滤

**Files:**

- Create: `assets/prompts/messages/front_view_grounding_policy.txt`
- Modify: `PromptConstants.java`
- Modify: `PromptContextProvider.java`
- Modify: `CallerExtraContextProvider.java`
- Test: `PromptContextProviderTest.java`
- Test: `CallerExtraContextProviderTest.java`

- [ ] PromptContextProvider 继续产生唯一 System Contribution。
- [ ] VisionRequirement 为 OPTIONAL/REQUIRED 时，在 Persona prompt 后追加视觉证据规则。
- [ ] NONE 时输出与改造前完全一致的 Persona prompt。
- [ ] 规则明确：
  - 没有成功 Tool 结果不得声称看到画面。
  - Demo 结果不是实时道路证据。
  - 不得扩写结构化 evidence 未包含的事实。
  - uncertainties 必须保留为不确定性，不能改成确定结论。
- [ ] CallerExtraContextProvider 排除 `vision_demo_image_id`；其他现有 caller extra 语义不变。

### Phase 1 测试门槛

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.vision.routing.*" --tests "com.hirain.aiagent.runtime.*" --tests "com.hirain.aiagent.context.provider.*"
.\gradlew.bat assembleDebug
```

- [ ] REQUIRED/OPTIONAL/NONE/COMPOUND 全覆盖。
- [ ] 普通 TEXT 30 秒、视觉候选 60 秒。
- [ ] Context 仍只有一个 SystemMessage。
- [ ] ToolGroupSelector SAM 兼容测试通过。
- [ ] Phase 1 未读取图片、未调用网络、未修改旧 IMAGE/VOICE/CONTROL。

---

## 8. Phase 2：Demo 图片配置、VLM 分析与结构化 Vision Tool

**阶段目标：** 完成可注入、可测试、无真实图片也可启动的视觉 Tool。Phase 2 结束时，Fake 图片和 Fake VLM 能走完 Tool 单测；真实 assets 图片仍允许缺失。

### Task 2.1：创建空配置和图片目录契约

**Files:**

- Create: `assets/vision/demo/vision-demo-config.json`
- Create: `assets/vision/demo/images/README.md`

- [ ] 使用第 5.4 节的空配置。
- [ ] README 说明：
  - 支持 JPEG/PNG/WebP；实际 MIME 由配置声明。
  - 每个 imageId 唯一。
  - assetPath 必须位于 `vision/demo/images/`。
  - 修改默认图只改 defaultImageId。
  - Debug 构建中持有有效 Eval permit 的请求可覆盖 imageId。
  - 普通 Debug 请求和 Release 请求均忽略覆盖值。
- [ ] 不复制或移动 `documents/audi.jpg`。
- [ ] 不创建伪图片、空图片或错误扩展名图片。

### Task 2.2：实现配置加载和校验

**Files:**

- Create: `VisionDemoConfig.java`
- Create: `VisionDemoConfigLoader.java`
- Test: `VisionDemoConfigLoaderTest.java`

校验规则：

- [ ] schemaVersion 必须为 1。
- [ ] images 允许为空；空表示 CONFIG_NOT_READY，不导致 Service 启动失败。
- [ ] 非空 imageId 必须唯一。
- [ ] defaultImageId 非空时必须存在于 images。
- [ ] assetPath 必须是相对路径、以 `vision/demo/images/` 开头、不得包含 `..`、反斜杠根路径或盘符。
- [ ] MIME 仅允许 `image/jpeg`、`image/png`、`image/webp`。
- [ ] maxImageBytes 必须为正；未配置时默认 5 MiB。
- [ ] JSON 格式错误转换为稳定 CONFIG_INVALID，不把 Gson 原始异常直接暴露给用户。
- [ ] Loader 将“读取固定 asset”和“纯 Java parse/validate”分开；单元测试直接传 JSON 字符串/Reader，不依赖 Android AssetManager 或 Robolectric。
- [ ] Loader 不缓存图片字节，只缓存不可变配置。

### Task 2.3：实现 DemoFrontViewImageProvider

**Files:**

- Create: `VisionAssetReader.java`
- Create: `AndroidVisionAssetReader.java`
- Create: `FrontViewImage.java`
- Create: `FrontViewImageProvider.java`
- Create: `DemoFrontViewImageProvider.java`
- Test: `DemoFrontViewImageProviderTest.java`

- [ ] Provider 选择顺序：合法 Debug/Eval override -> defaultImageId -> CONFIG_NOT_READY。
- [ ] Provider 不自行判断调用方权限；Service 仅在 `BuildConfig.DEBUG && evalPermit != null` 时把已授权 override 放入 RequestExecutionContext。Provider 只读取该受控字段，Release 不可能获得覆盖 ID。
- [ ] override 不存在于白名单时返回 IMAGE_NOT_CONFIGURED，不回退 default。
- [ ] 使用流式读取并在超过 maxImageBytes 时立即停止，不能先无限读入再检查。
- [ ] 0 字节图片返回 IMAGE_EMPTY。
- [ ] 读取后检查 JPEG/PNG/WebP 文件签名，并要求与白名单声明 MIME 一致；不只信任扩展名或配置字符串。
- [ ] assetPath 规范化后必须仍位于 `vision/demo/images/`，拒绝 `.`/`..` 段、空段、反斜杠、盘符、URI scheme 和前导 `/`。
- [ ] FrontViewImage 构造时 clone bytes，读取器也不得暴露可变内部数组。
- [ ] source 固定 `DEMO_ASSET`。
- [ ] `loadedAtMs` 表示本次读取时间，不得命名为真实相机 capturedAt。
- [ ] 不写磁盘、不输出 Base64、不打印 asset 内容。

测试使用内存 FakeVisionAssetReader，不依赖 Android AssetManager 或真实图片文件。

### Task 2.4：定义 VLM 结构化分析协议

**Files:**

- Create: `VisionAnalysis.java`
- Create: `VisionAnalyzer.java`
- Modify: `assets/prompts/task/front_view_qa.txt`
- Test: `QwenVisionAnalyzerTest.java`

VLM 必须返回：

```json
{
  "summary": "画面中央有一辆汽车",
  "observations": ["车辆位于画面中央"],
  "uncertainties": ["无法从单张图片判断车辆速度"]
}
```

- [ ] Prompt 明确当前输入为 Demo 测试图片，不称为实时摄像头。
- [ ] 要求只输出 JSON，不输出 Markdown code fence。
- [ ] summary 必须非空且限制合理长度。
- [ ] observations/uncertainties null 时标准化为空列表。
- [ ] 不接受 VLM 自报 confidence 作为可信字段。
- [ ] VLM 无法判断时必须通过 uncertainties 表达。
- [ ] 无法解析、空响应或缺少 summary 返回 MODEL_OUTPUT_INVALID。

### Task 2.5：实现 QwenVisionAnalyzer 与可取消模型工厂

**Files:**

- Create: `QwenVisionAnalyzer.java`
- Create: `VisionModelFactory.java`
- Modify: `AgentConfigFactory.java`（仅移除重复旧工厂时涉及）
- Test: `QwenVisionAnalyzerTest.java`
- Test: `RequestCallRegistryTest.java`

- [ ] 使用 `SystemMessage + UserMessage(TextContent, ImageContent)`。
- [ ] 使用 `java.util.Base64.getEncoder().encodeToString(...)` 生成无换行 Base64，避免 Android Base64 使纯 JVM 单测依赖 Android stub。
- [ ] ImageContent MIME 来自白名单配置。
- [ ] VisionModelFactory 使用 qwen-vl-max、DashScope compatible-mode URL 和现有 API Key。
- [ ] 模型使用低随机性设置（temperature=0 或当前 SDK 可表达的等价值）和有界 maxTokens；若当前 builder 不支持对应参数，记录并以输出长度门禁兜底，不得升级依赖。
- [ ] OkHttp builder 注入现有 RequestCallRegistry。
- [ ] 自定义 OkHttp adapter 根据 RequestExecutionContext.deadline 设置 call.timeout，VLM 不再保留独立 120 秒主链语义。
- [ ] 加入 TracingOkHttpInterceptor，但禁止记录请求 body。
- [ ] Analyzer 接收 ChatModel 或最小 ModelClient 接口，JVM 测试不得访问网络。
- [ ] 取消/超时异常转换为稳定状态，不能统一误报为 JSON 解析失败。
- [ ] 异常判定优先级固定为：RequestCallRegistry 对当前 requestId 已记录 cancellationRequested 且 deadline 未到 -> MODEL_CANCELLED；绝对 deadline 已到或 cause chain 含 SocketTimeoutException -> MODEL_TIMEOUT；其余网络/模型异常 -> MODEL_CALL_FAILED。Service 终态仍拥有最终 TIMEOUT/CANCELLED 映射优先权。
- [ ] VLM 原始文本设置最大长度（建议 16 KiB），summary、数组数量和单项长度均设上限；超限统一 MODEL_OUTPUT_INVALID，避免大结果进入 ToolResult/Memory。

### Task 2.6：实现 FrontViewVisionResult 和 FrontViewVisionTool

**Files:**

- Create: `FrontViewVisionResult.java`
- Create: `VisionResultParser.java`
- Create: `FrontViewVisionTool.java`
- Test: `FrontViewVisionResultTest.java`
- Test: `FrontViewVisionToolTest.java`

成功 JSON 固定为：

```json
{
  "schemaVersion": 1,
  "success": true,
  "status": "SUCCESS",
  "source": "DEMO_ASSET",
  "imageId": "urban_vehicle",
  "mimeType": "image/jpeg",
  "sizeBytes": 34579,
  "loadedAtMs": 1784260000000,
  "model": "qwen-vl-max",
  "analysis": {
    "summary": "画面中央有一辆汽车",
    "observations": ["车辆位于画面中央"],
    "uncertainties": ["无法判断车辆速度"]
  },
  "limitations": ["该结果基于预置测试图片，不代表实时道路画面"]
}
```

失败 JSON 保持同一 envelope，稳定 status 至少包括：

- `CONFIG_NOT_READY`
- `CONFIG_INVALID`
- `IMAGE_NOT_CONFIGURED`
- `IMAGE_NOT_FOUND`
- `IMAGE_EMPTY`
- `IMAGE_TOO_LARGE`
- `IMAGE_MIME_UNSUPPORTED`
- `IMAGE_MIME_MISMATCH`
- `EXECUTION_CONTEXT_MISSING`
- `MODEL_TIMEOUT`
- `MODEL_CANCELLED`
- `MODEL_CALL_FAILED`
- `MODEL_OUTPUT_INVALID`
- `INTERNAL_ERROR`

失败 envelope 至少固定包含 `schemaVersion=1`、`success=false`、`status` 和经过脱敏的短 `reason`；`analysis` 必须为 null/缺省，图片元数据仅在已经安全加载时允许出现。`reason` 只使用稳定原因码或安全摘要，不包含异常类堆栈、HTTP body、绝对路径或模型原始输出。

Tool 实现要求：

- [ ] 保持 Tool 名 `front_camera_interaction`，避免 ToolGroupRegistry/评测协议无意义改名。
- [ ] 为兼容既有 Tool schema，Tool 参数仍只接受 `text`，但该值是模型生成参数，不能作为可信原始问题；生产执行必须使用 RequestExecutionContext 中冻结的原始用户问题。
- [ ] Tool 参数与原始问题不一致时以原始问题为准，只记录低敏 mismatch=true；不得把两个问题拼接后发送 VLM。执行上下文缺失时返回 EXECUTION_CONTEXT_MISSING。
- [ ] imageId 来自受控执行上下文或默认配置，不来自模型参数。
- [ ] 捕获预期异常并返回失败 JSON；禁止返回空字符串。
- [ ] result JSON 不含 bytes、Base64、绝对路径、API Key 或堆栈。
- [ ] ToolDispatcher 技术成功与 VisionResult 业务成功明确分离。
- [ ] VisionResultParser 校验 schemaVersion、success/status 一致性、必需字段和长度上限；不得只检查 `success=true` 两个字符。

### Task 2.7：接入 AIAgentService 和 ToolRegistry

**Files:**

- Modify: `AIAgentService.kt`
- Modify: `ToolGroupRegistryTest.java`

- [ ] 初始化空配置时不得抛出导致 Service 启动失败。
- [ ] 构造 DemoFrontViewImageProvider、VisionModelFactory、QwenVisionAnalyzer、FrontViewVisionTool。
- [ ] VisionModelFactory 只完成对象装配，Service 启动时不得发起模型请求或读取图片字节。
- [ ] ToolRegistry 注册 FrontViewVisionTool，不再注册 VlManager。
- [ ] VISION_GROUP 仍只包含 `front_camera_interaction`。
- [ ] `ProcessCaptureGot` 移除 `vl.frontCameraSave`，但其余主动场景识别代码完全保留。
- [ ] Camera 定时调度、SceneMatch 和 TTS 初始化不因 Demo Tool 改造改变。

### Phase 2 测试门槛

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.tools.vision.*" --tests "com.hirain.aiagent.toolgroup.ToolGroupRegistryTest"
.\gradlew.bat assembleDebug
```

- [ ] Fake 图片 + Fake VLM 成功结果可序列化/解析。
- [ ] 空配置返回 CONFIG_NOT_READY。
- [ ] 无实际图片时 assembleDebug 成功。
- [ ] 非白名单、路径越界、空图、超大图、非法 MIME 全部失败关闭。
- [ ] 声明 MIME 与 JPEG/PNG/WebP 文件签名不一致时失败关闭。
- [ ] Tool result/日志/测试输出中不存在 `data:image` 或大段 Base64。
- [ ] 主动场景代码仍编译。

---

## 9. Phase 3：Tool 强制执行、证据闭环、取消与 Trace

**阶段目标：** 让“Tool 可见”升级为“REQUIRED 请求必须有成功证据”，并让 VLM 纳入现有 timeout/cancel/Trace。Phase 3 完成后，主模型不能在 REQUIRED 请求中绕过视觉 Tool。

### Task 3.1：在 TextAgentLoop 中设置 ToolChoice

**Files:**

- Modify: `TextAgentLoopOrchestrator.java`
- Test: `TextAgentLoopOrchestratorTest.java`

每个视觉请求维护 loop-local 状态：

- `visionToolAttempted`
- `visionEvidenceSucceeded`
- `requiredNoToolRetryCount`

Tool Choice 规则：

- [ ] REQUIRED 且尚无成功证据 -> `ToolChoice.REQUIRED`。
- [ ] OPTIONAL 且尚未执行视觉 Tool -> `ToolChoice.AUTO`。
- [ ] 视觉 Tool 成功后 -> `ToolChoice.NONE`，确保下一轮生成总结而不是重复看图。
- [ ] NONE -> 保持当前工具选择语义；不能把所有请求改成 NONE。
- [ ] Trace 记录本轮 toolChoice。
- [ ] 设置 REQUIRED 前断言本轮 ToolSpecifications 恰好包含唯一 `front_camera_interaction`；为空、缺失或混入其他 Tool 时直接 TOOL_SPEC_RESOLUTION_FAILED，不向模型发送不满足强制语义的请求。

防御规则：

- [ ] REQUIRED 首次没有 Tool Call：丢弃该普通 AiMessage，不写 Memory，重试一次。
- [ ] 第二次仍没有 Tool Call：写入统一安全失败 AssistantMessage 闭合用户轮次，返回外部 `VISION_EVIDENCE_UNAVAILABLE`，并把内部 reason 记录为 `VISION_TOOL_NOT_CALLED`；不保存模型伪答案。
- [ ] REQUIRED 返回非 `front_camera_interaction` Tool：为所有已声明 Tool Call 写入“未执行”ToolResult，再写安全失败 AssistantMessage 并失败关闭。
- [ ] 同一批出现多个视觉 Tool Call：零执行；为每个声明写入“重复调用未执行”ToolResult，再写安全失败 AssistantMessage，避免重复 VLM 成本且保持消息序列合法。
- [ ] 视觉 Tool 成功后再次请求 Tool：拒绝重复执行。

### Task 3.2：验证 Tool 业务结果并建立回答真值门禁

**Files:**

- Modify: `TextAgentLoopOrchestrator.java`
- Modify: `AgentResult.java`
- Modify: `RuntimeResponseMapper.java`
- Test: `TextAgentLoopOrchestratorTest.java`
- Test: `RuntimeResponseMapperTest.java`

- [ ] 使用 VisionResultParser 解析 `front_camera_interaction` 的 resultText。
- [ ] `ToolDispatchOutcome.dispatchSuccess=true` 只代表反射调用成功，不能当作视觉成功。
- [ ] 只有 VisionResult `success=true && status=SUCCESS` 才设置 `visionEvidenceSucceeded=true`。
- [ ] REQUIRED Tool 业务失败：先完成 ToolExecutionResultMessage 回填，保持消息序列合法，然后返回稳定 `VISION_EVIDENCE_UNAVAILABLE`。
- [ ] 在仍未 timeout/cancel 的前提下，Tool 业务失败后追加 VisionResponseMessages 中的安全失败 AssistantMessage；该消息与 RuntimeResponseMapper 输出完全一致。
- [ ] 不再进入自由总结轮。
- [ ] RuntimeResponseMapper 输出：
  - `success=false`
  - `status=VISION_EVIDENCE_UNAVAILABLE`
  - `errorType=VISION_EVIDENCE_UNAVAILABLE`
  - 面向用户文本不泄露内部异常，例如“暂时无法获取有效的前向视觉信息”。
  - errorDetail 保留稳定内部 status/reason，供 Debug/TestApp 使用。
- [ ] 超时/取消继续优先映射 TIMEOUT/CANCELLED，不被视觉错误覆盖。

### Task 3.3：条件式最终提醒和 Memory 边界

**Files:**

- Create: `VisionGroundingPostProcessor.java`
- Modify: `AgentConfigFactory.java`
- Modify: `vl_warning.txt` 或新建等价模板
- Test: `TextAgentLoopOrchestratorTest.java`

- [ ] 仅在本次 ToolHistory 包含成功视觉结果时追加提醒。
- [ ] 普通聊天、天气、车控和失败视觉请求不追加。
- [ ] 提醒明确“预置测试图片，不代表实时道路画面”。
- [ ] 避免重复追加同一提醒。
- [ ] SessionMemory 中的 ToolResult 只含 JSON 文本证据。
- [ ] 最终 AiMessage 与对外 AgentResponse 文本一致。
- [ ] MemoryExtractor 输入不得包含图片/Base64；REQUIRED、OPTIONAL 或本轮执行过视觉 Tool 时均直接跳过 `extractTurnMemory`，此规则不依赖模型输出内容猜测。
- [ ] 对 no-tool/错误-tool/重复-tool/Tool 业务失败分别验证 SessionMemory 尾部合法；timeout/cancel 验证现有 SessionHistorySequenceValidator 能在下一次读取时安全修复部分交换。

### Task 3.4：VLM 取消和 60 秒剩余期限验证

**Files:**

- Modify: `RequestExecutionContext.java`
- Modify: `AIAgentService.kt`
- Modify: `VisionModelFactory.java`
- Test: `RequestExecutionContextTest.java`
- Test: `RequestCallRegistryTest.java`
- Test: `QwenVisionAnalyzerTest.java`

- [ ] Service worker 绑定 runtimeSession.deadline、可信原始用户问题和只读执行选项。
- [ ] 只有 `BuildConfig.DEBUG && evalPermit != null` 时才把 `vision_demo_image_id` 复制进执行选项；普通 Debug 调用、过期/无效 permit 和全部 Release 请求均忽略覆盖值并走 defaultImageId。
- [ ] 执行选项只复制 allowlist 字段并防御性复制，禁止 Tool 直接读取可变 AgentRequest.extraContext。
- [ ] VLM Call 注册到与主模型相同的 requestId。
- [ ] 主模型 -> VLM -> 主模型是顺序 Call；RequestCallRegistry 任一时刻只持有当前 Call。
- [ ] cancelAgentRequest 在 VLM 执行时能取消当前 Call。
- [ ] timeout runnable 在 60 秒到期时取消当前 Call。
- [ ] VLM 根据剩余时间设置 call.timeout，不重新获得完整 60 秒。
- [ ] timeout/cancel 后迟到 VLM 结果不得写 Memory、不得发送第二次响应。
- [ ] finally 清理 registry 和 thread local。

### Task 3.5：视觉 Trace

**Files:**

- Create: `VisionTraceRecorder.java`
- Modify: `TraceSpanNames.java`
- Modify: `TraceAttributeKeys.java`
- Modify: `TraceManager.java`
- Modify: `AgentRuntime.java`
- Modify: `TextAgentLoopOrchestrator.java`
- Test: `VisionTraceRecorderTest.java`
- Test: `AgentRuntimeToolGroupTraceTest.java`
- Test: `TraceRedactorTest.java`

目标 Span：

```text
agent.request
  `- agent.loop
      `- agent.iteration
          |- gen_ai.chat                    # qwen-turbo 决策
          `- tool.execute
              |- tool.safety_check
              |- tool.dispatch
              |   |- vision.image.load
              |   |- vision.model           # qwen-vl-max + HTTP attrs
              |   `- vision.result
              `- tool.result_writeback
```

根/迭代属性：

- `agent.vision.requirement`
- `agent.vision.reason`
- `agent.vision.matched_signals`
- `agent.vision.compound_detected`
- `gen_ai.request.tool_choice`
- `agent.vision.no_tool_retry_count`
- `agent.vision.evidence_success`

视觉子 Span 属性：

- `vision.image.source`
- `vision.image.id`
- `vision.image.mime_type`
- `vision.image.size_bytes`
- `vision.image.loaded_at_ms`
- `vision.status`
- `vision.model`
- `vision.duration_ms`

禁止属性/事件：

- 图片 bytes。
- Base64。
- data URL。
- 绝对 asset/file 路径。
- VLM 完整多模态请求 body。
- API Key。

Trace 失败只能降级为 no-op，不能导致视觉业务失败。

### Phase 3 测试门槛

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.core.TextAgentLoopOrchestratorTest" --tests "com.hirain.aiagent.runtime.RequestCallRegistryTest" --tests "com.hirain.aiagent.trace.*" --tests "com.hirain.aiagent.tools.vision.*"
.\gradlew.bat assembleDebug lintDebug
```

- [ ] REQUIRED 第一次无 Tool Call 会重试一次。
- [ ] REQUIRED 第二次无 Tool Call 失败。
- [ ] REQUIRED ToolSpecifications 缺失或混入其他 Tool 时不调用模型。
- [ ] 错误 Tool/重复 Tool 的每个声明都有对应未执行 ToolResult，SessionMemory 不留下孤立 ToolCall。
- [ ] Tool 失败后不进入最终模型总结。
- [ ] Tool 成功后下一轮 ToolChoice.NONE。
- [ ] OPTIONAL 无 Tool Call 可返回澄清文本。
- [ ] cancel/timeout 只发一个终态响应。
- [ ] 普通 Debug、Debug+有效 Eval permit、Release 三种覆盖权限分别验证；只有第二种生效。
- [ ] 视觉轮次不调用 LongTermMemory 提取，确定性视觉失败的 SessionMemory 文本与 AgentResponse 一致。
- [ ] Memory、Trace、日志中不含图片和 Base64。

---

## 10. Phase 4：兼容入口收敛、旧视觉代码清理与总体验收

**阶段目标：** 删除已经被新 TEXT Vision Tool 替代的 IMAGE/VOICE/VlManager 业务实现，同时保留 CONTROL 和主动场景链，最后同步文档并输出图片补齐后的人工验收清单。

### Task 4.1：兼容式收敛 IMAGE / VOICE

**Files:**

- Modify: `AIAgentService.kt`
- Modify: `RuntimeResponseMapper.java`（如共用 unsupported 映射）

Service 入口固定为：

```text
TEXT    -> handleTextRequest
CONTROL -> handleControlRequest
IMAGE   -> dispatchUnsupportedInputResponse
VOICE   -> dispatchUnsupportedInputResponse
其他     -> dispatchUnsupportedInputResponse
```

- [ ] unsupported 响应必须有 requestId、sessionId、userId、personaId、clientMessageId、timestamp。
- [ ] `success=false`。
- [ ] `status/errorType=UNSUPPORTED_INPUT_TYPE`。
- [ ] 文本明确提示“当前 Agent 仅支持 TEXT，CONTROL 为内部兼容控制通道”。
- [ ] unsupported 分支不进入 AgentRuntime、不占用 TEXT worker、不调用模型。
- [ ] 若创建 Trace，必须使用短生命周期 root 并正常关闭；不得留下半开 span。
- [ ] 不删除 AgentRequest 字段，不改 Parcel 读写顺序。

### Task 4.2：保留 CONTROL 和主动场景链

**Files:**

- Modify: `AIAgentService.kt`（仅引用整理）
- Test/Manual: CONTROL 非回归

- [ ] 保留 `StartListen`：清理旧字符串状态、设置 mChating、停止 TTS。
- [ ] 保留 `StopListen`：清除 mChating。
- [ ] 保留 `ClearChatMemory`：清除当前共享 Session 短期消息。
- [ ] CONTROL 不进入 AgentRuntime。
- [ ] 保留 Camera 初始化和每秒 capture 调度。
- [ ] 保留 ProcessCaptureGot、SceneMatch、Scene Persona 和主动场景 TTS。
- [ ] 不借机清理 mChating/mNagativeReqExecuting 等旧场景状态，即使部分字段看起来可疑；后续场景重构单独处理。

### Task 4.3：删除 VlManager 和旧 VOICE/IMAGE 专属对象

**Files:**

- Delete: `VlManager.java`
- Delete: `VlWarningPostProcessor.java`
- Delete: `VlWarningPreProcessor.java`（确认无引用后）
- Modify: `AgentConfigFactory.java`
- Modify: `AIAgentService.kt`

- [ ] 删除 `handleImageRequest`。
- [ ] 删除 `handleVoiceRequest`。
- [ ] 删除只服务 VOICE 的 `chatOrchestrator` 字段和初始化。
- [ ] `AgentConfigFactory.createChatPersona` 若已无生产/测试引用则删除。
- [ ] `AgentConfigFactory.createVisionQAPersona` 若已无引用则删除。
- [ ] 删除 VlManager import、字段、初始化、注册和 frontCameraSave。
- [ ] 保持 AgentLoopOrchestrator，因为 Scene 仍使用。
- [ ] 删除前运行引用搜索；发现外部可见 API 依赖时暂停询问用户。

静态检查：

```powershell
Get-ChildItem app/src -Recurse -File -Include *.java,*.kt |
  Select-String -Pattern 'VlManager|handleImageRequest|handleVoiceRequest|createVisionQAPersona|chatOrchestrator'
```

预期：不存在生产引用；测试中的历史字符串断言也应同步删除或改为 unsupported 语义。

### Task 4.4：更新文档

**Files:**

- Create: `docs/overview/front-view-vision-tool-overview.md`
- Modify: `README.md`
- Modify: `docs/overview/tool-system-design-and-current-state-overview.md`
- Modify: `docs/overview/agent-design-and-architecture-evaluation.md`

文档必须区分：

- 已实现：Demo 配置、TEXT Tool 链、结构化证据、60 秒、取消、Trace、路由策略。
- 尚未实现：真实摄像头、按需取帧、新鲜度、真实道路安全判断。
- 尚未验收：用户未补图片时的真实 VLM 端到端效果。
- 保留兼容：CONTROL、主动场景链。
- 停止处理：IMAGE、VOICE。

不得使用“实时前向摄像头能力已经完成”之类表述。

### Task 4.5：创建图片补齐后的人工验收清单

**File:**

- Create: `docs/testresult/2026-07-17-front-view-vision-manual-acceptance-checklist.md`

清单至少包含以下用例，每项提供“操作、预期 AgentResponse、预期 Tool、预期 Trace、PASS/FAIL/BLOCKED、证据位置”：

1. 默认 imageId 成功。
2. Debug/Eval 覆盖到第二张图片。
3. 非法 imageId。
4. 图片不存在。
5. 图片超过大小限制。
6. VLM 输出非法 JSON。
7. VLM 网络失败。
8. 视觉请求 60 秒 timeout。
9. VLM 执行中 cancelAgentRequest。
10. REQUIRED 主模型不调用 Tool 的 Fake/测试路径。
11. 普通聊天保持 30 秒且不暴露视觉 Tool。
12. 泛化图片请求不触发前向视觉。
13. 视觉+车控复合请求返回拆分澄清。
14. IMAGE/VOICE 返回 UNSUPPORTED_INPUT_TYPE。
15. CONTROL 三个命令仍有效。
16. 主动场景链仍可运行。
17. Phoenix 中存在 vision.image.load/model/result。
18. Phoenix/日志/SQLite 中不存在 Base64 或图片 bytes。

当前用户未补图片时：

- 自动化与 Fake 测试可以 PASS。
- 默认真实 Tool 请求应预期返回 CONFIG_NOT_READY。
- 依赖真实图片和 DashScope 的设备项标记 BLOCKED，不得伪造 PASS。

### Task 4.6：最终自动化验收

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

- [ ] 所有 JVM tests 0 failures / 0 errors。
- [ ] assembleDebug 成功。
- [ ] lintDebug 成功；既有 warning 单独记录，不擅自扩大范围修复。
- [ ] `git diff --check` 通过。
- [ ] 计划中的新增配置 JSON 可被测试解析。
- [ ] README 相对链接不存在缺失。
- [ ] Release merged manifest 中不包含 Debug Eval Service（如果本轮构建 Release 验证）。
- [ ] 无新的依赖或版本改动。
- [ ] 无实际测试图片提交。
- [ ] CONTROL/Scene 生产引用仍存在。
- [ ] IMAGE/VOICE 旧处理方法不存在。

### Phase 4 完成判定

代码实施可以在以下条件下标记完成：

1. 四个 Phase 自动化门槛通过。
2. 空图片配置下 Service 可构建和启动，视觉请求明确失败关闭。
3. 人工验收清单已创建并把缺图项目标记 BLOCKED。
4. 未对真实摄像头或主动场景重构做虚假完成声明。

“代码完成”不等于“真实图片/VLM 设备效果已验收”。后者必须等待用户补齐图片数据后另行执行清单。

---

## 11. 关键测试矩阵

| 场景 | VisionRequirement | Tool 可见 | ToolChoice | Deadline | 预期结果 |
|---|---|---:|---|---:|---|
| 前方是什么 | REQUIRED | 是 | REQUIRED | 60s | 必须有视觉证据 |
| 用前置摄像头看看 | REQUIRED | 是 | REQUIRED | 60s | 必须调用 Tool |
| 那个红色东西是什么 | OPTIONAL | 是 | AUTO | 60s | Tool 或澄清 |
| 介绍前方碰撞预警 | NONE | 否 | 现有语义 | 30s | 普通知识回答 |
| 生成汽车图片 | NONE | 否 | 现有语义 | 30s | 不调用视觉 Tool |
| 不要看摄像头 | NONE | 否 | 现有语义 | 30s | 不调用视觉 Tool |
| 看前方并打开车窗 | COMPOUND | 否 | 不调用模型或固定澄清 | 30s | 要求拆分请求 |
| REQUIRED + 空配置 | REQUIRED | 是 | REQUIRED | 60s | VISION_EVIDENCE_UNAVAILABLE |
| REQUIRED + Tool 无调用 | REQUIRED | 是 | REQUIRED | 60s | 重试一次后失败 |
| REQUIRED + Tool 成功 | REQUIRED | 是 | NONE（总结轮） | 剩余时间 | 基于证据总结 |
| 普通 CONTROL | 不适用 | 不适用 | 不适用 | 旧语义 | 保持功能 |
| IMAGE/VOICE | 不适用 | 不适用 | 不适用 | 不占主链 | UNSUPPORTED_INPUT_TYPE |

---

## 12. 稳定错误协议

### 12.1 Tool 内部 status

Tool JSON 使用第 8 节定义的细粒度 status，供 Loop、Trace 和测试诊断。

### 12.2 AgentResponse 外部错误

外部保持较少、稳定的错误类型：

| errorType/status | 用户文本 | errorDetail |
|---|---|---|
| `VISION_EVIDENCE_UNAVAILABLE` | 暂时无法获取有效的前向视觉信息 | Tool 的稳定 status/reason |
| `TIMEOUT` | 系统：请求超时 | deadline 阶段原因 |
| `CANCELLED` | 系统：请求已取消 | cancel reason |
| `UNSUPPORTED_INPUT_TYPE` | 当前 Agent 不再处理该输入类型 | 原 inputType |

禁止把 VLM 原始响应、堆栈、asset 绝对路径或网络响应 body 放入面向用户文本。

---

## 13. Goal 模式执行协议

后续子 Agent 使用 Goal 模式实施时，应遵循：

1. Goal objective 必须引用本文档路径并声明“严格按 Phase 1-4 实施”。
2. 不得把计划编写状态误认为业务代码已完成。
3. 每完成一个 Task 即勾选本文档对应 checkbox 或在独立执行记录中保存等价状态。
4. 每个 Phase 结束必须运行该阶段测试，失败不得跳到下一 Phase。
5. 遇到第 4.3 节决策点必须暂停并询问用户。
6. 允许修复本计划直接引入的编译/测试问题；不得顺手重构无关模块。
7. 保护执行前已有工作树改动，不覆盖 `docs/overview/eval/` 或其他用户文件。
8. 图片数据缺失是已知前置条件：
   - 不创建伪图片绕过。
   - 不从互联网下载图片。
   - 不把 legacy audi.jpg 当默认图。
   - 以 CONFIG_NOT_READY 和 BLOCKED 清单完成代码边界。
9. 只有在四阶段代码、自动化验证、文档和人工清单全部完成后，才能将代码实施 Goal 标记 complete。
10. Goal complete 时仍需明确：“真实图片 + DashScope + 设备/Phoenix 端到端验收待用户补图后执行”。

---

## 14. 最终交付物

后续实施完成后必须交付：

- VisionIntentPolicy 及路由测试矩阵。
- RequestSession 中的视觉决策和 30/60 秒 deadline。
- 可空启动的 Demo 图片配置和说明目录。
- DemoFrontViewImageProvider。
- qwen-vl-max VisionAnalyzer。
- FrontViewVisionTool 和结构化结果协议。
- REQUIRED ToolChoice 和视觉证据门禁。
- VLM cancel/timeout 接入。
- vision.image.load/model/result Trace。
- IMAGE/VOICE unsupported 兼容响应。
- CONTROL/主动场景链保留证据。
- 完整 JVM/assemble/lint 结果。
- front-view vision overview。
- 缺图条件下的人工验收清单及 BLOCKED 标记。

完成后的准确项目表述应是：

> AIAgent 已在统一 TEXT Runtime/Context/Tool Loop 中实现可配置 Demo 图片的前向视觉问答能力，通过规则候选路由、LangChain4j Tool Choice、结构化 VLM 证据、统一超时取消与 Trace，保证明确视觉请求只有在真实执行视觉 Tool 后才能生成视觉结论；真实摄像头接入不属于当前阶段。
