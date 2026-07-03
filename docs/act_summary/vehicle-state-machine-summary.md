# 虚拟车辆状态机实现总结

## 概述

为 AIAgent 项目引入极简虚拟车辆状态机，替换原有的分散状态管理 + SoaService 外部调用模式，使 Demo 阶段 tool 调用真正可观测到状态变化。一次用户操作（如"空调设置为25度"）经过 LLM 解析 → tool 调用 → 状态机参数校验 → 状态更新 → 下次查询返回新值，形成完整闭环。

---

## 一、做了什么

### 1.1 新增状态 POJO 层（9 文件）

在 `VirtualStateMachine/state/` 下创建了 8 个子系统状态类，以及在 `VirtualStateMachine/` 下创建 VehicleStateMachine：

| 文件 | 包 | 字段数 | 职责 |
|------|-----|--------|------|
| `state/AcState.java` | `VirtualStateMachine.state` | 15 | 空调开关、温度、风量、模式、出风口 |
| `state/DoorState.java` | `VirtualStateMachine.state` | 5 | 四门状态 + 闭锁，含 `isAnyDoorOpen()` |
| `state/WindowState.java` | `VirtualStateMachine.state` | 11 | 四窗/天窗/遮阳帘/除霜/加热/后视镜 |
| `state/SeatState.java` | `VirtualStateMachine.state` | 11 | 四座加热/通风、按摩、方向盘加热 |
| `state/SpeedState.java` | `VirtualStateMachine.state` | 1 | 车速 |
| `state/ChassisState.java` | `VirtualStateMachine.state` | 1 | 底盘模式 |
| `state/FragState.java` | `VirtualStateMachine.state` | 2 | 香氛类型/浓度 |
| `state/DmsState.java` | `VirtualStateMachine.state` | 3 | 疲劳/分心/情绪 |
| `VehicleStateMachine.java` | `VirtualStateMachine` | — | 持有上述 8 个状态对象，提供所有 tool 方法 + 参数校验 |

### 1.2 改造 8 个 Vehicle*Manager

每个 Manager 的改动：

- **删除**：所有本地状态字段、`formalfunc` 标志位、`SoaService.Companion.getInstance().xxx()` 调用
- **新增**：`VehicleStateMachine stateMachine` 构造函数参数
- **委托**：`@Tool` 方法直接调 `stateMachine.setXxx()`，`getXxxStatus()` 调 `stateMachine.getXxxStatus()`

### 1.3 修改集成层（2 文件）

- **`AIAgentService.kt`**：`onCreate()` 中先创建 `vehicleStateMachine`，传入所有 Manager 构造函数；`ProcessCaptureGot()` 中的 scene 场景也复用同一个状态机实例
- **`MainAgentLoop.java`**（旧 Agent 入口）：同样引入 `VehicleStateMachine`，传入所有 Manager

---

## 二、状态机系统设计

### 2.1 整体架构

```
┌──────────────────────────────────────────────────────┐
│                  VehicleStateMachine                   │
│  (单一实例，项目中所有 tool 读写状态的唯一入口)        │
│                                                        │
│  持有 8 个 State 对象：                                 │
│  ┌──────┐ ┌────────┐ ┌─────────┐ ┌─────────┐          │
│  │AcState│ │DoorState│ │WindowState│ │SeatState│         │
│  └──────┘ └────────┘ └─────────┘ └─────────┘          │
│  ┌─────────┐ ┌──────────┐ ┌─────────┐ ┌─────────┐      │
│  │SpeedState││ChassisState││FragState ││DmsState  │      │
│  └─────────┘ └──────────┘ └─────────┘ └─────────┘      │
│                                                        │
│  方法: setAcStatus() setDoorLock() setFlWindowStatus()  │
│        setSeatFlHeat() setVehicleSpd() setChassisMode() │
│        setFragType() setDmsDriveFatigue() ...           │
│        getAcStatus() getDoorStatus() ... (JSON 返回)    │
└──────────────────────┬───────────────────────────────┘
                       │ 注入构造函数
        ┌──────────────┼─────────────────┐
        ▼              ▼                  ▼
  VehicleAcManager  VehicleDoorManager  ...(2个.other)
    (@Tool 方法)     (@Tool 方法)
        │              │
        └──────────────┘
               │  dispatch()
               ▼
         ToolRegistry
               │  ToolExecutionRequest
               ▼
         AgentLoopOrchestrator
               │  ChatRequest
               ▼
         LLM (qwen-turbo)
```

### 2.2 状态变更流程

一次完整的工具调用链路：

```
用户: "空调设置为25度"
  ↓
LLM 解析 → ToolExecutionRequest("set_ac_drive_temp", {"arg0": 25})
  ↓
ToolRegistry.dispatch(request)
  ↓
VehicleAcManager.setAcDriveTemp(25)
  ↓
VehicleStateMachine.setAcDriveTemp(25)
  ├── 参数校验: 16 ≤ 25 ≤ 31 → 通过
  ├── acState.setAcDriveTemp(25)  ← 状态变更
  └── return "主驾温度调节成功"
  ↓
AgentLoop 收到结果 → 回填记忆 → 下次迭代 LLM
  ↓
下次注入车辆状态时 getAcStatus() 返回温度=25 ✅
```

### 2.3 参数校验规则

状态机在写入前对所有数值和枚举做校验，非法输入返回失败原因字符串（而非抛异常），以便 LLM 感知并修正行为：

| 参数场景 | 校验规则 | 失败示例 |
|----------|----------|----------|
| 空调温度 | 16-31 | "主驾温度16-31摄氏度，当前值：35" |
| 空调风量 | 1-7 | "风量挡位1-7，当前值：9" |
| 车窗/天窗/遮阳帘 | 0-100 | "左前车窗范围 0-100%，当前值：120" |
| 座椅通风 | 0-100 | "左前座椅通风范围 0-100%，当前值：-1" |
| 车速 | 0-240 | "车速范围0-240 km/h，当前值：300" |
| 干燥除味模式 | 关闭/标准清洁/深度清洁 | "无效的干燥除味模式：xxx" |
| 循环模式 | 内循环/外循环/自动 | "无效的循环模式：xxx" |
| 底盘模式 | 普通模式/越野模式/雪地模式 | "无效的底盘行驶模式：xxx" |
| 香氛类型 | 晨间松木/正午丁香/午夜橙香 | "无效的香氛类型：xxx" |
| 香氛浓度 | 关闭/低/中/高 | "无效的香氛强度：xxx" |
| 按摩模式 | 波浪/脉冲/揉捏/震动/腰部聚焦 | "无效的按摩模式：xxx" |
| 按摩强度 | 关闭/弱/中等/强力 | "无效的按摩强度：xxx" |
| DMS 疲劳 | 清醒/轻度/中度/重度 | "无效的疲劳等级：xxx" |
| DMS 分心 | 专注/轻度分心/中度分心/重度分心 | "无效的分心等级：xxx" |
| DMS 情绪 | 中性/高兴/惊讶/悲伤/愤怒/厌恶/恐惧 | "无效的情绪：xxx" |
| 车门闭锁 | 锁车前所有门必须关闭 | "车门未关闭，车门闭锁失败" |

### 2.4 默认初始状态

启动时各子系统默认值：

| 系统 | 默认状态 |
|------|----------|
| 空调 | 关闭，主驾/副驾 26°C，风量 1，自动循环，全部出风口关 |
| 车门 | 四门关闭，未锁 |
| 车窗 | 全部 0%（关闭），除霜/加热/后视镜加热全关 |
| 座椅 | 加热全关，通风 0%，按摩波浪模式+关闭，方向盘加热关 |
| 车速 | 70 km/h |
| 底盘 | 普通模式 |
| 香氛 | 晨间松木，浓度关闭 |
| DMS | 清醒，专注，中性 |

---

## 三、状态机与外界的联系

### 3.1 与 Agent 中枢的连接链路

```
AIAgentService.onCreate()
  └── VehicleStateMachine vehicleStateMachine = new VehicleStateMachine()
  └── VehicleAcManager(vehicleStateMachine)     ← 注入状态机
  └── VehicleDoorManager(vehicleStateMachine)   ← 注入状态机
  └── (其他 6 个 Manager 同理)
  └── ToolRegistry.registerAll(acManager, doorManager, ...)  ← 注册 @Tool 方法
  └── AgentLoopOrchestrator(toolRegistry)       ← Agent 中枢通过 registry 调度
```

### 3.2 无需修改的部分（向后兼容）

- **`ToolRegistry.java`** / **`ToolDispatcher.java`** → 未改动。Manager 的 @Tool 注解和方法签名不变，dispatch 机制正常工作
- **`AgentLoopOrchestrator.java`** → 未改动。它只通过 `ToolRegistry.dispatch(request)` 调用工具，内部由哪个对象执行完全透明
- **`AgentConfigFactory.java`** → 未改动。factory 接收 `VehicleSpeedManager` 参数，新的 SpeedManager 接口（getSpeedStatus/setVehicleSpd）完全兼容
- **`VehicleStatusPreProcessor.java`** → 未改动。它调用 `statusProvider.get()` 获取整车状态 JSON，statusProvider 读取的是 `vehicleStateMachine.getXxxStatus()` 的返回值，JSON key 名称与改造前完全一致

### 3.3 状态收集路径（与 Agent 中枢的正常连接）

PreProcessor 中注入车辆状态的路径，改造前后对比：

```
改造前: statusProvider → manager.getDoorStatus() → 本地字段 → JSON
改造后: statusProvider → manager.getDoorStatus() → stateMachine.getDoorStatus() → acState.getter → JSON

两次 JSON 结构完全一致，Agent 中枢无感知
```

### 3.4 验证结论

编译通过（`compileDebugJavaWithJavac`），无语法/类型/依赖问题。所有 `@Tool` 方法的 name 和 value 不变，`ToolSpecifications.toolSpecificationsFrom()` 自动扫描正常。ToolRegistry 的注册和 dispatch 路径不需要任何修改。
