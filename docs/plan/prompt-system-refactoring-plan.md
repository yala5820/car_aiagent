# Prompt 系统重构方案

## 背景

当前项目中所有 Prompt（系统提示词、用户消息模板、指令片段）全部以 Java 字符串拼接的方式硬编码在 6 个源文件中。共涉及 **6 个系统提示词定义、9 个额外硬编码指令字符串**，分散在：

| 文件 | 硬编码 Prompt 数 | 角色 |
|------|-----------------|------|
| `MainAgentLoop.java` | 1 系统提示词 + 1 用户消息模板 | Agent 助手 |
| `ChatServer.java` | 1 系统提示词 + 1 用户消息模板 | 对话引擎 |
| `SceneServer.java` | 1 系统提示词 + 4 用户消息/指令模板 | 场景服务引擎 |
| `SceneMatch.java` | 1 系统提示词 + 1 用户消息 + 1 JSON Schema | 场景识别 |
| `VlManager.java` | 1 系统提示词 + 1 响应后缀 | 视觉问答 |
| `LocalPrompt.kt` | 5 段 Prompt 片段（约 412 行） | 实验性/未使用 |

核心问题：
- 修改 Prompt（微调措辞、添加规则）需要改 Java 源码并重新编译 APK
- 三套系统提示词（MainAgentLoop / ChatServer / SceneServer）描述的是同一个"车载AI助手"角色，但内容不一致、格式不统一、部分规则互相冲突
- Prompt 与 Java 代码耦合，无法做 A/B 测试、版本回滚、或根据场景动态切换
- 不符合 LangChain4j 生态中 `PromptTemplate` + 外部模板文件的最佳实践

---

## 设计目标

1. **抽离硬编码**：所有 Prompt 文本从 Java 源码中移除，迁移至独立的模板文件
2. **统一模板引擎**：使用 LangChain4j 内置的 `PromptTemplate` + `{{variable}}` 语法，不引入额外依赖
3. **动态切换能力**：预留上下文感知接口，支持后续根据场景（场景类型、用户偏好、时间段）动态选择不同 Prompt
4. **风格统一**：所有 `.txt` 模板文件在排版、换行、变量命名上遵循统一规范

---

## 1. 模板文件体系设计

### 1.1 存放位置

采用 Android 标准 resource 路径。由于 `PromptTemplate` 可以直接传入 String 且 Android APK 打包时 `assets/` 目录中的文件可以通过 `context.getAssets().open()` 读取（项目中已有此模式，见 VlManager.java:139），选择 `app/src/main/assets/prompts/` 作为模板根目录。

### 1.2 目录结构

```
app/src/main/assets/prompts/
├── system/
│   ├── assistant_default.txt    # 默认车载AI助手系统提示词（替代 MainAgentLoop / ChatServer / SceneServer 三套分散定义）
│   └── assistant_scene.txt      # 场景服务专用的补充系统提示词（内容合并自 SceneServer.systemPrompt_）
├── task/
│   ├── scene_recognition.txt    # 场景识别 Prompt（替代 SceneMatch.vl_scene_match 中的 systemPrompt）
│   └── front_view_qa.txt        # 前向视野问答 Prompt（替代 VlManager.SYSTEM_MSG）
├── user/
│   ├── vehicle_status.txt       # 车辆状态注入模板（替代 "车辆状态" + getVehicleStatus()）
│   ├── scene_description.txt    # 场景描述注入模板（替代 "场景描述" + scene.to_string()）
│   ├── active_control.txt       # 主动控车指令（替代 SceneServer 中的 userPrompt 硬编码）
│   └── summarize.txt            # 文本整合模板（替代 SceneServer 中的"整合"硬编码）
└── messages/
    └── vl_warning.txt           # VlManager 响应警告后缀（替代 Java String warning）
```

### 1.3 模板语法规范

使用 LangChain4j `PromptTemplate` 原生支持的 `{{variable_name}}` 双花括号语法：

```
你是{{role_description}}的车载AI助手。
今天是{{current_date}}。
当前车辆状态：{{vehicle_status}}
```

LangChain4j 内置变量 `{{current_date}}`、`{{current_time}}`、`{{current_date_time}}` 自动解析，无需手动填充。

### 1.4 模板文件格式规范

每个 `.txt` 文件遵循以下排版规范：

- 文件编码：**UTF-8**
- 换行符：**LF**（与项目的 .gitattributes 配置一致）
- 变量使用小写下划线命名：`{{vehicle_status}}`、`{{driver_intent}}`
- 多行模板用自然换行，不拼接 `\n` 字符串
- 文件末尾保留一个空行

---

## 2. 新增 Java 类设计

### 2.1 `PromptManager` — 模板加载与渲染

位置：`app/src/main/java/com/hirain/aiagent/prompt/PromptManager.java`

**职责**：从 Android assets 目录加载 `.txt` 模板文件，缓存解析后的 `PromptTemplate` 实例，提供渲染接口。

```
核心接口：
  - PromptManager(Context context)
      // 扫描 assets/prompts/ 目录，建立 模板名→文件路径 的索引

  - String render(String templateName, Map<String, Object> variables)
      // 加载模板 → PromptTemplate.apply(variables) → 返回渲染后文本
      // 若模板不存在抛出 IllegalArgumentException

  - void reload()
      // 清除缓存并重新扫描（后续支持热加载时使用）
```

内部使用 `ConcurrentHashMap<String, PromptTemplate>` 缓存已加载的模板。首次 `render()` 时异步读取并解析，后续调用直接从缓存获取。

### 2.2 `PromptSelector` — 动态 Prompt 切换接口

位置：`app/src/main/java/com/hirain/aiagent/prompt/PromptSelector.java`

**职责**：定义根据上下文选择 Prompt 模板的策略接口。调用方通过实现此接口来支持不同场景下的 Prompt 切换。

```
interface PromptSelector {
    /**
     * 根据当前上下文选择最适合的模板名称。
     * @param context 上下文信息（场景类型、用户意图、车辆状态等），不可变 Map
     * @return 模板名称（不含路径前缀和 .txt 后缀），例如 "system/assistant_default"
     */
    String select(Map<String, Object> context);
}
```

**内置默认实现：**

| 实现类 | 选择逻辑 |
|--------|----------|
| `DefaultPromptSelector` | 始终返回 `"system/assistant_default"`（默认行为，等价于当前硬编码） |
| `ScenePromptSelector` | 根据 `context.get("scene_type")` 返回 `"system/assistant_scene"` 或默认模板 |
| `CompositePromptSelector` | 链式组合多个 Selector，按优先级依次匹配 |

### 2.3 `PromptConstants` — 模板名称常量

位置：`app/src/main/java/com/hirain/aiagent/prompt/PromptConstants.java`

**职责**：所有模板名称的字符串常量集中定义，避免各处写字符串字面量。

```java
public final class PromptConstants {
    // System prompts
    public static final String SYSTEM_ASSISTANT_DEFAULT = "system/assistant_default";
    public static final String SYSTEM_ASSISTANT_SCENE = "system/assistant_scene";

    // Task prompts
    public static final String TASK_SCENE_RECOGNITION = "task/scene_recognition";
    public static final String TASK_FRONT_VIEW_QA = "task/front_view_qa";

    // User message templates
    public static final String USER_VEHICLE_STATUS = "user/vehicle_status";
    public static final String USER_SCENE_DESCRIPTION = "user/scene_description";
    public static final String USER_ACTIVE_CONTROL = "user/active_control";
    public static final String USER_SUMMARIZE = "user/summarize";

    // Message fragments
    public static final String MSG_VL_WARNING = "messages/vl_warning";

    private PromptConstants() {}
}
```

---

## 3. 模板文件内容设计

### 3.1 `system/assistant_default.txt`

合并 MainAgentLoop、ChatServer、SceneServer 三套系统提示词，提取共同部分 + 消除冲突：

```
角色定义：
    你是一位专业、友好且高度智能的车载AI助手，专注于提供安全、高效、愉悦的驾驶体验。
    你的核心使命是在保障驾驶安全的前提下，为用户提供全方位的智能座舱服务。

核心原则
    认知友好：使用简化易懂的表达，尽量避免专业术语。
    上下文感知：结合当前驾驶状态、地理位置、时间等上下文提供个性化服务。
    主动智能：能够预测用户需求，但不过度打扰。
    严谨准确：无法完成的用户请求，以当前系统不支持为由礼貌拒绝。

功能规范
    通用对话：自然聊天、娱乐互动（单次不超过1分钟）、百科问答。
    天气查询：使用对应工具查询天气，未提供地址信息时参考当前地址。
    推荐能力：音乐/影视、旅游景点、游玩建议。
    前向视野互动：用户询问前方物体时，必须重新调用工具获取实时前向视野数据。
    精准控车：支持自然语言的车辆控制，复杂指令拆解分步执行。
    模糊控车：识别隐含需求（如"有点冷"自动调高空调温度），需二次确认。

交互规范
    保持简洁，单次语音输出不超过30秒。
    模糊控车需要二次确认。
    功能规范之外的请求，以当前系统不支持为由礼貌拒绝。

能力边界
    能够控制车辆功能、提供天气信息、娱乐和旅途建议。
    上述功能之外的请求，以当前系统不支持为由礼貌拒绝。
```

### 3.2 `system/assistant_scene.txt`

从 SceneServer.systemPrompt_ 提取，增加场景相关的安全规则：

```
角色定义：
    你是一位专业、友好且高度智能的车载AI助手，在智能座舱场景服务中主动为用户调节座舱状态。

核心原则
    安全相关：施工、火灾、车祸、大雾雨雪等极端场景，必须提供安全驾驶建议。
    主动智能：能够预测用户需求，在适当时机提供主动建议，但不过度打扰。

交互规范
    话术必须包括如下内容：
        1. 简单描述场景并概括调用的工具
        2. 必要时提供安全驾驶建议
    自然语言响应限制在80字以内。
```

### 3.3 `task/scene_recognition.txt`

```
你是一个智能座舱场景识别专家，任务：根据摄像头图片生成严格符合JSON Schema的场景描述。

## 输出规则
1. 必须输出纯JSON对象，无任何额外文本
2. 必须包含且仅包含两个字段：
   - name: 场景名称（必须为精确值：'舱外浓烟'/'雨雪天气'/'工程施工'/'乘客休息'/'驾驶员疲劳'/'其他'）
   - description: 结合识别到的场景，对图片中内容进行详细描述、并提供与当前场景匹配的座舱相关舒适调节建议与安全驾驶相关建议。
3. 例如：{"name":"xxx", "description":"xxx"}

## 当前任务
请严格按规则输出JSON
```

### 3.4 `user/active_control.txt`

从 SceneServer.userPrompt 提取：

```
基于当前座舱内/外场景描述，帮我主动控车，使用工具能力一次把座舱调节至最理想状态。

工具使用规则：
    1. 保证仅在以下场景调节空调温度，其他场景禁止调节空调温度：
        1) 雪天寒冷场景，空调设置在27-30摄氏度之间；
        2) 驾驶员疲劳场景，空调温度设置在19-22摄氏度之间。
        3) 乘员休息场景，空调设置为舒适温度。
    2. 如果调节了空调温度，需要同时关闭已开启的车窗。
    3. 保证仅在雪天寒冷场景下控制方向盘与座椅加热，其他场景禁止开启加热。
    4. 雪天需要把行驶模式切换为雪地模式，保证安全驾驶。
```

---

## 4. 源码适配方案

### 4.1 MainAgentLoop.java

**修改前：**
```java
systemPrompt = buildSystemPrompt(); // 返回硬编码字符串
chatMemory.add(SystemMessage.systemMessage(systemPrompt));
...
messages.add(UserMessage.userMessage("车辆状态", getVehicleStatus()));
```

**修改后：**
```java
// 构造函数中：
systemPrompt = promptManager.render(PromptConstants.SYSTEM_ASSISTANT_DEFAULT, Map.of());
chatMemory.add(SystemMessage.systemMessage(systemPrompt));

// executeLoop() 中：
String statusMsg = promptManager.render(PromptConstants.USER_VEHICLE_STATUS,
    Map.of("vehicle_status", getVehicleStatus()));
messages.add(UserMessage.userMessage(statusMsg));
```

移除 `buildSystemPrompt()` 方法。

### 4.2 ChatServer.java

同理，用 `promptManager.render()` 替代硬编码的 `systemPrompt` 字段和 `"车辆状态"` 字符串。同时修复第 74 行的重复"天气查询"行。

### 4.3 SceneServer.java

用 `promptManager.render()` 替代：
- `systemPrompt_` 字段 → `SYSTEM_ASSISTANT_SCENE`
- `userPrompt` 变量 → `USER_ACTIVE_CONTROL`
- `"场景描述"` + `"车辆状态"` → `USER_SCENE_DESCRIPTION` + `USER_VEHICLE_STATUS`
- `"整合下面两段话"` → `USER_SUMMARIZE`

### 4.4 SceneMatch.java

用 `promptManager.render()` 替代 `vl_scene_match()` 方法中的局部 `systemPrompt` 变量。`ResponseFormat` 中的 JSON Schema description 也考虑外部化。

### 4.5 VlManager.java

用 `promptManager.render()` 替代 `SYSTEM_MSG` 常量和响应警告后缀。

### 4.6 PromptManager 单例传递

PromptManager 需要在 `AIAgentService.onCreate()` 中初始化，并通过构造函数传递给所有需要它的类（MainAgentLoop、ChatServer、SceneServer、VlManager）。

---

## 5. 动态切换架构

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
│  ┌──────────────────┐  ┌──────────────────────┐     │
│  │DefaultSelector   │  │SceneSelector          │     │
│  │→ assistant_default│  │→ scene_type == "雨雪" │     │
│  └──────────────────┘  │  → assistant_scene     │     │
│                         │  → otherwise:          │     │
│                         │  → assistant_default   │     │
│                         └──────────────────────┘     │
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

调用方流程：
1. 收集当前上下文（场景类型、用户意图、车辆状态）
2. 调用 `PromptSelector.select(context)` 确定模板名称
3. 调用 `PromptManager.render(templateName, variables)` 渲染 Prompt
4. 将渲染结果作为 SystemMessage / UserMessage 注入 LLM

---

## 6. 实施步骤

### 步骤 1：创建目录和模板文件
- 创建 `app/src/main/assets/prompts/` 及所有子目录
- 创建 9 个 `.txt` 模板文件，内容从现有硬编码提取并规范化

### 步骤 2：新建 PromptManager / PromptSelector / PromptConstants
- 创建 `prompt/` package
- 实现 `PromptManager`：assets 加载 + PromptTemplate 缓存 + render
- 定义 `PromptSelector` 接口 + `DefaultPromptSelector` 实现
- 定义 `PromptConstants` 常量类

### 步骤 3：改造 MainAgentLoop
- 构造函数注入 PromptManager
- `buildSystemPrompt()` → `promptManager.render()`
- `"车辆状态"` 硬编码 → `USER_VEHICLE_STATUS` 模板
- 删除 `buildSystemPrompt()` 方法

### 步骤 4：改造 ChatServer
- 构造函数注入 PromptManager
- `systemPrompt` 字段 → `promptManager.render()`
- 修复第 74 行重复的"天气查询"
- `"车辆状态"` 硬编码 → `USER_VEHICLE_STATUS` 模板

### 步骤 5：改造 SceneServer
- 构造函数注入 PromptManager
- `systemPrompt_` → `SYSTEM_ASSISTANT_SCENE`
- `userPrompt` → `USER_ACTIVE_CONTROL`
- 其他硬编码指令 → 对应模板

### 步骤 6：改造 SceneMatch
- 注入 PromptManager
- `systemPrompt` 局部变量 → `TASK_SCENE_RECOGNITION`

### 步骤 7：改造 VlManager
- 注入 PromptManager
- `SYSTEM_MSG` → `TASK_FRONT_VIEW_QA`
- `warning` → `MSG_VL_WARNING`

### 步骤 8：AIAgentService 中初始化 PromptManager 并传递
- `onCreate()` 中 `new PromptManager(this)`
- 传递给 ChatServer、SceneServer、MainAgentLoop 等

---

## 7. 验证

- `gradlew assembleDebug` 编译通过（排除 AIDL 预存在错误）
- 模板文件全部通过 `render()` 静态验证：每个模板的变量列表与实际调用传入的变量 Map 一致
- 渲染结果与重构前的硬编码字符串逐内容对比，确保语义不变
- `MainAgentLoop` / `ChatServer` / `SceneServer` / `SceneMatch` / `VlManager` 五处的 Prompt 均来自模板文件，源码中不再有中文 Prompt 字符串
