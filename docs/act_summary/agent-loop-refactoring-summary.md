# Agent Loop 重构总结

## 概述

对 AIAgent 项目中所有 Agent 执行入口进行了一次系统性重构，将三套互不兼容的 Agent Loop 实现统一为单一的可插拔管道引擎，并引入安全审查、结构化结果、人格配置等企业级特性。

---

## 一、重构前的项目情况

### 1.1 三套 Agent Loop 并存

重构前项目中存在 **4 个与 Agent 执行相关的文件**，其中 3 个包含独立的 Agent Loop 逻辑：

| 文件 | 行数 | 状态 | 功能 |
|------|------|------|------|
| `core/MainAgentLoop.java` | ~228 | **死代码** | 最规范的实现（ToolRegistry + for-loop），但从未被实例化 |
| `engines/chat/ChatServer.java` | ~220 | **活跃** | 对话引擎，递归 Agent Loop |
| `engines/sceneserver/SceneServer.java` | ~215 | **活跃** | 场景服务引擎，单轮工具调用 + 汇总合并 |
| `engines/scenematch/SceneMatch.java` | ~105 | **活跃** | 纯 VL 分类调用，非 Agent Loop |

### 1.2 各自为政的实现

每个 Loop 在模型选型、工具调度方式、迭代控制策略、记忆策略上各不相同：

| 对比维度 | MainAgentLoop（死代码） | ChatServer | SceneServer |
|----------|------------------------|------------|-------------|
| **模型** | qwen-turbo | qwen-turbo | qwen-flash |
| **迭代控制** | for 循环 + 10 次上限 | 递归（无上限） | 1 轮工具 + 1 轮汇总 |
| **工具调度** | ToolRegistry（集中式 Map 查找） | 手动 if-else hasTool/handleToolRequest | 手动 if-else + 场景工具白名单 |
| **记忆策略** | SQLite 持久化（MainAgentMemory） | SQLite 持久化（ChatMemory） | 单轮清除（SceneMemory） |
| **系统提示词** | assistant_default | assistant_default | assistant_scene |
| **并发模型** | 无 | 无 | 无 |
| **错误处理** | catch-and-return-string | catch-and-return-string | catch-and-return-string |
| **安全审查** | 无 | 无 | 无 |
| **结构结果** | 无（返回 String） | 无（返回 String） | 无（返回 String） |

### 1.3 工具调度系统的割裂

虽然工具系统重构已经完成了 `ToolRegistry` + `ToolDispatcher` 的统一，但 ChatServer 和 SceneServer 仍然使用手动的 `hasTool()`/`handleToolRequest()` if-else 链。这两个方法已经在工具系统重构中被删除——意味着这两个引擎**无法通过编译**。

### 1.4 MainAgentLoop 的尴尬处境

MainAgentLoop 是三个实现中唯一使用 ToolRegistry 的、唯一使用 for 循环的、唯一有迭代上限的，但它是一个**无人实例化的死代码**。AIAgentService.kt 创建的是 ChatServer 和 SceneServer。

---

## 二、总体做了什么

1. **设计了 7 个可插拔组件接口**——PreProcessor、ModelCaller、ToolExecutor、SafetyGuard、PostProcessor、LoopTerminator、ResultCollector，将 Agent Loop 的每个环节拆解为单一职责的接口
2. **构建了 AgentLoopOrchestrator**——唯一的 Agent 执行引擎，通过 for 循环串联 7 个组件，替代三套独立 Loop
3. **设计了 AgentConfig + AgentConfigFactory**——通过配置定义"人格"（chat / scene / vision_qa），无需写新类
4. **引入了 SafetyGuard 安全审查机制**——工具执行前可注入安全规则（如车速 > 5 km/h 时禁止解锁车门）
5. **引入 AgentResult 结构化结果**——替代 catch-and-return-string 模式，携带错误类型枚举
6. **引入 AgentLoopState 线程安全状态跟踪**——供 Service 层观察 Agent 繁忙状态
7. **重构了 AIAgentService.kt**——从创建 ChatServer/SceneServer 改为创建 ToolRegistry + AgentLoopOrchestrator
8. **新建 ~33 个文件**（7 接口 + 18 实现 + 6 核心类型 + 2 工厂/引擎）

---

## 三、发现的问题与解决过程

### 问题 1：三个 Agent Loop 实现各自为政，没有统一入口

**发现：**

项目中存在三个独立的 Agent Loop：MainAgentLoop（死代码）、ChatServer（递归）、SceneServer（单轮）。它们的共同职责是一样的——组装消息 → 调用 LLM → 执行工具 → 迭代/返回——但实现方式完全不同。

这意味着：
- 修改整个项目的行为（如增加安全审查）需要改 3 个文件
- 新人格（如"导航助手"）需要再写一个 ChatServer 的副本
- LLM 调用参数、超时设置、记忆策略等零散分布在 3 处

**思考：**

三个 Loop 的核心流程本质上是一样的。差异点在于"人格"层面的配置：用的什么模型、装什么前置处理器、执行什么安全检查、如何收集结果。用**策略模式**将差异点参数化，把共性收敛到单一引擎中。

核心思路是：Agent Loop = 不可变配置（选型） + 可插拔组件管道（执行流程）。不同"人格"通过不同的配置对象定义，不需要不同的 Java 类。

**解决：**

设计 `AgentConfig`（不可变配置） + `AgentLoopOrchestrator`（通用引擎）两层：

```java
// 一个"人格"就是一个配置对象，不需要单独的类
AgentConfig chatConfig = AgentConfig.builder("chat")
    .systemPromptTemplateName(SYSTEM_ASSISTANT_DEFAULT)
    .modelCaller(new Lc4jModelCaller(buildQwenTurbo()))
    .preProcessors(List.of(new VehicleStatusPreProcessor(...), new TimeContextPreProcessor()))
    .terminator(new CompositeTerminator(new NoToolCallTerminator(), new SafetyVetoTerminator()))
    .resultCollector(new DirectTextCollector())
    .build();
```

AgentLoopOrchestrator 不可知"人格"——它只知道执行组件链：

```
execute(userInput, extraContext)
  → 注入系统提示词 → 写入用户消息
  → for i in 0..maxIterations:
     ① PreProcessor 链     → 注入瞬时上下文（车辆状态、场景描述等）
     ② ModelCaller          → LLM 调用
     ③ LLM 有 ToolCall → SafetyGuard → ToolExecutor → 回填记忆 → continue
     ④ LLM 输出文本 → PostProcessor → LoopTerminator → ResultCollector → return
  → max iterations → AgentResult.error(MAX_ITERATIONS)
```

---

### 问题 2：ChatServer/SceneServer 工具调度已损坏

**发现：**

ChatServer 和 SceneServer 使用手动 if-else 链调用 `hasTool()`/`handleToolRequest()`：

```java
// ChatServer.java
private String handleTools(ToolExecutionRequest request) {
    if (doorManager.hasTool(request.name())) return doorManager.handleToolRequest(request);
    else if (windowManager.hasTool(request.name())) return windowManager.handleToolRequest(request);
    // ...
}
```

这些方法在工具系统重构时已从所有 Manager 中删除。Manager 现在只包含 `@Tool` 注解方法，工具分发由 `ToolRegistry` 集中管理。所以 ChatServer 和 SceneServer **实际上无法通过编译且无法运行**。

**思考：**

这个问题有两个层面的解决方案：
1. **短期**：把 ChatServer/SceneServer 的 `hasTool`/`handleToolRequest` 调用改为 `ToolRegistry.dispatch()`
2. **长期**：不再使用 ChatServer/SceneServer 这些独立引擎，统一走 AgentLoopOrchestrator

选择长期方案。ChatServer 和 SceneServer 保留文件但不再被 Service 实例化，它们的职责由 AgentConfigFactory 中的 Preset 替代。

**解决：**

AIAgentService.kt 中的初始化从：

```kotlin
// 重构前：三个独立引擎
vl = VlManager(this, promptManager)
scene_matcher = SceneMatch(promptManager)
scene_server = SceneServer(this, promptManager)
chat = ChatServer(this, vl, promptManager)
```

改为：

```kotlin
// 重构后：共享基础设施 + 按需人格
toolRegistry = ToolRegistry().apply { registerAll(10 managers) }
chatOrchestrator = AgentLoopOrchestrator(
    AgentConfigFactory.createChatPersona(this, promptManager, toolRegistry, ...), ...)

// 场景触发：在 ProcessCaptureGot 中动态创建
val sceneConfig = AgentConfigFactory.createScenePersona(this, promptManager, toolRegistry, ..., scene)
val sceneOrchestrator = AgentLoopOrchestrator(sceneConfig, ...)
val result = sceneOrchestrator.execute("", mapOf("scene" to scene))
```

---

### 问题 3：无安全审查机制

**发现：**

在车载场景中，LLM 控制车辆硬件的安全性至关重要。但当前所有工具调用都是无条件执行的——LLM 说解锁车门就解锁，不管车速是多少。

这是一个潜在的安全隐患：如果 LLM 幻觉导致在高速行驶时解锁车门、调节方向盘、关闭 ESP 等，可能造成严重事故。

**思考：**

安全审查应该在工具执行前插入，作为一个同步检查点。设计一个 `SafetyGuard` 接口，可以链接多个审查规则（车速检查、挡位检查、安全带状态检查等）。执行流程为：

```
ToolExecutionRequest → SafetyGuard.evaluate() → ALLOW → ToolExecutor.execute()
                                                → VETO("原因") → 返回否决消息给 LLM
```

否决后不抛异常，而是返回描述性文本给 LLM，让 LLM 决定下一步（如"好的，已停车再解锁"）。

**解决：**

定义 `SafetyVerdict`（允许/否决）和 `SafetyGuard` 接口：

```java
@FunctionalInterface
public interface SafetyGuard {
    SafetyVerdict evaluate(ToolExecutionRequest request, AgentLoopContext ctx);
}
```

实现 `SpeedBasedDoorLockGuard`——当车速 > 5 km/h 时阻止车门解锁：

```java
if ("set_door_lock".equals(request.name())) {
    boolean isLock = args.getBoolean("arg0");
    if (!isLock && speed > 5) {
        return SafetyVerdict.veto("车速 %d km/h 时不允许解锁车门", speed);
    }
}
```

`CompositeSafetyGuard` 支持链式组合多个审查规则。`AllowAllSafetyGuard` 用于不需要审查的人格（如 vision_qa）。

在 AgentLoopOrchestrator 中，安全审查发生在工具执行之前：

```java
for (SafetyGuard guard : config.safetyGuards()) {
    verdict = guard.evaluate(toolReq, ctx);
    if (verdict.isVetoed()) break;
}
if (verdict.isVetoed()) {
    result = "[SAFETY VETO] " + verdict.reason();
} else {
    result = config.toolExecutor().execute(toolReq);
}
```

---

### 问题 4：递归 Agent Loop 无上限

**发现：**

ChatServer 的 `processAiResponse()` 方法使用递归模式处理工具调用：

```java
private String processAiResponse(ChatResponse aiResponse) {
    // ...
    if (aiMessage.hasToolExecutionRequests()) {
        // 执行工具 ...
        ChatResponse nextResponse = model.chat(request_with_tool);
        return processAiResponse(nextResponse);  // 递归调用
    } else {
        return aiResponse.aiMessage().text();
    }
}
```

递归在 Java 中没有循环主体，每次递归都增加调用栈深度。如果 LLM 连续请求工具调用 100 次（极端情况），会导致 StackOverflowError。同时没有超时机制——一次对话可能持续数分钟。

**思考：**

用 for 循环代替递归，设置明确的迭代上限（10 次）和超时时间（30 秒）。LLM 看到工具结果后可以选择继续调用工具（下一轮迭代）或输出最终文本（终止循环）。

**解决：**

AgentLoopOrchestrator 使用 for 循环 + 超时检查：

```java
for (int i = 0; i < config.maxIterations(); i++) {
    if (timeout exceeded) return AgentResult.error(TIMEOUT);

    // LLM 调用
    ChatResponse response = modelCaller.call(request);

    if (response has tool calls) {
        for (tool : tool calls) {
            toolResult = execute tool;
            memory.add(toolResult);
        }
        continue;  // 下一轮
    } else {
        output = postProcess(response);
        if (terminator.shouldStop()) {
            return collector.collect(output);
        }
    }
}
return AgentResult.error(MAX_ITERATIONS);
```

三个 Persona 的迭代配置：
- `chat`：maxIterations=10, timeout=30s（多轮对话场景）
- `scene`：maxIterations=2, timeout=20s（单轮工具 + 汇总）
- `vision_qa`：maxIterations=1, timeout=20s（无工具，直接回答）

---

### 问题 5：catch-and-return-string 错误模式

**发现：**

所有 Agent Loop 在出错时返回的是中文文本字符串：

```java
return "系统: 请求失败 - " + e.getMessage();
```

调用方无法区分"模型调用失败"和"最大迭代次数达到"——两者都是 String。日志中丢失了错误类型信息，无法做监控和告警。

**思考：**

引入结构化结果类型 `AgentResult`，包含 success/false 标志、枚举错误类型、错误详情文本、迭代次数、耗时和工具执行历史。调用方可以精确判断错误类型并采取不同行为。

**解决：**

```java
public final class AgentResult {
    public enum ErrorType {
        NONE, MODEL_CALL_FAILED, TOOL_EXECUTION_FAILED,
        MAX_ITERATIONS_REACHED, SAFETY_VETO, TIMEOUT, INVALID_CONFIG, INTERRUPTED
    }

    private final boolean success;
    private final String output;
    private final ErrorType errorType;
    private final String errorDetail;
    private final int iterationsUsed;
    private final long durationMs;
    private final List<ToolExecutionRecord> toolHistory;
}
```

使用方式：

```java
AgentResult result = orchestrator.execute("打开车窗", mapOf());
if (result.isSuccess()) {
    playTTS(result.output());
    logMetrics(result.iterationsUsed(), result.durationMs());
} else if (result.errorType() == SAFETY_VETO) {
    playTTS("安全原因已阻止操作");
} else {
    playTTS("请求失败，请稍后重试");
}
```

---

### 问题 6：并发控制依赖 4 个 AtomicBoolean 且无法感知 Agent 状态

**发现：**

AIAgentService.kt 使用 4 个并发标志位控制不同流的互斥：

```kotlin
private var mChating = false
private var mPositiveReqExecuting = AtomicBoolean(false)
private var mNagativeReqExecuting = AtomicBoolean(false)
private var mNagativeTTSplaying = false
```

但它们无法回答一个简单问题：**Agent 现在忙吗？** Service 层无法判断 Agent 是否在执行中。

**思考：**

每个 AgentLoopOrchestrator 内部携带一个 `AgentLoopState`（使用 AtomicInteger 实现的状态机），Service 层可以通过 `orchestrator.getState().isBusy()` 查询。状态转换清晰：IDLE → RUNNING → COMPLETED/ERROR/TIMEOUT。

**解决：**

```java
public class AgentLoopState {
    public enum State { IDLE, RUNNING, COMPLETED, ERROR, TIMEOUT }
    private final AtomicInteger state = new AtomicInteger(State.IDLE.ordinal());

    public boolean tryStart()  // IDLE → RUNNING（CAS）
    public void markCompleted()
    public void markError()
    public void markTimeout()
    public boolean isBusy()
}
```

Orchestrator 在 `execute()` 开始时调用 `state.tryStart()`——如果失败（已有任务在执行），直接返回 `AgentResult.error(INVALID_CONFIG, "Agent is busy")`。Service 层可以通过 `getState()` 暴露状态给外部监听器。

---

## 四、改进后的项目架构

### 4.1 新增文件结构

```
app/src/main/java/com/hirain/aiagent/core/
├── AgentLoopContext.java       # 执行上下文（迭代状态 + 工具历史 + 上下文数据）
├── AgentLoopOrchestrator.java  # 主循环引擎（~150 行）
├── AgentLoopState.java         # 线程安全状态跟踪器
├── AgentConfig.java            # 不可变人格配置 + Builder
├── AgentResult.java            # 结构化返回结果
├── SafetyVerdict.java          # 安全审查结果
├── ToolExecutionRecord.java    # 工具执行快照
│
├── component/                  # 7 个可插拔接口
│   ├── PreProcessor.java
│   ├── ModelCaller.java
│   ├── ToolExecutor.java
│   ├── SafetyGuard.java
│   ├── PostProcessor.java
│   ├── LoopTerminator.java
│   └── ResultCollector.java
│
├── preprocessor/
│   ├── VehicleStatusPreProcessor.java   # 注入车辆状态
│   ├── TimeContextPreProcessor.java     # 注入当前时间
│   ├── SceneContextPreProcessor.java    # 注入场景描述
│   ├── ActiveControlPreProcessor.java   # 注入主动控车指令
│   └── VlWarningPreProcessor.java       # 注入 VL 警告
│
├── model/
│   └── Lc4jModelCaller.java             # LangChain4j ChatModel 封装
│
├── safety/
│   ├── AllowAllSafetyGuard.java         # 始终放行
│   ├── SpeedBasedDoorLockGuard.java     # 车速门锁审查
│   └── CompositeSafetyGuard.java        # 链式组合
│
├── postprocessor/
│   ├── NoOpPostProcessor.java           # 直通
│   ├── SceneActionMergePostProcessor.java  # 硬编码场景动作
│   └── VlWarningPostProcessor.java      # VL 警告后缀
│
├── terminator/
│   ├── NoToolCallTerminator.java        # 无工具调用时终止
│   ├── MaxIterationTerminator.java      # 达迭代上限时终止
│   ├── SafetyVetoTerminator.java        # 安全否决时终止
│   └── CompositeTerminator.java         # 组合模式（OR/AND）
│
├── collector/
│   ├── DirectTextCollector.java         # 直接返回文本
│   └── SummarizeMergeCollector.java     # 场景动作汇总（80 字）
│
└── factory/
    └── AgentConfigFactory.java          # 3 个人格预设（chat/scene/vision_qa）
```

### 4.2 最终架构图

```
                       AIAgentService.kt
                       (Android Foreground Service)
                              │
         ┌────────────────────┼──────────────────┐
         │                    │                   │
         ▼                    ▼                   ▼
    sendMessage()      ProcessCaptureGot    sendMessageWithImage()
      (chat flow)        (scene flow)         (vision_qa flow)
         │                    │                   │
         ▼                    ▼                   ▼
   chatOrchestrator    sceneOrchestrator    visionQAOrchestrator
   (持久化, PERSISTENT)  (按需创建, EPHEMERAL)  (按需创建, NONE)
                              │
         ┌────────────────────┴────────────────────────┐
         │              AgentLoopOrchestrator            │
         │                                               │
         │  execute(input, context)                      │
         │    → ensureSystemPrompt()                     │
         │    → addUserMessage()                         │
         │    → for i in 0..maxIterations:               │
         │        ① PreProcessor 链 → 瞬时上下文注入     │
         │        ② ModelCaller → LLM 调用               │
         │        ③ ├─ ToolCall → SafetyGuard → ToolExec │
         │           │   → 回填记忆 → continue            │
         │           └─ 文本 → PostProcessor → Terminator │
         │             → ResultCollector → AgentResult    │
         └──────────────────────────────────────────────┘
                              │
         ┌────────────────────┼────┬──────────┬──────┐
         ▼                    ▼    ▼          ▼      ▼
    ToolRegistry         PromptManager    ChatMemory  VehicleStatus
    (反射工具调度)        (模板管理)       (记忆策略)  (状态采集)
```

### 4.3 关键变更统计

| 指标 | 重构前 | 重构后 |
|------|--------|--------|
| Agent 引擎实现 | 3 套（MainAgentLoop/ChatServer/SceneServer） | 1 套（AgentLoopOrchestrator） |
| 工具调度方式 | 2 种（ToolRegistry + 手动 if-else 链） | 1 种（ToolExecutor → ToolRegistry） |
| 迭代控制 | 3 种（for/递归/单轮） | 1 种（for + terminator） |
| 安全审查 | 无 | SafetyGuard 接口 + 内置实现 |
| 错误处理 | catch-and-return-string | 结构化 AgentResult（8 种错误类型） |
| 并发状态 | 4 个 AtomicBoolean（不可查询） | AgentLoopState（isBusy 可查询） |
| 核心组件接口数 | 0 | 7 个 |
| 核心 Java 文件数 | 4（MainAgentLoop + 3 engines） | ~33（核心 + 组件 + 实现 + 工厂） |
| AIAgentService 引擎初始化 | ChatServer + SceneServer | AgentLoopOrchestrator + ToolRegistry |

### 4.4 架构原则

1. **单一引擎，配置驱动**——所有 Agent 执行收敛到 AgentLoopOrchestrator，不同人格通过 AgentConfig 配置定义，不新增 Java 类
2. **组件化管道**——7 个组件接口职责单一，每个环节可独立替换和测试
3. **安全优先**——SafetyGuard 在工具执行前拦截，否决不抛异常而是返回结构化信息给 LLM
4. **结构化结果**——AgentResult 承载成功/失败/错误类型/耗时/工具历史，替代 String 传递
5. **可观察状态**——AgentLoopState 对外提供 isBusy 查询，Service 层可以精准并发控制

### 4.5 遗留问题

1. **ChatServer.java / SceneServer.java**——保留源文件但不再实例化。工具调度代码已损坏（hasTool/handleToolRequest 已删除），需后续确认是否删除整个文件
2. **MainAgentLoop.java**——被 AgentLoopOrchestrator 完全替代，可考虑 @Deprecated 或删除
3. **AIDL 编译问题**——Windows 环境下的 Unicode 转义问题，预存在，非本次引入
4. **车速安全审查**——当前 SpeedBasedDoorLockGuard 通过 JSON 解析 `getSpeedStatus()` 获取车速，后续应接入真正的 SoaService 车速数据
