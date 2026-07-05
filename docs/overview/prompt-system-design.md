# AIAgent Prompt 系统设计

## 一、系统概述

AIAgent 的 Prompt 系统是一套**模板化、外部化、可插拔**的提示词管理方案。核心思路：将所有 LLM 提示词从 Java/Kotlin 代码中抽离到 `assets/prompts/` 目录下的 `.txt` 文件中，通过 `PromptManager` 统一加载、缓存和渲染，以 `PromptSelector` 接口为上层提供动态切换能力。

**设计目标：**
- **外部化**：修改提示词不需要改代码、重新编译，只需更新 assets 中的 `.txt` 文件
- **标准化**：基于 LangChain4j 的 `PromptTemplate`，使用 `{{variable}}` 语法做变量插值
- **可切换**：通过 `PromptSelector` 接口支持根据上下文动态选择模板
- **轻量**：无第三方模板引擎依赖，全部使用 LangChain4j 内置能力

---

## 二、架构总览

```
┌──────────────────────────────────────────────────────────────────┐
│                       assets/prompts/                             │
│  (9 个 .txt 模板文件，按目录分类)                                  │
│  ├── system/         系统提示词（定义 AI 角色和行为）              │
│  ├── task/           任务提示词（场景识别、视觉问答）              │
│  ├── user/           用户消息模板（状态注入、控车指令）            │
│  └── messages/       消息片段（辅助上下文提醒）                    │
└────────────────────────┬─────────────────────────────────────────┘
                         │ Android Assets 读取
                         ▼
┌──────────────────────────────────────────────────────────────────┐
│  PromptManager.java          模板引擎                              │
│  ├── 从文件路径懒加载 + 全局缓存 (ConcurrentHashMap)              │
│  ├── PromptTemplate.from() 解析 {{variable}} 占位符               │
│  ├── render(name)          渲染无变量模板                          │
│  └── render(name, vars)    渲染带变量模板                          │
└────────────────────────┬─────────────────────────────────────────┘
                         │ 注入 PromptManager 实例
        ┌────────────────┼──────────────────┬───────────────────┐
        ▼                ▼                  ▼                   ▼
┌───────────────┐ ┌──────────────┐ ┌──────────────┐ ┌─────────────────┐
│AgentLoop      │ │SceneMatch    │ │VlManager     │ │SummarizeMerge   │
│Orchestrator   │ │(场景识别)     │ │(视觉问答)     │ │Collector(文本整合)│
│               │ │              │ │              │ │                 │
│注入SystemPrompt│ │渲染任务模板   │ │渲染任务模板    │ │渲染整合模板      │
│+ 长期记忆注入   │ │+ LLM 调用    │ │+ 警告消息注入  │ │+ LLM 调用       │
└───────────────┘ └──────────────┘ └──────────────┘ └─────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────────────┐
│  PromptSelector.java    动态选择接口（内置 3 种实现）              │
│  ├── DefaultPromptSelector    始终返回 SYSTEM_ASSISTANT_DEFAULT   │
│  ├── ScenePromptSelector      根据 scene_type 切换场景/默认       │
│  └── CompositePromptSelector  链式组合多个 Selector               │
└──────────────────────────────────────────────────────────────────┘
```

---

## 三、模板文件详解（9 个 .txt）

所有模板文件以 `assets/prompts/` 为根目录，使用 LangChain4j `PromptTemplate` 做变量插值（语法 `{{variable}}`）。

### 3.1 系统提示词（`system/`）

#### `system/assistant_default.txt` — 默认车载 AI 助手

**用途：** 普通对话和车控场景下 AI 的系统提示词，定义 AI 的角色、原则和能力边界。

**内容要点：**
- **角色定义**：专业、友好、高度智能的车载 AI 助手
- **核心原则**：认知友好、上下文感知、主动智能、严谨准确
- **功能规范**：通用对话、天气查询、推荐、前向视野互动、精准控车、模糊控车
- **交互规范**：单次语音不超过 30 秒、模糊控车二次确认
- **能力边界**：超出功能规范以"系统不支持"礼貌拒绝

**使用方：** `AgentLoopOrchestrator.injectSystemPrompt()`

#### `system/assistant_scene.txt` — 场景服务系统提示词

**用途：** 场景触发时的系统提示词，定义场景服务 AI 的特殊行为。

**内容要点：**
- 安全相关场景（施工、火灾、大雾雨雪等）必须提供安全驾驶建议
- 主动智能：预测用户需求，在适当时机提供建议
- 交互规范：描述场景 + 概括工具调用 + 安全建议，80 字以内

**使用方：** `AgentLoopOrchestrator`（通过 `AgentConfigFactory.createScenePersona()` 配置）

**切换逻辑：** `PromptSelector.ScenePromptSelector` 判断：如果 `scene_type` 为非空有效场景名，则使用 `assistant_scene`，否则使用 `assistant_default`

---

### 3.2 任务提示词（`task/`）

#### `task/scene_recognition.txt` — 场景识别

**用途：** 对摄像头图片进行场景分类，输出结构化 JSON。

**内容要点：**
- 输出纯 JSON，无额外文本
- 两个字段：`name`（场景名称枚举：舱外浓烟/雨雪天气/工程施工/乘客休息/驾驶员疲劳/其他）和 `description`（场景描述 + 建议）

**使用方：** `SceneMatch.vl_scene_match()`，将模板渲染后作为 LLM 消息调用多模态模型

**关键设计：** 场景名称被限定为 6 种精确枚举值，保证 `PromptSelector.ScenePromptSelector` 可以准确判断。

#### `task/front_view_qa.txt` — 前向视野问答

**用途：** 视觉问答（VLM）的系统提示词，定义驾驶员询问前方视野时的行为。

**内容要点：**
- 角色："窗景随问"
- 聚焦前方视野：仅关注前置摄像头拍摄的道路及周边
- 安全优先、不确定时诚实回应（"看不太清楚"）
- 不支持多轮记忆：每次独立交互

**使用方：** `VlManager`，在 `frontCameraInteractionPositive()` 中渲染并作为系统消息传递给视觉模型

---

### 3.3 用户消息模板（`user/`）

#### `user/vehicle_status.txt` — 车辆状态注入

**模板内容：**
```
车辆状态：{{vehicle_status}}
```

**用途：** 将整车状态 JSON 作为用户消息注入到对话上下文中。

**使用方：** `VehicleStatusPreProcessor`（一个 PreProcessor 实现），在每次 Agent 迭代开头注入

**{{vehicle_status}} 值来源：** `VehicleStateMachine` 的 8 个 `getXxxStatus()` 方法聚合而成的 JSON 字符串

#### `user/scene_description.txt` — 场景描述注入

**模板内容：**
```
场景描述：{{scene_description}}
```

**用途：** 将场景识别结果的详细描述注入到对话上下文中。

**使用方：** `SceneContextPreProcessor`（一个 PreProcessor 实现），在场景 persona 的每次迭代开头注入

#### `user/active_control.txt` — 主动控车指令

**用途：** 场景触发时向 LLM 发出的主动控车指令模板。

**内容要点：**
- 基于场景描述主动将座舱调节至最理想状态
- 工具使用规则：空调温度条件限制（雪天 27-30°C、疲劳 19-22°C、休息舒适）、关窗联动、雪天加热限制、雪地模式切换

**使用方：** `ActiveControlPreProcessor`，在已检测到有效场景时向 LLM 注入这个指令，同时抑制用户输入

#### `user/summarize.txt` — 文本整合

**模板内容：**
```
整合下面两段话，字数80字以内：
{{first_part}}
{{second_part}}
```

**用途：** 将 AI 的初始回复和场景相关回复拼接整合，输出一个精简的最终回复（80 字以内）。

**使用方：** `SummarizeMergeCollector`（一个 ResultCollector 实现），在场景 persona 执行完后调用 LLM 做一次文本整合

---

### 3.4 消息片段（`messages/`）

#### `messages/vl_warning.txt` — VL 响应警告

**模板内容：**
```
（该响应仅对本次前向视野互动有效，对后续前向视野互动意图属于无效的记忆，必须重新调用摄像头数据识别工具！）
```

**用途：** 在 VL 回复后注入一条系统消息，提醒 LLM 下次前向视野查询需要重新调用工具获取新数据，避免使用过时的记忆。

**使用方：**
- `VlWarningPreProcessor`（PreProcessor 实现）：在后续迭代中注入这条消息
- `VlManager`：在视觉问答响应后附加到 AI 消息上

---

## 四、核心类详解

### 4.1 PromptManager.java — 模板引擎

**包路径：** `core/prompt/PromptManager.java`

核心职责是**加载、缓存、渲染**三类操作：

| 方法 | 功能 | 线程安全 |
|------|------|----------|
| `render(templateName)` | 渲染无变量模板 | ✅ ConcurrentHashMap 缓存 |
| `render(templateName, variables)` | 渲染带 `{{variable}}` 的模板 | ✅ |
| `reload()` | 清除缓存，重新从 assets 加载 | ✅ |

**加载机制：**
- 模板路径：`assets/prompts/{templateName}.txt`
- 懒加载：首次 `render()` 时从 assets 读取，然后缓存到 `ConcurrentHashMap`
- 缓存键：模板名称（不含 `.txt` 后缀）
- 渲染引擎：`dev.langchain4j.model.input.PromptTemplate`

**异常处理：** 模板文件不存在时抛出 `IllegalArgumentException`

### 4.2 PromptConstants.java — 模板名常量

**包路径：** `core/prompt/PromptConstants.java`

集中管理所有模板的引用路径，避免字符串散落在代码中。分为四组常量：

```
system/assistant_default    → 对话助手系统提示词
system/assistant_scene      → 场景服务系统提示词
task/scene_recognition      → 场景识别任务
task/front_view_qa          → 前向视野问答
user/vehicle_status         → 车辆状态注入
user/scene_description      → 场景描述注入
user/active_control         → 主动控车指令
user/summarize              → 文本整合
messages/vl_warning         → VL 响应警告
```

### 4.3 PromptSelector.java — 动态选择器

**包路径：** `core/prompt/PromptSelector.java`

`@FunctionalInterface`，提供从上下文中选择模板名称的能力。包含三种内置实现：

| 实现 | 选择逻辑 | 使用场景 |
|------|----------|----------|
| `DefaultPromptSelector` | 始终返回 `assistant_default` | 默认配置 |
| `ScenePromptSelector` | `scene_type` 有效 → `assistant_scene`，否则 → `assistant_default` | 场景路由 |
| `CompositePromptSelector` | 按顺序调用多个 Selector，取第一个非 null 结果 | 链式组合 |

**现状：** `AgentConfigFactory` 目前直接在构造 `AgentConfig` 时指定 `systemPromptTemplateName` 常量，`PromptSelector` 接口已定义但尚未在 Orchestrator 管线中集成。它是为"运行时根据上下文动态切换人格"预留的扩展点。

---

## 五、Prompt 在整个 Agent 管线中的流动

一次完整的对话请求中，Prompt 模板的流动路径：

```
用户输入 "空调设置为25度"
  │
  ▼
AgentLoopOrchestrator.execute()
  │
  ├── injectSystemPrompt()                              ← 读取 persona 对应的 system prompt 模板
  │     ├── promptManager.render(config.templateName())  ← assistant_default.txt 渲染
  │     └── memoryOrchestrator.prepareSystemPrompt()      ← 注入长期记忆
  │
  ├── PreProcessor 链（每次迭代重复）                      ← 以下每条都是被渲染后注入的消息
  │     ├── VehicleStatusPreProcessor                    ← vehicle_status.txt 注入
  │     ├── TimeContextPreProcessor                       ← 非模板，代码生成时间字符串
  │     ├── MemoryPreProcessor                            ← 非模板，代码生成记忆参考
  │     ├── SceneContextPreProcessor                       ← scene_description.txt 注入
  │     ├── ActiveControlPreProcessor                     ← active_control.txt 注入
  │     └── VlWarningPreProcessor                         ← vl_warning.txt 注入
  │
  ├── ModelCaller → LLM 调用                              ← 混合所有消息发给 LLM
  │
  ├── LLM 返回 ToolCall                                    ← LLM 决定调用工具
  │     └── ToolExecutor → VehicleStateMachine             ← 车控操作
  │
  ├── LLM 返回文本                                         ← 最终回复
  │
  ├── PostProcessor 链                                     ← 后处理
  └── ResultCollector                                      ← 结果收集
        └── SummarizeMergeCollector                        ← summarize.txt + LLM 整合
```

---

## 六、设计要点

### 6.1 为什么用 .txt 文件而非代码内嵌？

1. **迭代效率**：产品同学或业务方可以直接修改 .txt 文件调整 AI 行为，无需开发者介入编译
2. **版本管理**：提示词的变更可以被 git 追踪，方便回溯和审查
3. **环境区分**：未来可扩展到开发/生产使用不同模板目录

### 6.2 为什么用 LangChain4j PromptTemplate？

LangChain4j 的 `PromptTemplate` 是项目中已有的依赖，引入额外模板引擎（如 Handlebars、Freemarker）会增加 APK 体积和编译复杂度。`{{variable}}` 语法对非技术人员也很直观。

### 6.3 模板粒度设计

将提示词拆分为 9 个小文件，而非一个大文件，原因是：

- **低耦合**：修改场景识别逻辑不影响对话助手行为
- **上下文精准**：PreProcessor 只在需要时注入特定模板（如场景描述只在场景 persona 的迭代中注入）
- **LLM 注意力聚焦**：每次给 LLM 的消息是精确且精简的，避免不相关的系统指令稀释注意力

---

## 七、下一步发展方向

### 短期可落地

| 方向 | 说明 | 优先级 |
|------|------|--------|
| **PromptSelector 管线集成** | 目前 `PromptSelector` 接口已定义但在 Orchestrator 中未使用。需在 `AgentConfig` 中增加 Selector 字段，`injectSystemPrompt()` 根据上下文动态选择模板 | 高 |
| **A/B 测试支持** | 同一场景使用不同 Prompt 版本，通过 Selector 按用户/设备/时间比例分配 | 中 |
| **运行时热加载** | PromptManager 已有 `reload()` 方法，可扩展为 assets 文件变动时自动重载。但 Android assets 在 APK 打包后只读，热加载需要将模板复制到可写目录 | 中 |

### 中期方向

| 方向 | 说明 |
|------|------|
| **模板版本管理** | 在 .txt 文件头部加 `version` 元信息，运行时选择最新版本 |
| **动态 Prompt 组装** | 将角色定义、核心原则、功能规范拆为独立片段，根据场景动态拼接 |
| **多语言支持** | 按语言子目录（`zh/`、`en/`）组织模板，根据系统语言自动选择 |

### 遗留问题

| 问题 | 描述 |
|------|------|
| **`[角色:助理]` 前缀冲突** | Launcher 在用户消息前添加 `[角色:助理]` 前缀，覆盖了 System Prompt 中的角色定义。LLM 以"助理"身份直接回复文本，不调用车控工具。需上游协调或 Service 端去除该前缀 |
| **PromptSelector 未实战** | 接口已定义并单元测试通过，但未在 Orchestrator 管线中集成。`AgentConfig` 目前硬编码 `systemPromptTemplateName` |
| **模板文件数量膨胀** | 当前 9 个模板尚可手动管理。当模板超过 20 个时，建议引入 `prompts/index.json` 清单文件管理元信息 |
