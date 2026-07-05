# Tool 系统功能完成情况评估报告

评估日期：2026-07-03  
评估范围：`app/src/main/java/com/hirain/aiagent/ai/langchain4j/tool`、`core` Agent Loop、`tools/**`、`VirtualStateMachine`、`AIAgentService.kt`、`app/proguard-rules.pro`、`gradle/libs.versions.toml`  
参考文档：`docs/act_summary/tool-system-refactoring-summary.md`  
评估原则：以当前源码为准，参考文档仅作为线索，不直接采信结论。

---

## 1. 总体结论

当前 Tool 系统已经完成了从“各 Manager 手写 `hasTool()` / `handleToolRequest()` + 主循环硬编码分发”到“`@Tool` 声明 + `ToolRegistry` 注册 + `ToolDispatcher` 反射执行”的核心改造。这个方向总体合理，尤其适合当前项目的 Demo 阶段车控能力：工具数量有限、参数类型简单、执行目标主要是虚拟车辆状态机，集中式调度可以明显降低重复代码和字符串错配风险。

但系统还没有达到“生产级智能座舱 Agent Tool 层”的完成度。主要风险不是反射本身，而是工具定义、工具可见性、参数协议、场景工具白名单、安全策略、测试闭环之间还没有形成一个可验证的契约体系。当前最严重的问题是：`AgentConfigFactory` 中场景 persona 的 `SCENE_TOOL_MAP` 仍使用大量旧工具名，和当前 `@Tool(name=...)` 不匹配，导致场景服务实际暴露的工具子集会严重缩水，部分场景几乎拿不到预期工具。

综合评价：

| 维度 | 评价 |
| --- | --- |
| 技术路线 | 基本合理：LangChain4j Tool Calling + OpenAI 兼容接口 + 注册中心 + 反射执行，符合当前规模 |
| 架构解耦 | 明显改善：工具规格生成、工具路由、Agent Loop 执行职责已经分离 |
| 功能完成度 | 中等偏上：47 个 `@Tool` 已统一注册，但场景工具子集和测试验证存在缺口 |
| 安全完成度 | 初级：已有 `SafetyGuard` 插槽和车速锁门保护，但缺少按工具风险分级的系统性策略 |
| 可维护性 | 中等：比旧模式好很多，但工具名仍散落在场景白名单、测试样例、文档中，缺少一致性检查 |
| 可生产化程度 | 不足：参数协议、异常语义、并发状态、权限边界、工具调用审计仍需加强 |

---

## 2. 当前 Tool 系统结构梳理

### 2.1 工具定义层

当前工具主要位于：

- `tools/external/weather/WeatherUtils.java`
- `tools/vehicle/**/Vehicle*Manager.java`
- `tools/vision/vl/VlManager.java`

源码扫描显示当前共有 47 个 `@Tool` 方法：

- 天气：1 个，`getWeatherForecast`
- 空调：15 个，覆盖开关、温度、风量、循环、出风口、负离子、清洁模式等
- 底盘：1 个，`set_chassis_mode`
- DMS：3 个，疲劳、分心、情绪
- 车门：1 个，`set_door_lock`
- 香氛：2 个，类型、强度
- 座椅/方向盘：11 个，加热、通风、按摩、方向盘加热
- 车速：1 个，`set_vehicle_spd`
- 车窗/天窗/除霜/后视镜：11 个
- 前向视觉问答：1 个，`front_camera_interaction`

工具 Manager 的设计基本变成了“薄适配层”：`@Tool` 方法只做 LLM 可见能力声明，然后委托到 `VehicleStateMachine` 或外部服务。这个方向是合理的，因为业务约束不应该散落在工具调度层。

### 2.2 工具注册和路由层

`ToolRegistry` 负责：

- 为每个 Manager 创建 `ToolDispatcher`
- 使用 LangChain4j 的 `ToolSpecifications.toolSpecificationsFrom(mgr.getClass())` 生成工具规格
- 将 `ToolSpecification.name()` 映射到对应 `ToolDispatcher`
- 提供 `getToolSpecifications()` 给 `ChatRequest.Builder.toolSpecifications(...)`
- 提供 `dispatch(ToolExecutionRequest)` 给 Agent Loop 执行工具

这使工具规格和执行入口基本共享同一组 Manager 对象，是当前设计中最有价值的改进。

### 2.3 工具执行层

`ToolDispatcher` 构造时扫描目标对象的 `getDeclaredMethods()`，读取 `@Tool` 注解，建立 `toolName -> Method` 映射。执行时：

1. 根据 `ToolExecutionRequest.name()` 找到绑定方法
2. 将 `request.arguments()` 解析成 `JSONObject`
3. 按 `arg0`、`arg1` 顺序读取参数
4. 支持 `boolean`、`int`、其他类型按 `String` 处理
5. 反射调用目标方法
6. 返回 `result.toString()`，异常时返回 `"工具执行失败: ..."`

这个实现足够支撑当前工具参数形态，但它仍是一个较窄的参数协议适配器，不是通用 Tool Runtime。

### 2.4 Agent Loop 集成

新的主循环 `AgentLoopOrchestrator` 通过 `AgentConfig` 接收：

- `toolSpecifications`
- `ToolExecutor`
- `SafetyGuard`
- `PostProcessor`
- `LoopTerminator`
- `ResultCollector`

工具调用路径是：

```text
LLM 生成 ToolExecutionRequest
  -> AgentLoopOrchestrator
  -> SafetyGuard.evaluate(...)
  -> ToolExecutor.execute(...)
  -> ToolRegistry.dispatch(...)
  -> ToolDispatcher.dispatch(...)
  -> Vehicle*Manager / WeatherUtils / VlManager
  -> ToolExecutionResultMessage 回填 chatMemory
  -> 下一轮 LLM
```

这个流程符合标准 Tool Calling Loop：先让模型决定工具调用，再执行工具，再把工具结果作为 `ToolExecutionResultMessage` 回填，让模型基于结果生成最终回答。

---

## 3. 技术栈设置合理性评估

### 3.1 LangChain4j 作为 Tool Calling 抽象：合理

项目使用 LangChain4j 1.16.3，直接复用：

- `@Tool`
- `@P`
- `ToolSpecification`
- `ToolExecutionRequest`
- `ToolExecutionResultMessage`
- `ChatRequest.toolSpecifications(...)`

这比自定义 JSON function schema 更合适，因为项目已经使用 LangChain4j 的 `OpenAiChatModel`，不需要额外维护一套工具规格生成器。`ToolRegistry` 中继续调用 `ToolSpecifications.toolSpecificationsFrom(...)`，说明工具规格生成仍交给框架，而不是项目自造协议，这是正确选择。

需要注意的是，当前项目没有使用 LangChain4j 的高级 Agent / AiServices 托管整个循环，而是自己实现 `AgentLoopOrchestrator`。考虑到项目有车机 AIDL、记忆系统、场景服务、Trace、安全审查、前后台服务生命周期等强业务需求，自研主循环是合理的。Tool 系统只复用 LangChain4j 的工具规格和消息协议，边界清晰。

### 3.2 反射执行：当前阶段合理，生产阶段需补验证

反射执行的优点：

- 消除了旧版手写字符串分发
- 不要求 Manager 继承基类
- 新增工具时只需添加 `@Tool` 方法并注册 Manager
- 与 LangChain4j 自身读取 `@Tool` 的机制一致

反射执行的风险：

- 参数解析错误只能运行时发现
- 混淆规则必须正确，否则 release 构建可能丢注解或方法
- 重名工具会被后注册 Manager 覆盖，目前没有显式失败
- 反射调用绕过编译期方法签名约束

当前工具数量只有 47 个，参数类型也集中在 `boolean` / `int` / `String`，反射的性能和复杂度不是主要问题。因此“反射 + 注册中心”是合理的中间方案。若后续进入量产或工具数量快速增长，应增加启动期自检和单元测试，而不是马上引入 APT 或大型插件化框架。

### 3.3 OpenAI 兼容 DashScope + parallelToolCalls：需要谨慎

`AgentConfigFactory.buildModel()` 开启了 `.parallelToolCalls(true)`。这在纯查询类工具中问题不大，但当前大部分工具会修改同一个 `VehicleStateMachine`。如果模型一次返回多个工具调用，`AgentLoopOrchestrator` 会顺序执行它们，但语义上仍可能存在问题：

- 多个工具之间可能有顺序依赖，例如先开空调再调温、先解锁再开门
- 同一子系统多个工具调用可能互相覆盖
- 安全审查逐个执行，缺少对“一组工具调用”的整体审查
- 结果回填是逐条 `ToolExecutionResultMessage`，模型下一轮才会总结，用户可能感知不到中间失败

建议短期保留，因为这能支持“打开空调并调到 24 度”这类复合指令；但中期需要引入 batch-level safety 和冲突检测。

### 3.4 OkHttp 与外部工具调用：可用但阻塞模型循环

天气工具内部使用 OkHttp 异步请求 + `CountDownLatch.await(10s)` 转同步，运行在 Agent 工作线程内。这个实现简单可用，但它会阻塞当前 Agent Loop。当前服务层已有工作线程和请求超时，短期可以接受；后续应将外部工具调用的超时、错误码、重试、取消策略统一纳入 Tool Runtime，而不是每个工具自己处理。

### 3.5 ProGuard / R8 配置：方向正确，但规则不完整

`app/proguard-rules.pro` 已添加工具反射保留规则，这是必要的。不过当前规则仍有疑点：

- `-keepclassmembers class * { @dev.langchain4j.agent.tool.P <fields>; }` 看起来像保留字段注解，但 `@P` 用在方法参数上，不是字段；该规则很可能不能保护参数注解元数据。
- 文件中没有启用 `-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault,Signature` 一类注解属性保留规则。release 当前 `isMinifyEnabled = false`，所以风险暂时不触发；一旦开启混淆或压缩，`ToolSpecifications` 和 `ToolDispatcher` 依赖的注解读取可能失效。

因此，技术栈配置在 debug / Demo 环境下合理，但 release 反射稳定性尚未完成验证。

---

## 4. 架构合理性详细评估

### 4.1 优点一：`@Tool` 成为主要事实来源

旧系统的问题是同一个工具名至少写三次：`@Tool`、`hasTool()`、`handleToolRequest()`。当前将工具发现收敛到 `@Tool` 注解，方向正确。`ToolRegistry` 使用 LangChain4j 生成 `ToolSpecification`，`ToolDispatcher` 使用 `@Tool` 建执行映射，避免了旧版字符串错配。

但要注意：目前 `@Tool` 还不是“唯一事实来源”。因为 `AgentConfigFactory.SCENE_TOOL_MAP` 仍手写工具名，测试和文档中也有旧工具名。换句话说，核心调度链已经收敛，但工具可见性策略尚未收敛。

### 4.2 优点二：Agent Loop 与工具实现解耦

`AgentLoopOrchestrator` 不再关心 `VehicleDoorManager`、`WeatherUtils` 等具体类型，只依赖 `ToolExecutor` 函数接口。这个抽象很合适：

- 便于替换 Tool 执行器，例如测试 stub、远程工具网关、权限代理
- 便于不同 persona 使用不同工具集合
- 便于在执行前后插入 trace、安全审查、审计

这个解耦方式比把所有 Manager 直接塞进主循环更健康。

### 4.3 优点三：业务校验下沉到 `VehicleStateMachine`

当前车控工具的范围、枚举、状态收敛基本在 `VehicleStateMachine` 内处理，例如温度范围、车速范围、模式枚举、车门未关闭不能闭锁等。这比把校验写在 Manager 或 Dispatcher 里更合理：

- Manager 保持 LLM 适配层角色
- 状态机成为车控 Demo 的唯一状态入口
- 后续接入真实 SOA 时，可以把状态机替换或扩展为硬件适配层

这是当前 Tool 系统最接近“业务架构正确性”的部分。

### 4.4 优点四：Persona 级工具子集设计是正确方向

`AgentConfig.toolSubset` 允许不同 persona 暴露不同工具：

- chat persona：全部工具
- scene persona：场景白名单工具
- vision_qa persona：空工具列表

从 Agent 设计角度，这是必要能力。不同任务应该有不同的 action space，不能让场景服务或视觉问答随意调用全部车控工具。

但是当前实现只完成了机制，策略数据本身存在明显错误，见后文 P0 问题。

### 4.5 不足一：工具注册缺少启动期契约校验

`ToolRegistry.registerAll()` 当前没有做以下校验：

- 重复工具名检测
- `ToolSpecification` 名称与 `ToolDispatcher` binding 名称一致性检测
- 工具方法返回类型检测
- 参数类型白名单检测
- 参数数量与模型协议兼容性检测
- 每个场景白名单工具名是否真实存在

这导致很多错误只能在模型真正调用工具时暴露。对车控类 Agent 来说，这个反馈太晚。

### 4.6 不足二：参数协议过窄，且注释与实现不一致

`ToolDispatcher` 类注释写着“优先解析 JSON 中的命名键，回退到 `arg0` / `arg1`”，但实际 `resolveParameters()` 只读取 `arg0`、`arg1`。这会产生两个问题：

- 维护者会误以为支持命名参数，例如 `{"address":"北京","day":1}`
- 不同模型或同一模型的不同兼容模式若输出真实参数名，工具会全部失败

考虑到 LangChain4j 的 `ToolSpecification` 通常会包含参数名和参数描述，Tool Runtime 只支持 `argN` 是一个脆弱假设。即使 qwen 当前输出 `arg0`，也应该把这作为兼容路径，而不是唯一协议。

### 4.7 不足三：工具结果没有标准结构

当前工具返回值有三类：

- 成功自然语言：`"空调开启成功"`
- 失败自然语言：`"无效的循环模式：..."`
- JSON 字符串：天气、车辆状态查询

`ToolDispatcher` 对异常返回 `"工具执行失败: ..."`，但业务失败通常仍是普通字符串。Agent Loop 无法机器判断：

- 工具是否真正成功
- 是否为参数错误
- 是否需要用户确认
- 是否是外部服务超时
- 是否触发了安全拒绝

这对最终回答质量和 trace 分析都会有影响。当前 Demo 可用，但生产化应统一返回结构，例如：

```json
{
  "success": true,
  "code": "OK",
  "message": "空调开启成功",
  "state_delta": {"ac_status": true}
}
```

### 4.8 不足四：安全策略仍是插槽级，不是工具治理级

当前 `SafetyGuard` 已经接入主循环，并有 `SpeedBasedDoorLockGuard`。这是好的开始。但车控工具需要更完整的风险分层：

- 查询类：天气、视觉问答、状态读取
- 舒适类：空调、座椅、香氛
- 视野类：车窗、除霜、后视镜
- 行驶相关：车速、底盘模式
- 安全相关：门锁、DMS

不同风险等级应有不同策略，例如行驶中禁止某些动作、需要二次确认、只允许系统场景触发、只允许驾驶员明确指令触发。当前只有车门闭锁保护，覆盖面不够。

### 4.9 不足五：工具和外部硬件边界还不清晰

项目概述中提到 SOA 总线、Camera、VR/TTS，但当前大部分车控工具只操作虚拟状态机。作为 Demo 是合理的，但 Tool 系统缺少一个明确的“执行模式”抽象：

- Demo / virtual mode
- Real vehicle / SOA mode
- Shadow mode，只记录不执行
- Dry-run mode，只返回计划

如果未来直接从 `VehicleStateMachine` 替换为真实 SOA 调用，工具层会同时面对权限、安全、超时、回滚、硬件失败等问题。建议提前在报告和后续设计中明确 Tool 执行边界。

---

## 5. 当前存在的关键问题

### P0：场景工具白名单大量使用旧工具名，scene persona 实际工具子集错误

证据：

- 当前真实工具名包括 `set_ac_drive_temp`、`set_ac_fan_intensity`、`set_ac_cyc_mode`、`set_chassis_mode`、`set_steering_heat`、`set_frag_type`、`set_frag_intensity`、`set_window_r_heat`。
- `AgentConfigFactory.SCENE_TOOL_MAP` 却仍使用 `set_ac_temperature`、`set_ac_air_volume`、`set_ac_mode`、`set_ac_circulation`、`set_ac_ac`、`set_ac_auto`、`set_drive_mode`、`set_seat_pos`、`set_seat_heat`、`set_steer_heat`、`set_frag_switch`、`set_frag_level`、`set_window_r_defrosting` 等旧名。
- `filterSceneTools()` 通过 `allowed.contains(spec.name())` 过滤，因此旧名不会匹配任何当前工具。

影响：

- 场景服务本来应触发多个舒适/安全工具，但实际只能匹配少量仍同名的工具，例如 `set_window_f_defrosting`。
- 多数场景 action space 为空或严重不完整，模型无法调用预期车控工具。
- 这是功能性缺陷，不是文档问题。

建议：

1. 将场景白名单改为引用统一工具名常量或从工具元数据标签生成。
2. 增加单元测试：每个 `SCENE_TOOL_MAP` 中的工具名必须存在于 `ToolRegistry.getToolSpecifications()`。
3. 增加场景级测试：每个场景过滤后工具数必须大于预期阈值，并包含关键动作。

### P1：`ToolDispatcher` 注释与实现不一致，且只支持 `argN` 参数

证据：

- 类注释声称优先解析命名键，再回退到 `arg0`。
- `resolveParameters()` 实际只构造 `String key = "arg" + i` 并强制 `args.has(key)`。

影响：

- 如果模型输出 `{"address":"北京","day":1}`，天气工具会失败，提示缺少 `arg0`。
- 工具 schema 里 `@P` 提供的是参数描述，但执行器没有利用真实参数名。
- 后续模型切换或 LangChain4j 升级可能暴露兼容问题。

建议：

1. 短期修正文档或实现，保证注释和行为一致。
2. 中期支持命名参数、`argN` 双协议。
3. 为 `ToolDispatcher` 建立参数解析单元测试，覆盖 boolean、int、String、缺参、类型错误、命名参数、`argN` 参数。

### P1：工具注册缺少重复名称和空工具检查

当前 `dispatchers.put(spec.name(), dispatcher)` 会静默覆盖同名工具。若两个 Manager 使用同一 `@Tool(name=...)`，后注册者会覆盖前者，`allSpecs` 仍可能包含重复规格，LLM 侧和执行侧行为不一致。

建议：

- 注册时发现重复工具名直接抛出异常。
- 注册 Manager 后如果 `ToolDispatcher` binding 数和 `ToolSpecifications` 数不一致，应启动失败。
- 对没有任何 `@Tool` 的 Manager 给出明确日志或错误，避免误注册。

### P1：Tool 系统缺少直接测试

当前 `app/src/test/java` 下没有 `ToolRegistry` / `ToolDispatcher` 专项测试。搜索到的工具名测试主要是 trace formatter 示例，并且仍使用旧名 `set_ac_temperature`。这意味着当前最核心的工具调度机制缺少自动化保护。

建议补充：

- `ToolDispatcherTest`
  - 能执行 boolean/int/String 参数工具
  - 缺参返回可预期错误
  - 类型错误返回可预期错误
  - 支持命名参数和 `argN`
- `ToolRegistryTest`
  - 注册后工具数等于预期
  - 重复工具名失败
  - 未知工具返回明确错误
  - `ToolSpecification` 名称与 Dispatcher binding 一致
- `SceneToolSubsetTest`
  - 每个场景白名单工具都真实存在
  - 每个场景过滤结果不为空
  - 关键场景包含关键工具

### P1：Release 反射保留规则未完成验证

当前 release `isMinifyEnabled = false`，所以混淆风险被暂时绕过。但既然已经写入 ProGuard 规则，说明设计上预期支持反射保留。现有规则疑点较大，尤其是 `@P <fields>` 与参数注解使用位置不匹配。

建议：

- 补充 `-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault,Signature`。
- 开启一次 minify 的验证构建，确认 `ToolSpecifications.toolSpecificationsFrom()` 能读取全部 47 个工具。
- 加一个 release-like 单元或 instrumentation 检查，至少验证工具数量、工具名和参数描述存在。

### P2：工具返回协议不统一

目前业务失败和成功都以自然语言字符串返回，Agent Loop 难以做结构化判断。对于 Demo 可接受，但会影响：

- Trace 统计
- 用户确认流程
- 失败重试
- 多工具调用冲突处理
- 安全审计

建议后续定义统一 `ToolResult`：

- `success`
- `code`
- `message`
- `data`
- `requires_confirmation`
- `risk_level`

然后在回填给模型时再序列化为 JSON 字符串。

### P2：工具安全治理覆盖不足

当前安全链路只看到 `SpeedBasedDoorLockGuard` 这类局部保护。车机 Agent 的工具系统应至少有：

- 工具风险等级
- 工具权限来源：用户主动请求、场景自动触发、系统内部调用
- 行驶状态约束
- 二次确认策略
- 危险动作拒绝策略
- 工具调用审计日志

建议先从元数据开始，不必一步到位实现全部策略。可以为工具建立静态清单或注解扩展，标注 `riskLevel`、`domain`、`allowedPersona`、`requiresConfirmation`。

### P2：场景工具子集仍靠手写字符串维护

即使修复当前旧名，未来仍可能再次漂移。场景工具选择不应长期靠 `Set<String>` 手写。

建议：

- 短期：把工具名抽成 `ToolNames` 常量，白名单引用常量。
- 中期：引入工具标签，例如 `domain=AC`、`capability=DEFROST`、`persona=SCENE`。
- 长期：由工具元数据生成 persona 可见工具集合，白名单只描述业务意图。

### P2：视觉工具边界混合了内部方法和 LLM 工具

`VlManager` 中只有 `front_camera_interaction` 是 `@Tool`，`frontCameraSave()` 和 `frontCameraInteractionPositive()` 是服务内部方法。这个划分本身合理，但同一个类同时承担：

- LLM 工具
- 摄像头缓存
- VLM 直接调用
- 默认图片 fallback
- 文件写入辅助

后续容易让工具调用和服务内部调用耦合。建议未来拆分：

- `FrontCameraTool`：LLM 可见工具
- `VisionQaService`：VLM 调用
- `FrontImageStore`：最近帧缓存
- `ImageAssetLoader`：默认图片加载

当前阶段不必立即拆，但需要记录设计债务。

### P3：命名规范仍有细节不一致

多数车控工具采用 snake_case，但天气工具 `getWeatherForecast` 仍是 camelCase，DMS 工具 `set_dms_drive_distractionlevel` 少了下划线。它们不一定导致功能错误，但会影响模型理解、一致性和后续白名单维护。

建议统一命名：

- LLM 可见工具名统一 snake_case
- Java 方法名统一 camelCase
- 工具名常量集中维护
- 对外兼容旧名时使用 alias 机制，而不是在主工具名中保留不一致形式

### P3：过期文档仍干扰判断

当前源码中 `engines/chat/ChatServer.java` 和 `engines/sceneserver/SceneServer.java` 已不存在，参考总结中“这两个文件无法编译”的说法不再符合当前源码事实。多个文档仍包含旧工具名和旧调度链示例，例如 `docs/others`、`docs/reference`、部分 trace 示例。

建议：

- 将历史文档移入 `docs/others/archive` 或标注“历史参考，不代表当前实现”。
- 当前架构文档使用真实工具名和 `AgentLoopOrchestrator` 链路。
- 测试样例避免继续使用旧工具名，除非明确测试历史兼容。

---

## 6. 缺少或遗漏的设计

### 6.1 Tool 元数据层

当前 `@Tool` 只能表达名称和自然语言描述，无法表达车机工具所需治理信息。建议补充一个项目内元数据层，哪怕先用静态表实现：

| 元数据 | 用途 |
| --- | --- |
| domain | AC / DOOR / WINDOW / SEAT / WEATHER / VISION |
| capability | temperature / lock / defrost / massage 等 |
| riskLevel | LOW / MEDIUM / HIGH / CRITICAL |
| allowedPersonas | chat / scene / vision_qa |
| requiresConfirmation | 是否需要二次确认 |
| drivingConstraint | 行驶中是否允许 |
| timeoutMs | 工具级超时 |
| idempotent | 是否幂等 |

### 6.2 Tool Contract 测试

应建立一组不依赖真实 LLM 的契约测试，保证：

- 工具注册数量稳定
- 所有工具名唯一
- 所有场景白名单都能匹配真实工具
- 参数 schema 和执行器支持的参数协议一致
- 所有工具都能用最小合法参数执行一次
- 所有非法参数都返回可解释失败

这类测试比端到端 LLM 测试更稳定，也更适合保护工具系统。

### 6.3 Batch Tool 调用治理

既然开启了 parallel tool calls，就需要治理“一次模型响应中的多个工具调用”：

- 同一 domain 多次调用是否合并
- 冲突动作如何处理，例如同时开窗和禁开窗
- 是否先执行低风险，再执行高风险
- 是否某个工具失败后停止后续工具
- 是否需要把一组工具作为事务记录到 trace

当前逐条执行虽然简单，但没有业务级批处理语义。

### 6.4 Tool 权限与来源区分

同一个工具由不同来源触发时风险不同：

- 用户明确说“打开空调”
- 场景服务自动判断“舱外浓烟”
- 视觉问答识别到前方情况
- Launcher 直接通过 AIDL 发 CONTROL

建议 `AgentLoopContext` 中明确记录 trigger source，并让 `SafetyGuard` 使用它。场景自动触发的工具应比用户主动请求更保守。

### 6.5 执行模式切换

当前状态机是 Demo 执行中心。后续接 SOA 时应避免直接替换造成大改。建议设计：

```text
VehicleToolManager
  -> VehicleCommandService interface
      -> VirtualVehicleCommandService
      -> SoaVehicleCommandService
      -> DryRunVehicleCommandService
```

这样 Tool 系统保持稳定，真实硬件接入只替换执行后端。

### 6.6 工具调用观测与审计

Trace 已经有 `tool.execute` span，但工具系统还缺少审计视角：

- 谁触发了工具
- 输入参数是否被脱敏
- 安全审查结果
- 工具执行结果 code
- 状态变化前后快照
- 是否写入长期记忆或用户可见响应

车控工具尤其需要可回放、可追责的调用记录。

---

## 7. 优化路线建议

### 7.1 立即处理

1. 修复 `SCENE_TOOL_MAP` 旧工具名，确保场景 persona 能拿到真实工具。
2. 为 `SCENE_TOOL_MAP` 增加存在性测试，防止再次漂移。
3. 修正 `ToolDispatcher` 注释或实现，明确当前是否支持命名参数。
4. 增加 `ToolRegistry` 重名检测，避免静默覆盖。

### 7.2 短期完善

1. 给 `ToolDispatcher` / `ToolRegistry` 增加 JVM 单元测试。
2. 建立“所有工具最小合法参数执行测试”，使用 `VehicleStateMachine` 不依赖真实硬件。
3. 统一工具命名，至少处理 `getWeatherForecast` 和 `set_dms_drive_distractionlevel`。
4. 调整 ProGuard/R8 注解保留规则，并做一次 release-like 验证。
5. 清理或标注旧工具链文档，避免后续评估被过期资料误导。

### 7.3 中期演进

1. 引入 `ToolResult` 结构化返回协议。
2. 引入工具元数据和风险等级。
3. 按 persona / scene / risk 生成工具子集，减少手写字符串白名单。
4. 设计 batch tool call 冲突检测与整体安全审查。
5. 将视觉工具拆出缓存、VLM 调用和 LLM 工具入口。

### 7.4 长期生产化

1. 抽象真实车辆命令执行接口，支持 virtual / SOA / dry-run / shadow。
2. 建立工具调用审计日志和回放能力。
3. 对高风险工具增加用户确认和策略引擎。
4. 将工具契约纳入 CI，任何工具名、参数、白名单变更都必须通过契约测试。

---

## 8. 最终评价

当前 Tool 系统的核心重构方向是正确的：以 LangChain4j `@Tool` 为声明入口，用 `ToolRegistry` 统一工具规格和路由，用 `ToolDispatcher` 消除重复分发代码，再由 `AgentLoopOrchestrator` 完成安全审查、工具执行、结果回填。这套架构比旧系统更清晰，也更符合 Agent Tool Calling 的基本范式。

但它目前仍处在“可运行的 Demo Tool 系统”阶段，而不是“可靠的车机 Agent Tool 平台”。最大问题是工具契约没有被测试和强校验保护，尤其是场景白名单仍然持有大量旧工具名，已经影响实际功能。其次是参数协议、工具结果、安全治理、release 反射保留、批量工具调用语义都还比较初级。

如果只面向初级车机 AI Agent 中枢演示，当前方案可以继续使用；如果要作为后续真实车控能力的基础，需要优先补齐工具契约测试、工具元数据、安全策略和执行后端抽象。建议下一轮工作不要先重写架构，而是先修复白名单漂移和测试缺口，让当前架构变成可验证、可演进的基础。
