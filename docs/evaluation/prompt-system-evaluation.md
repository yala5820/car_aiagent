# AIAgent Prompt 系统评价报告

生成日期：2026-07-03

## 1. 评估范围

本报告基于当前仓库的静态源码和文档审查，重点检查 Prompt 系统的技术栈、架构设计、实际接入情况、缺失设计和后续优化方向。参考资料包括：

- `docs/overview/prompt-system-design.md`
- `docs/act_summary/prompt-system-refactoring-summary.md`
- `docs/plan/prompt-system-refactoring-plan.md`
- `app/src/main/assets/prompts/`
- `app/src/main/java/com/hirain/aiagent/prompt/`
- `AgentLoopOrchestrator`、`AgentConfigFactory`、各类 `PreProcessor`、`SceneMatch`、`VlManager`、`MemoryCompressor`、`MemoryExtractor`

本次未修改业务代码，未运行真实模型调用，也未执行 Gradle 编译。结论以源码结构和静态调用链为依据。

## 2. 总体结论

当前 Prompt 系统已经完成了“从主业务代码中抽离核心提示词”的第一阶段目标，具备清晰的模板目录、统一的 `PromptManager`、集中常量和主 Agent/场景/VL 关键链路接入。对于当前 Demo 阶段的车机 AI Agent 来说，技术选型总体合理，复杂度控制得当。

但从“完整 Prompt 系统”的标准看，目前仍是一个基础可用版本，而不是闭环成熟版本。主要短板在于：

1. `PromptSelector` 已定义但未接入 Orchestrator，动态选择能力尚未真正落地。
2. Prompt 外部化覆盖不完整，记忆压缩、记忆提取、旧 `LocalPrompt.kt`、场景 JSON Schema 描述等仍保留源码内提示词。
3. `assets` 方案只能做到“代码外管理”，不能做到运行时热更新；文档中“无需重新编译”的表述需要修正。
4. 缺少模板清单、变量校验、版本管理、自动化测试和 prompt 效果评估数据集。
5. 车辆状态、场景、记忆等上下文均以 `UserMessage` 注入，安全约束与事实上下文的角色层级不够清晰，存在被用户输入或模型注意力稀释的风险。

建议将当前状态定义为：核心链路可用，架构方向正确，工程治理和运行时闭环不足。完成度约为 65% - 75%，其中模板加载和主链路接入较完整，动态路由、全量治理、验证体系明显不足。

## 3. 技术栈设置评价

### 3.1 LangChain4j PromptTemplate

当前使用 `dev.langchain4j.model.input.PromptTemplate` 加载 `{{variable}}` 模板，符合项目已使用 LangChain4j 的整体技术路线。优点是无需引入 Freemarker、Handlebars 等额外模板引擎，减少 APK 体积、依赖风险和学习成本。

结论：合理。

需要注意的是，`PromptTemplate` 适合简单变量插值，不适合复杂条件、循环、模板继承、变量 schema 校验等高级能力。如果后续 Prompt 需要按车型、语言、场景、用户画像动态组装，单纯依赖 `.txt + Map` 会逐渐吃力。

### 3.2 Android assets 存储模板

模板存放在 `app/src/main/assets/prompts/` 是合理的：它不参与 Android resource id 生成，适合保存原始文本文件；`PromptManager` 通过 `context.getAssets().open()` 读取，和 Android 运行环境匹配。

结论：作为 APK 内置模板合理；作为运行时热更新机制不充分。

需要纠偏的是，assets 会被打包进 APK。修改 `.txt` 后仍然需要重新打包和部署才能生效，除非额外实现“首次启动复制到可写目录 / 远端拉取 / 调试覆盖目录”等机制。因此设计文档中“修改 assets 中的 `.txt` 文件无需重新编译”的说法只适用于源码维护体验，不适用于已安装 APK 的运行时更新。

### 3.3 PromptManager 缓存设计

`PromptManager` 使用 `ConcurrentHashMap<String, PromptTemplate>` 懒加载模板，线程安全、实现简单，适合前台 Service 内多请求复用。`reload()` 清缓存的接口也为后续调试和热加载预留了入口。

结论：当前规模下合理。

不足是没有启动时预加载和模板完整性检查。模板缺失、变量缺失、语法错误会在运行时第一次调用时暴露，且异常会沿 Agent 主流程转成通用失败，不利于定位。

### 3.4 模型和 Prompt 分工

当前模型分工大体合理：

- `qwen-turbo`：主对话与工具调用。
- `qwen-flash`：场景主动控车和文本汇总。
- `qwen-vl-max`：前向视野问答。
- `qwen3-vl-plus`：当前 `SceneMatch` 实际用于场景识别。

需要注意文档和实现存在轻微不一致：项目总览中强调 `qwen-flash` 用于场景，实际场景识别是 `SceneMatch` 内的 VLM `qwen3-vl-plus`，场景动作 Agent 才是 `qwen-flash`。这不是架构错误，但需要在文档中明确区分“场景识别模型”和“场景控车 Agent 模型”。

## 4. 当前设计合理性评价

### 4.1 已完成且设计较好的部分

1. 模板目录分层清晰

`system/`、`task/`、`user/`、`messages/` 四类目录能表达模板用途。9 个模板文件和 `PromptConstants` 基本一一对应，降低了字符串散落风险。

2. PromptManager 职责单一

`PromptManager` 只负责加载、缓存、渲染，不绑定业务上下文。这个边界清晰，后续可替换存储来源而不影响上层调用。

3. AgentConfigFactory 已将 Prompt 注入纳入 persona 配置

`createChatPersona()`、`createScenePersona()`、`createVisionQAPersona()` 分别指定系统模板和处理链，说明 Prompt 已经成为 Agent 配置的一部分，而不是散落在业务方法里。

4. PreProcessor 机制适合做上下文注入

车辆状态、场景描述、主动控车指令通过 PreProcessor 注入，和 Agent Loop 的组件化方向一致。后续如果要增加路线、DMS、座舱模式等上下文，也可以沿用这一模式。

5. 场景识别结合 ResponseFormat JSON Schema

`SceneMatch` 不只依赖自然语言 prompt，还使用 LangChain4j `ResponseFormat` 约束 JSON 输出。对于场景枚举识别，这比只在 prompt 里写“输出 JSON”更稳。

6. Trace 已记录 prompt 组装信息

`AgentLoopOrchestrator` 中存在 prompt assembly span，能够记录 transient messages、memory messages、all messages 数量和工具规格。对后续排查“为什么模型没调工具”很有价值。

### 4.2 架构和逻辑上的主要缺口

#### 4.2.1 PromptSelector 没有接入主流程

`PromptSelector` 当前只是接口和内置实现，`AgentConfig` 仍保存固定的 `systemPromptTemplateName`，`AgentLoopOrchestrator.injectSystemPrompt()` 直接渲染固定模板。也就是说，文档中“根据上下文动态切换模板”的能力尚未真正发生。

影响：

- 不能根据 `scene_type`、用户画像、输入类型、驾驶状态动态切换系统提示词。
- `ScenePromptSelector` 的有效性只能停留在理论层面。
- 后续做 A/B 测试或灰度 prompt 仍需要改工厂或配置代码。

建议：在 `AgentConfig` 中引入可选 `PromptSelector`，并在 `injectSystemPrompt()` 组装上下文后选择模板；保留固定模板作为默认兼容路径。

#### 4.2.2 Prompt 外部化覆盖不完整

当前核心 9 个模板已外部化，但全项目仍存在多处 LLM 指令或 Prompt 性质文本：

- `MemoryCompressor.buildCompressionPrompt()` 仍在源码内拼接压缩提示词。
- `MemoryExtractor.buildExtractPrompt()` 仍在源码内拼接长期记忆提取提示词。
- `LocalPrompt.kt` 仍保留大段旧式硬编码 Prompt，虽然当前未发现主链路引用。
- `SceneMatch` 的 JSON Schema 字段描述仍硬编码在代码中。
- `TimeContextPreProcessor`、`MemoryPreProcessor` 中的上下文标签仍是源码内中文文本。

影响：

- “所有 Prompt 已外部化”的结论不成立。
- 记忆系统的模型行为难以与 prompt 版本一起管理和回滚。
- 新人容易误以为 `LocalPrompt.kt` 仍是有效主 prompt 来源。

建议：将 Prompt 范围重新定义为“会直接进入 LLM 上下文的文本”，把记忆压缩、记忆提取、上下文标签、VL 警告等纳入统一治理。对于 JSON Schema description，可以先保留在代码中，但需要在报告和文档里明确它属于结构化输出 schema，而非普通模板。

#### 4.2.3 上下文消息角色层级不够清晰

车辆状态、时间、长期记忆参考、场景描述、主动控车指令、VL 警告大多以 `UserMessage` 注入。这样实现简单，但存在角色语义混杂：

- 车辆状态和时间是系统事实，更像环境上下文，不是用户请求。
- 主动控车指令是系统主动任务，不应和真实用户输入同级。
- VL 记忆失效警告是约束规则，更适合放在 system/developer 级别，而不是用户消息或直接追加到用户可见回复中。

影响：

- 用户输入可能覆盖或稀释这些上下文。
- 模型可能把系统注入内容误判为用户自然语言请求。
- 视觉问答警告被追加到最终回复中，会暴露内部策略文本，影响语音播报体验。

建议：按语义分层：

- SystemMessage：角色、安全边界、工具使用必须遵守的规则。
- Context message：车辆状态、时间、位置、场景事实，使用明确分隔符和不可执行声明。
- UserMessage：真实用户输入。
- Internal-only postprocess metadata：VL 失效提示、trace/调试提示，不直接返回给用户。

#### 4.2.4 长期记忆可能重复注入

主 Agent 中同时存在两条记忆注入路径：

- `AgentLoopOrchestrator.injectSystemPrompt()` 调用 `memoryOrchestrator.prepareSystemPrompt()`，把长期记忆追加到 SystemPrompt。
- `AgentConfigFactory.createChatPersona()` 同时配置了 `MemoryPreProcessor`，它又会在首轮注入 `【用户记忆参考】...`。

如果用户存在长期记忆，同一批信息可能同时出现在 SystemMessage 和 transient UserMessage 中。

影响：

- 增加 token 消耗。
- 放大长期记忆对当前请求的影响，可能导致模型过度个性化。
- 记忆内容如果被污染，会同时影响 system 和 user 两个层级。

建议：二选一保留。更推荐在 SystemPrompt 中注入长期稳定偏好，在 PreProcessor 中只注入本轮需要的短期上下文或检索结果，并明确去重策略。

#### 4.2.5 缺少模板清单和变量 schema

当前模板变量靠调用方 `Map.of()` 手动传入，缺少统一清单。例如 `vehicle_status`、`scene_description`、`first_part`、`second_part` 是否齐全，只能靠人工检查。

影响：

- 新增模板后容易漏传变量。
- 模板改名或变量改名不会在编译期暴露。
- 无法快速列出模板用途、负责人、适用 persona、版本、是否允许热更新。

建议新增 `assets/prompts/index.json` 或代码侧 registry，至少记录：

- templateName
- type: system/task/user/message/memory
- requiredVariables
- owner/persona
- version
- description
- allowedModels
- outputContract

#### 4.2.6 缺少自动化验证

当前 `app/src/test` 下没有发现 PromptManager、PromptSelector 或模板变量完整性的专门测试。已有测试主要集中在 trace 和 memory。

建议补充以下测试：

1. 所有 `PromptConstants` 指向的模板文件存在。
2. 所有模板能被 `PromptManager.render()` 成功加载。
3. 带变量模板在传入变量后不残留 `{{...}}`。
4. `PromptSelector.ScenePromptSelector` 对“其他”“无效场景”“雨雪天气”等输入返回符合预期。
5. `AgentConfigFactory` 的三类 persona 使用预期模板、预期 PreProcessor 顺序。
6. 视觉问答输出不应把内部失效警告直接暴露给最终用户，除非这是刻意产品设计。

#### 4.2.7 缺少 Prompt 效果评估体系

当前系统有模板，但没有“模板好坏”的评估闭环。对车控类 Agent 来说，仅能加载模板不够，还需要可重复的任务集验证：

- 精准控车是否调用正确工具。
- 模糊控车是否二次确认。
- 车辆状态注入是否影响决策。
- 场景识别是否稳定输出枚举。
- 主动场景控车是否遵守温度、车窗、加热、驾驶模式约束。
- VL 问答是否每次重新取图，是否避免使用过期记忆。

建议建立 `docs/evaluation/prompt-cases/` 或测试资源文件，维护一组输入、上下文、预期工具调用、预期拒答/确认行为和人工评分项。

## 5. 主要问题清单

| 优先级 | 问题 | 影响 | 建议 |
|---|---|---|---|
| P0 | `PromptSelector` 未接入主流程 | 动态切换只是预留接口，无法支撑场景/用户/实验路由 | 在 `AgentConfig` 和 `AgentLoopOrchestrator` 中接入选择器 |
| P0 | 长期记忆双路径注入 | token 浪费、记忆影响过强、污染风险放大 | 明确 SystemPrompt 与 PreProcessor 的记忆分工 |
| P1 | 记忆压缩/提取 prompt 仍硬编码 | Prompt 治理不完整，无法统一版本化 | 新增 `memory/compress.txt`、`memory/extract.txt` |
| P1 | assets 热更新表述不准确 | 误导后续部署和调试预期 | 文档改为“源码外置，APK 内置”；热更新另设可写目录方案 |
| P1 | 缺少模板变量校验和测试 | 运行时才暴露模板缺失/变量错误 | 增加 PromptManager/模板完整性 JVM 或 Robolectric 测试 |
| P1 | VL 警告直接进入最终回复 | 影响用户体验，暴露内部策略文本 | 改为内部状态或 system 约束，不直接播报 |
| P2 | `LocalPrompt.kt` 旧 prompt 残留 | 新人误读，外部化结论不严谨 | 标记 deprecated 或迁移/删除，需单独确认后执行 |
| P2 | Prompt 缺少版本和清单 | 模板数量增长后难维护 | 增加 `index.json` 或 registry |
| P2 | 场景识别模型文档与实现表述不一致 | 维护者误判模型职责 | 文档区分场景识别 VLM 和场景控车 LLM |
| P2 | 上下文全用 UserMessage 注入 | 角色层级混乱，易被用户输入覆盖 | 建立消息角色规范和分隔符格式 |

## 6. 优化路线建议

### 6.1 短期：补齐闭环，控制风险

1. 接入 `PromptSelector`

保持现有固定模板兼容，同时允许 persona 提供 selector。默认 selector 返回当前固定模板，scene selector 根据 `scene.name` 或 `extraContext` 选择场景模板。

2. 增加 Prompt 静态校验测试

先不引入新依赖，写最小 JVM 测试或 Android instrumented test，覆盖模板存在性和变量渲染。若 `assets` 在 JVM 测试中难以读取，可把变量解析逻辑抽成纯 Java helper，或用 instrumented test 验证 assets。

3. 修正文档表述

明确当前是“APK 内置模板”，不是运行时热更新。`reload()` 只是清缓存，不会让已安装 APK 读取外部新文件。

4. 清理或标注 `LocalPrompt.kt`

如果确认为历史遗留，建议加 `@Deprecated` 和中文说明；若仍有计划使用，则应纳入 prompt 目录体系。

### 6.2 中期：统一治理所有 LLM 文本

1. 将记忆系统 prompt 外部化

新增：

```
assets/prompts/memory/compress.txt
assets/prompts/memory/extract.txt
```

并纳入 `PromptConstants` 与完整性测试。

2. 增加模板清单

建议使用 `prompts/index.json` 描述模板元信息和变量 schema。这样后续可以做自动校验、文档生成和运行时可观测。

3. 规范消息角色

建立项目内约定：哪些内容允许作为 SystemMessage，哪些内容作为 UserMessage，哪些只作为内部 metadata。特别是安全规则、工具强制规则、记忆失效规则，不建议长期放在普通用户消息中。

4. 建立 prompt case 集

用固定输入和上下文检查工具调用、拒答、二次确认、场景控车动作是否符合预期。初期可以先做人工评估表，后续再接入自动化模型评测。

### 6.3 长期：支持实验和运行时迭代

1. Prompt 版本化

模板文件名或清单字段引入版本，例如 `assistant_default@v1`，trace 中记录 prompt version，便于回放和问题定位。

2. A/B 测试和灰度

在 `PromptSelector` 中引入用户、设备、时间、实验桶维度。注意实验配置不应散落在代码中，建议由清单或远端配置驱动。

3. 外部可写目录热加载

如果确实需要不重新打 APK 调整 prompt，可在 debug/工程模式下优先读取 app 私有目录或远端下发目录，失败再回退 assets。生产环境需要签名校验、版本回滚和安全审计。

## 7. 结论

当前 Prompt 系统的方向是正确的：轻量、集中、和 Agent 组件化架构兼容，已经明显优于早期源码内硬编码方式。对于初级车机 AI Agent 中枢，目前可以支撑基础对话、场景服务和视觉问答的模板化管理。

但它还没有达到“完整 Prompt 平台层”的程度。下一步最应该优先处理的是三个闭环：动态选择闭环、全量 Prompt 治理闭环、验证闭环。只要补齐这三点，Prompt 系统就能从“模板抽离”升级为可长期维护、可评估、可演进的工程能力。
