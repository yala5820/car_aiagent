# Prompt 系统重构总结

## 概述

对 AIAgent 项目中所有硬编码的 Prompt 文本进行一次系统性重构，涵盖模板外置、LangChain4j PromptTemplate 标准化、动态切换能力预留、以及多源 system prompt 的归并统一。重构后所有 Prompt 文本从 Java/Kotlin 源码中移除，迁移至外部模板文件。

---

## 一、重构前的项目情况

### 1.1 硬编码现状

重构前，项目中所有 Prompt 文本全部以 Java 字符串拼接的方式写在源码中。共涉及 **6 个系统提示词定义、9 个额外硬编码指令字符串**，分布在 6 个文件中：

| 文件 | 行数 | 角色 | 用途 |
|------|------|------|------|
| `core/MainAgentLoop.java` | 205-226 | 车载AI助手 | 主 Agent 循环的系统提示词 |
| `engines/chat/ChatServer.java` | 50-92 | 车载AI助手 | 对话引擎的系统提示词 |
| `engines/sceneserver/SceneServer.java` | 47-66 | 车载AI助手 | 场景服务引擎的系统提示词 |
| `engines/scenematch/SceneMatch.java` | 77-86 | 场景识别专家 | VL 场景识别的任务提示词 |
| `tools/vision/vl/VlManager.java` | 39-50 | 窗景随问 | 多模态视觉问答的系统提示词 |
| `data/local/LocalPrompt.kt` | 4-448 | 智能车载小助手 | 实验性 Prompt（约 412 行，未接入主流程） |
| `SceneServer.java` | 111-120 | — | 主动控车指令（嵌入在方法体中） |
| `SceneServer.java` | 208 | — | 文本整合指令 |
| 多处 | — | — | "车辆状态"、"场景描述" 等固定标签字符串 |

### 1.2 三套互相矛盾的系统提示词

`MainAgentLoop`、`ChatServer`、`SceneServer` 中各自声明的系统提示词描述的是同一个"车载AI助手"角色，但内容不一致：

| 对比项 | MainAgentLoop | ChatServer | SceneServer |
|--------|---------------|------------|-------------|
| 角色定义 | 精简版 | 详细版 | 精简版 |
| "禁止承诺"规则 | 无 | 出现 4 次 | 无 |
| 安全场景提示 | 无 | 无 | 有（施工/火灾/车祸等） |
| 通用对话规则 | 概述 | 详细（含娱乐互动、百科问答规则） | 概述 |
| 前向视野规则 | 概述 | 详细强实时性要求 | 无 |
| 字数限制 | 30秒 | 30秒 | 80字 |

同一个角色，三份各有侧重的描述，LLM 的行为一致性无法保证。

### 1.3 技术债

- Prompt 修改必须改 Java 源码 → 重新编译 APK → 重新部署，无法快速迭代
- 所有模板变量（如车辆状态注入）通过 `+` 字符串拼接实现，无标准化模板语法
- 无抽象层，更换 Prompt 或 A/B 测试需要大面积改动 Java 代码
- `ChatServer.java:74` 存在完全重复的"天气查询"行（复制粘贴未清理）

---

## 二、发现的问题与解决过程

### 问题 1：Prompt 与代码强耦合

**发现：**

所有 Prompt 文本作为 Java String 常量写在源码中。`buildSystemPrompt()` 方法返回拼接的字符串，`SYSTEM_MSG` 常量直接写在类中。修改 Prompt 措辞等于修改 Java 代码：

```java
// MainAgentLoop.java — 22 行 Java 字符串拼接
private String buildSystemPrompt() {
    return "角色定义：\n" +
            "    你是一位专业、友好且高度智能的车载AI助手...\n" +
            ...
}
```

**思考：**

Prompt 本质上属于"配置"而非"代码"。Prompt 的修改频率远高于业务逻辑的修改频率。产品经理可能会频繁调整措辞、添加规则、修正语气。将 Prompt 与代码耦合意味着每次修改都需要走完整的 CI/CD 流水线（编译 → 打包 → 部署）。

从 LangChain4j 框架的角度看，框架提供了 `PromptTemplate` 类专门用于模板加载和渲染，使用了 `{{variable}}` 占位符语法。这是框架推荐的 Prompt 处理方式。此外 Android 的 `assets/` 目录天然适合存放这类配置文件，项目中已有从 assets 加载文件的模式（VlManager.java:139）。

**解决：**

将 Prompt 文本全部外置到 `app/src/main/assets/prompts/` 目录下的 `.txt` 文件中。使用 LangChain4j 的 `PromptTemplate` 进行加载和渲染：

```
app/src/main/assets/prompts/
├── system/
│   ├── assistant_default.txt    # 默认车载AI助手系统提示词
│   └── assistant_scene.txt      # 场景服务专用补充系统提示词
├── task/
│   ├── scene_recognition.txt    # 场景识别 Prompt
│   └── front_view_qa.txt        # 前向视野问答 Prompt
├── user/
│   ├── vehicle_status.txt       # 车辆状态注入 {{vehicle_status}}
│   ├── scene_description.txt    # 场景描述注入 {{scene_description}}
│   ├── active_control.txt       # 主动控车指令
│   └── summarize.txt            # 文本整合 {{first_part}} {{second_part}}
└── messages/
    └── vl_warning.txt           # VL 响应警告后缀
```

模板文件使用 `{{variable}}` 双花括号语法（LangChain4j 原生支持），内置变量 `{{current_date}}`、`{{current_time}}` 自动解析。

---

### 问题 2：三套系统提示词内容不一致

**发现：**

MainAgentLoop、ChatServer、SceneServer 各自维护了一份系统提示词，描述同一个"车载AI助手"角色。ChatServer 版本长达 42 行，多次重复"禁止承诺无法完成的用户请求"，而 MainAgentLoop 版本只有 22 行且没有这一规则。SceneServer 版本有安全场景规则但其他两个没有。

这意味着同一个 LLM API（qwen-turbo）在不同入口接收不同的角色设定，行为不一致。如果 LLM 在 SceneServer 中因为"安全驾驶建议"的规则给出了合规的回答，但在 MainAgentLoop 中因为缺少该规则给出了不合规的回答，定位和修复成本都很高。

**思考：**

多份系统提示词的根源在于"每个引擎各自处理自己的 Prompt"的架构风格。在工具系统重构前，每个引擎有自己独立的 hasTool/handleToolRequest 分发链。Prompt 的"各自为政"与工具调度的"各自为政"是同一个问题的不同表现。

解决这个问题需要从"分散"回归"统一"：
1. 将三个角色的系统提示词合并为统一的默认助手提示词
2. 场景服务因为使用场景，可以保留补充提示词（只包含场景特有的规则，不重复默认规则）
3. 两份提示词在文件系统中物理隔离（`system/assistant_default.txt` + `system/assistant_scene.txt`），内容上不重叠

**解决：**

合并前三个提示词为一份统一的 `system/assistant_default.txt`，消除冲突和重复：

- 保留所有 Prompt 中共同的角色定义和核心原则
- 保留覆盖最全的 ChatServer 版本的长尾规则（娱乐互动、百科问答等）
- 特意省略"禁止承诺"的 4 次重复，改为"严谨准确"一节统一表述
- SceneServer 的安全规则、字数限制移入 `system/assistant_scene.txt` 作为场景专用补充
- 外部化后 MainAgentLoop 中的 `buildSystemPrompt()` 删除

---

### 问题 3：无模板引擎、无标准化占位符语法

**发现：**

车辆状态注入使用方式不统一：

```java
// MainAgentLoop: 写入 UserMessage 的 name 字段
messages.add(UserMessage.userMessage("车辆状态", getVehicleStatus()));

// SceneServer: 同样方式
chatMemory.add(UserMessage.userMessage("场景描述", scene.to_string()));

// SceneServer userPrompt: 纯字符串拼接，无变量
String userPrompt = "..." + "基于当前座舱内/外场景描述...";
```

没有变量注入机制。当需要注入动态数据（如车辆状态、场景描述）时，要么使用 UserMessage 的 name/value 模式，要么直接在字符串中拼接。没有统一的模板变量语法。

**思考：**

LangChain4j 的 `PromptTemplate` 提供了标准的 `{{variable_name}}` 占位符语法，并且支持 `{{current_date}}` 等内置变量。使用这个机制可以：
1. 统一所有动态数据的注入方式
2. 模板文件本身是自描述的——看文件就知道需要哪些变量
3. 渲染时通过 `Map.of("key", value)` 传入变量，类型安全

**解决：**

定义标准的模板变量命名规范：

```
{{vehicle_status}}     # 车辆状态 JSON
{{scene_description}}  # 场景描述 JSON
{{first_part}}         # 第一段文本
{{second_part}}        # 第二段文本
```

所有动态数据注入统一使用 `promptManager.render(templateName, variables)` 方式：

```java
// 统一后的调用方式
String text = promptManager.render(PromptConstants.USER_VEHICLE_STATUS,
    Map.of("vehicle_status", getVehicleStatus()));
messages.add(UserMessage.userMessage(text));
```

---

### 问题 4：无 Prompt 管理抽象层

**发现：**

1. 修改 Prompt 需要修改 Java 源码
2. 不能根据上下文（如场景类型、用户身份、时间段）切换 Prompt
3. 没有缓机制——每次调用都重新拼接字符串

**思考：**

设计一个 Prompt 管理系统需要三个层次：
1. **加载层** —— 从外部存储加载模板文件，缓存解析结果
2. **渲染层** —— 将模板 + 变量合并为最终文本
3. **选择层** —— 根据上下文决定使用哪个模板

其中"选择层"是预留动态切换能力的关键。通过策略模式（`PromptSelector` 接口），将"选择模板"和"渲染模板"分离。后续可以根据 scene_type、time_of_day、user_profile 等上下文切换 Prompt，而不需要修改调用方代码。

**解决：**

新建三个类：

**`PromptManager`** — 模板加载与渲染
- 从 Android assets 目录加载 `.txt` 文件
- 使用 `ConcurrentHashMap` 缓存 `PromptTemplate` 实例
- 线程安全的懒加载（首次 `render()` 时读取并缓存）
- 提供 `reload()` 方法清除缓存（支持热加载）

**`PromptConstants`** — 模板名称常量
- 所有模板路径集中定义，避免各处硬编码字符串

**`PromptSelector`** — 动态模板选择接口
```java
@FunctionalInterface
public interface PromptSelector {
    String select(Map<String, Object> context);
}
```
内置三个实现：
| 实现 | 逻辑 |
|------|------|
| `DefaultPromptSelector` | 始终返回默认助手提示词 |
| `ScenePromptSelector` | 根据 `context.get("scene_type")` 切换到场景专用提示词 |
| `CompositePromptSelector` | 链式组合多个 Selector，按优先级匹配 |

```
┌─────────────────────────────────────────────────────┐
│                   PromptManager                      │
│  缓存 Map<String, PromptTemplate>                    │
│  render(templateName, variables) → String            │
│  reload() → 清除缓存                                  │
└────────────────────┬────────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────────┐
│                 PromptSelector (接口)                 │
│  select(context) → templateName                      │
│                                                      │
│  实现:                                               │
│  DefaultPromptSelector   ScenePromptSelector          │
│  CompositePromptSelector                              │
└─────────────────────────────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────────┐
│                  调用方                              │
│  MainAgentLoop / ChatServer / SceneServer            │
│                                                      │
│  String tmpl = selector.select(context);             │
│  String prompt = promptManager.render(tmpl, vars);   │
└─────────────────────────────────────────────────────┘
```

---

### 问题 5：ChatServer.java 中重复行

**发现：**

`ChatServer.java:74` 与 `73` 行内容接近重复：

```java
"        天气查询：使用对应工具查询天气，回答用户关于天气的对话(未提供地址信息时参考当前地址信息)。\n" +
"        天气查询：使用对应工具查询天气，回答用户关于天气的对话。\n" +   // 重复！
```

明显的复制粘贴遗漏，可能是一个版本迭代的残留。

**思考：**

这类问题在 Prompt 字符串中难以通过 IDE 检查发现，因为它们是字符串内容不是代码。将 Prompt 外置到模板文件后，通过文本 diff 工具可以更容易发现重复。

**解决：**

在外部化过程中，合并为一条规则并移除重复。统一后的模板内容：
```
天气查询：使用对应工具查询天气，未提供地址信息时参考当前地址。
```

---

## 三、改进后项目情况总结

### 3.1 文件结构变化

```
新增:
  app/src/main/assets/prompts/
    system/assistant_default.txt     # 默认系统提示词
    system/assistant_scene.txt       # 场景系统提示词
    task/scene_recognition.txt       # 场景识别任务提示词
    task/front_view_qa.txt           # 前向视野问答提示词
    user/vehicle_status.txt          # 车辆状态注入模板
    user/scene_description.txt       # 场景描述注入模板
    user/active_control.txt          # 主动控车指令
    user/summarize.txt               # 文本整合模板
    messages/vl_warning.txt          # VL 响应警告

  app/src/main/java/com/hirain/aiagent/prompt/
    PromptManager.java               # 模板加载与渲染
    PromptConstants.java             # 模板名称常量
    PromptSelector.java              # 动态切换接口 + 内置实现

修改源码（移除硬编码 Prompt）:
    core/MainAgentLoop.java
    engines/chat/ChatServer.java
    engines/sceneserver/SceneServer.java
    engines/scenematch/SceneMatch.java
    tools/vision/vl/VlManager.java
    AIAgentService.kt

未修改:
    data/local/LocalPrompt.kt        # 已标记为实验性，未接入主流程
```

### 3.2 关键指标对比

| 指标 | 重构前 | 重构后 | 变化 |
|------|--------|--------|------|
| Prompt 存储方式 | Java String 硬编码 | 外部 .txt 模板文件 | 源码与内容分离 |
| 模板文件数量 | 0 | 9 个 .txt 文件 | +9 |
| Java Prompt 常量类 | 0 | 3 个（Manager/Constants/Selector） | +3 |
| 系统提示词定义数 | 3 套不一致定义 | 2 套统一模板（默认+场景补充） | -1 |
| 硬编码行数 | ~150 行字符串拼接 | 0 行（排除 11 字符短指令） | -150 |
| 动态切换能力 | 无 | PromptSelector 接口 + 策略模式 | 新增 |
| 模板语法 | Java `+` 拼接 | LangChain4j `{{variable}}` | 标准化 |
| Prompt 修改流程 | 改源码 → 编译 → 打包 → 部署 | 改 .txt → 部署 | 大幅简化 |
| ChatServer 重复行 | 1 处 | 0 处 | 已修复 |

### 3.3 Prompt 管理架构

```
┌──────────────────────────────────────────────┐
│              assets/prompts/                   │
│  ┌──────────┐  ┌──────────┐  ┌────────────┐  │
│  │ system/  │  │  task/   │  │   user/    │  │
│  │ 2 files  │  │ 2 files  │  │  4 files   │  │
│  └──────────┘  └──────────┘  └────────────┘  │
│  ┌──────────┐                                │
│  │messages/ │                                │
│  │ 1 file   │                                │
│  └──────────┘                                │
└──────────────────┬───────────────────────────┘
                   │ context.getAssets().open()
                   ▼
┌──────────────────────────────────────────────┐
│              PromptManager                    │
│  ConcurrentMap<String, PromptTemplate>         │
│  render(template, vars) → String              │
│  render(template) → String                    │
└──────────────┬───────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────┐
│          PromptSelector (策略模式)              │
│  select(context) → templateName               │
│                                               │
│  DefaultPromptSelector / ScenePromptSelector   │
│  CompositePromptSelector                      │
└──────────────┬───────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────┐
│            调用方（5 个引擎类）                 │
│  MainAgentLoop / ChatServer / SceneServer     │
│  SceneMatch / VlManager                       │
└──────────────────────────────────────────────┘
```

### 3.4 遗留问题

1. **SceneMatch.JSON Schema 未外部化** — `ResponseFormat` 中的 `addStringProperty("name", "...")` 参数描述仍然是硬编码字符串。原因是 JSON Schema 的 description 字段是 API 调用时直接传入的，不适合存放在模板文件中。后续可以评估是否也外部化。

2. **ChatServer/SceneServer 的工具调度** — 这两个引擎仍然使用已被删除的 `hasTool()`/`handleToolRequest()` 方法，无法通过编译。这是之前工具系统重构的遗留问题，与 Prompt 重构无关。

3. **AIDL 编译问题** — Windows 环境下的 Unicode 转义问题，预存在，非本次重构引入。
