# 工具系统全面重构方案

## 1. 现状分析

### 1.1 问题清单

| 类别 | 问题 | 影响范围 |
|------|------|----------|
| **命名混乱** | VehicleWindowManager 混用驼峰 `setFlWindowStatus` 和下划线 `set_window_f_defrosting` | 1 个文件 |
| **Bug** | VehicleSpeedManager.hasTool 检查 `"vehicle_spd"`，实际方法名 `"set_vehicle_spd"` | 调度失败 |
| **Bug** | VehicleDMSManager.hasTool 检查无 `"set_"` 前缀（`"dms_drvie_fatigue"`） | 调度失败 |
| **Bug** | VehicleWindowManager.hasTool 中 `"no_window_opening_passengers"` 缺少 `"set_"` 前缀 | 调度失败 |
| **样板代码** | 每个 Manager 都有 ~20 行 `hasTool()` + ~25 行 `handleToolRequest()` | 10 个文件，共约 450 行 |
| **instanceof 链** | MainAgentLoop.dispatchToManager() 用 10 层 instanceof 判断 | 1 个文件 |
| **手动参数解析** | 每个 handleToolRequest 手动 parse `"arg0"`、`"arg1"` 并做 if-else 分发 | 10 个文件 |
| **注解不完整** | VlManager 的 `front_camera_save` 有 @P 但无 @Tool，不规范 | 1 个文件 |

### 1.2 影响文件

```
tools/
├── external/weather/WeatherUtils.java       (1 tool)
├── vision/vl/VlManager.java                 (1 tool)
└── vehicle/
    ├── door/VehicleDoorManager.java         (1 tool)
    ├── chassis/VehicleChassisManager.java   (1 tool)
    ├── speed/VehicleSpeedManager.java       (1 tool, BUG)
    ├── frag/VehicleFragManager.java         (2 tools)
    ├── dms/VehicleDMSManager.java           (3 tools, BUG)
    ├── window/VehicleWindowManager.java     (11 tools, 命名混乱 + BUG)
    ├── seat/VehicleSeatManager.java         (11 tools)
    └── ac/VehicleAcManager.java             (15 tools)
core/MainAgentLoop.java                      (instanceof 链)
```

---

## 2. 设计目标

1. **消除所有样板代码**：删除 `hasTool()` 和 `handleToolRequest()`
2. **修复所有已知 Bug**：3 个 hasTool 名称不匹配
3. **统一命名规范**：Java 方法名 = 驼峰，LLM 可见名 = `@Tool(name = "snake_case")`
4. **集中化工具派发**：一个 `ToolDispatcher` 处理所有参数解析和方法调用
5. **消除 instanceof 链**：用 Map-based 注册中心替代
6. **符合 LangChain4j 1.16.3 最佳实践**

---

## 3. 架构设计

### 3.1 核心思路

LangChain4j 的设计哲学是：**`@Tool` 注解只管"声明"，框架负责"执行"**。我们当前的问题是每个 Manager 自己实现了执行层（`hasTool` + `handleToolRequest`），导致 10 份重复代码。

重构后：
- Manager 只负责**业务逻辑** + **`@Tool` 声明**
- 一个集中式 `ToolDispatcher` 负责**所有参数解析和方法调用**
- 一个 `ToolRegistry` 负责**注册和路由**

### 3.2 新增文件

#### 3.2.1 `ToolDispatcher` — 工具执行核心

**路径**：`ai/langchain4j/tool/ToolDispatcher.java`

**包**：`com.hirain.aiagent.ai.langchain4j.tool`

**职责**：
1. 构造时扫描一个 Object 的所有 `@Tool` 方法
2. 为每个方法创建 `Method + ParameterMeta[]` 绑定
3. 提供 `dispatch(ToolExecutionRequest)` 方法：按 tool name 查找方法 → 解析 JSON 参数 → 反射调用 → 返回结果

**核心实现**：

```java
public class ToolDispatcher {

    private final Object target;
    private final Map<String, ToolBinding> bindings = new LinkedHashMap<>();

    public ToolDispatcher(Object target) {
        this.target = target;
        for (Method m : target.getClass().getDeclaredMethods()) {
            Tool tool = m.getAnnotation(Tool.class);
            if (tool == null) continue;
            String toolName = tool.name().isEmpty() ? m.getName() : tool.name();
            bindings.put(toolName, new ToolBinding(m, buildParamMeta(m)));
        }
    }

    public String dispatch(ToolExecutionRequest request) {
        ToolBinding binding = bindings.get(request.name());
        if (binding == null) {
            return "未知工具: " + request.name();
        }
        try {
            Object[] args = resolveArgs(binding, request.arguments());
            Object result = binding.method.invoke(target, args);
            return result == null ? "Success" : result.toString();
        } catch (Exception e) {
            return "工具执行失败: " + e.getMessage();
        }
    }

    public Set<String> getToolNames() { return bindings.keySet(); }
    public Object getTarget() { return target; }

    // ── 内部实现 ──
    private Object[] resolveArgs(ToolBinding binding, String arguments) throws Exception {
        JSONObject json = new JSONObject(arguments);
        Object[] args = new Object[binding.params.length];
        for (int i = 0; i < binding.params.length; i++) {
            args[i] = resolveArg(json, binding.params[i], i);
        }
        return args;
    }

    private Object resolveArg(JSONObject json, ParameterMeta meta, int index) {
        // 1) 尝试命名匹配
        if (json.has(meta.name)) {
            return coerce(json, meta.name, meta.type);
        }
        // 2) 尝试 P value 匹配
        // 3) 回退到位置参数 "arg0"、"arg1"
        String posKey = "arg" + index;
        if (json.has(posKey)) {
            return coerce(json, posKey, meta.type);
        }
        throw new IllegalArgumentException("缺少参数: " + meta.name);
    }

    private Object coerce(JSONObject json, String key, Class<?> type) {
        if (type == boolean.class || type == Boolean.class) return json.getBoolean(key);
        if (type == int.class || type == Integer.class)     return json.getInt(key);
        return json.getString(key);
    }

    // ── 内部类型 ──
    private static class ToolBinding {
        final Method method;
        final ParameterMeta[] params;
        ToolBinding(Method m, ParameterMeta[] p) { method = m; params = p; }
    }

    private static class ParameterMeta {
        final String name;
        final Class<?> type;
        ParameterMeta(String n, Class<?> t) { name = n; type = t; }
    }

    private ParameterMeta[] buildParamMeta(Method method) {
        Parameter[] params = method.getParameters();
        ParameterMeta[] metas = new ParameterMeta[params.length];
        for (int i = 0; i < params.length; i++) {
            String name = params[i].getName(); // 需 -parameters 编译参数
            P pAnn = params[i].getAnnotation(P.class);
            if (pAnn != null && !pAnn.value().isEmpty()) {
                name = pAnn.value(); // fallback 用 P value
            }
            // 实际优先用编译参数名，其次用 argN
            metas[i] = new ParameterMeta("arg" + i, params[i].getType());
        }
        return metas;
    }
}
```

**参数解析优先级**：`arg0`、`arg1` 位置参数优先（兼容 qwen 模型输出），后续可配置。

#### 3.2.2 `ToolRegistry` — 工具注册中心

**路径**：`ai/langchain4j/tool/ToolRegistry.java`

```java
public class ToolRegistry {

    // toolName → Dispatcher
    private final Map<String, ToolDispatcher> dispatchers = new LinkedHashMap<>();
    private List<ToolSpecification> allSpecs = List.of();

    public void register(Object... managers) {
        List<ToolSpecification> specs = new ArrayList<>();
        for (Object mgr : managers) {
            ToolDispatcher dispatcher = new ToolDispatcher(mgr);
            List<ToolSpecification> mgrSpecs =
                ToolSpecifications.toolSpecificationsFrom(mgr.getClass());
            for (ToolSpecification spec : mgrSpecs) {
                dispatchers.put(spec.name(), dispatcher);
            }
            specs.addAll(mgrSpecs);
        }
        allSpecs = List.copyOf(specs);
    }

    public String dispatch(ToolExecutionRequest request) {
        ToolDispatcher d = dispatchers.get(request.name());
        return d != null ? d.dispatch(request) : "无效工具: " + request.name();
    }

    public List<ToolSpecification> getToolSpecifications() { return allSpecs; }
}
```

### 3.3 MainAgentLoop 改造

**改造前**（约 50 行工具相关代码）：
```java
// 10 个独立字段
private final WeatherUtils weatherUtils;
private final VehicleDoorManager doorManager;
// ... 8 more ...

// 构造函数中 10 次实例化
weatherUtils = new WeatherUtils("xxx");
doorManager = new VehicleDoorManager();
// ... 8 more ...

// Map<String, ToolExecutor> toolRegistry + registerTools()
private final Map<String, ToolExecutor> toolRegistry = new LinkedHashMap<>();
private List<ToolSpecification> registerTools() {
    // 10 个 ToolSpecifications.toolSpecificationsFrom() + lambda
}

// 10 层 instanceof
private String dispatchToManager(Object manager, ToolExecutionRequest) { ... }
```

**改造后**（约 15 行）：
```java
private final ToolRegistry toolRegistry = new ToolRegistry();

public MainAgentLoop(Context context) {
    // ... model, memory, prompt ...

    toolRegistry.register(
        new WeatherUtils("c9af807ed95f93b56855a928417586f9"),
        new VehicleDoorManager(),
        new VehicleWindowManager(),
        new VehicleSeatManager(),
        new VehicleAcManager(),
        new VehicleChassisManager(),
        new VehicleFragManager(),
        new VehicleSpeedManager(),
        new VehicleDMSManager(),
        new VlManager(context)
    );
    toolSpecifications = toolRegistry.getToolSpecifications();
}

private String dispatchTool(ToolExecutionRequest request) {
    return toolRegistry.dispatch(request);
}
// registerTools() — 删除
// dispatchToManager() — 删除
```

### 3.4 Manager 改造示例

以 **VehicleDoorManager** 为例（最简单，1 个工具）：

```java
package com.hirain.aiagent.tools.vehicle.door;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import com.hirain.aiagent.infra.soa.SoaService;
import org.json.JSONException;
import org.json.JSONObject;

public class VehicleDoorManager {

    private static final String KEY_DOOR_FL_STATUS = "左前门开启";
    private static final String KEY_DOOR_FR_STATUS = "右前门开启";
    private static final String KEY_DOOR_RL_STATUS = "左后门开启";
    private static final String KEY_DOOR_RR_STATUS = "右后门开启";
    private static final String KEY_DOOR_LOCKED = "车门闭锁";

    private boolean door_fl_open;
    private boolean door_fr_open;
    private boolean door_rl_open;
    private boolean door_rr_open;
    private boolean door_locked;
    private boolean formalfunc = false;

    public VehicleDoorManager() {
        this.door_fl_open = false;
        this.door_fr_open = false;
        this.door_rl_open = false;
        this.door_rr_open = false;
        this.door_locked = false;
    }

    /** 查询车门状态（非工具方法，供 MainAgentLoop 调用） */
    public String getDoorStatus() {
        SoaService.Companion.getInstance().getDoorStatus();
        try {
            JSONObject json = new JSONObject();
            json.put(KEY_DOOR_FL_STATUS, door_fl_open);
            json.put(KEY_DOOR_FR_STATUS, door_fr_open);
            json.put(KEY_DOOR_RL_STATUS, door_rl_open);
            json.put(KEY_DOOR_RR_STATUS, door_rr_open);
            json.put(KEY_DOOR_LOCKED, door_locked);
            return json.toString();
        } catch (JSONException e) {
            return "获取车门状态失败。";
        }
    }

    /**
     * 控制车门闭锁/解锁。
     * 当用户要求锁车、解锁、闭锁车门时调用此工具。
     */
    @Tool(name = "set_door_lock", value = "控制车门闭锁/解锁。当用户要求锁车、解锁车门时调用。")
    public String setDoorLock(
            @P("车门闭锁传 true，解锁传 false") boolean lock) {
        SoaService.Companion.getInstance().set_door_lock(lock);
        if (!lock) {
            if (formalfunc) this.door_locked = false;
            return "车门解锁成功";
        }
        if (door_fl_open || door_fr_open || door_rl_open || door_rr_open) {
            return "车门未关闭，车门闭锁失败";
        }
        if (formalfunc) this.door_locked = true;
        return "车门闭锁成功";
    }
    // hasTool() — 已删除
    // handleToolRequest() — 已删除
}
```

---

## 4. 命名与注解规范

### 4.1 Java 方法名：驼峰

| 旧（混乱） | 新（统一） |
|-----------|-----------|
| `setFlWindowStatus` | `setFlWindowStatus` |
| `set_window_f_defrosting` | `setWindowFDefrosting` |
| `set_door_lock` | `setDoorLock` |
| `set_ac_drive_temp` | `setAcDriveTemp` |
| `front_camera_interaction` | `frontCameraInteraction` |
| `getWeatherForecast` | `getWeatherForecast`（已有工具，保持不变） |
| `set_dms_drvie_fatigue` | `setDmsDriveFatigue`（同时修复 typingo `drvie`→`drive`） |

### 4.2 `@Tool` 注解

```java
@Tool(
    name = "set_door_lock",           // LLM 可见名，蛇形下划线
    value = "控制车门闭锁/解锁。" +    // 工具描述
            "当用户要求锁车、解锁车门时调用。"
)
```

**描述规范**：`[功能描述]。[触发条件]。`

### 4.3 `@P` 注解

```java
// Boolean 参数
@P("true 表示开启，false 表示关闭")

// Int 参数（有范围）
@P("温度值（摄氏度），范围 16-31")

// String 参数（有枚举值）
@P("模式，可选：'内循环'、'外循环'、'自动'")
```

---

## 5. 实施步骤

### Phase 1 — 新建基础设施

| 步骤 | 文件 | 说明 |
|------|------|------|
| 1 | `ai/langchain4j/tool/ToolDispatcher.java` | 新建：反射扫描 + 参数解析 + 方法调用 |
| 2 | `ai/langchain4j/tool/ToolRegistry.java` | 新建：注册中心 + 路由 |
| 3 | `app/build.gradle.kts` | 确认 `buildFeatures { aidl = true }` 无需额外配置（无需 kapt） |

### Phase 2 — 简单 Manager（1-2 个工具）

| 步骤 | 文件 | 改动 |
|------|------|------|
| 4 | `VehicleDoorManager.java` | 删除 hasTool/handleToolRequest，加 @Tool(name=)，方法名改驼峰 |
| 5 | `VehicleChassisManager.java` | 同上 |
| 6 | `VehicleSpeedManager.java` | 同上，**修复 hasTool Bug**（原检查 "vehicle_spd"） |
| 7 | `VehicleFragManager.java` | 同上 |

### Phase 3 — 中等 Manager（3 个工具）+ Bug 修复

| 步骤 | 文件 | 改动 |
|------|------|------|
| 8 | `VehicleDMSManager.java` | 删除 hasTool/handleToolRequest，**修复 hasTool Bug**（缺少 set_ 前缀），修复 typingo `drvie`→`drive` |

### Phase 4 — 复杂 Manager（11-15 个工具）

| 步骤 | 文件 | 改动 |
|------|------|------|
| 9 | `VehicleWindowManager.java` | **统一命名**：6 个驼峰→保持，5 个蛇形→改驼峰，加 @Tool(name=)，**修复 Bug** |
| 10 | `VehicleSeatManager.java` | 删除 hasTool/handleToolRequest，方法名改驼峰，加 @Tool(name=) |
| 11 | `VehicleAcManager.java` | 同上（15 个工具，改动量最大） |

### Phase 5 — 特殊 Manager

| 步骤 | 文件 | 改动 |
|------|------|------|
| 12 | `WeatherUtils.java` | 删除 hasTool/handleToolRequest，@Tool(name=) 保持 "getWeatherForecast" |
| 13 | `VlManager.java` | 删除 hasTool/handleToolRequest，移除无效的 @P（无 @Tool 的方法），@Tool(name=) 保持 "front_camera_interaction" |

### Phase 6 — 主循环集成

| 步骤 | 文件 | 改动 |
|------|------|------|
| 14 | `MainAgentLoop.java` | 替换 10 字段 + instanceof 链 → ToolRegistry。保留 8 个 Manager 字段用于 `getVehicleStatus()`。 |

---

## 6. 前后对比

| 指标 | 改造前 | 改造后 |
|------|--------|--------|
| hasTool() 方法 | 10 个，约 200 行 | 0 |
| handleToolRequest() 方法 | 10 个，约 250 行 | 0 |
| instanceof 分支 | 10 个 | 0 |
| 新增集中式代码 | 0 | ~120 行（ToolDispatcher + ToolRegistry） |
| **净减少** | | **~330 行** |
| Bug | 3 个 | 0 |
| 命名不一致 | 1 个文件 6 处 | 0 |
| Java 方法风格 | 混合 | 统一驼峰 |[tool-system-refactoring-summary.md](../act_summary/tool-system-refactoring-summary.md)
| LLM 可见名 | 混合 | 统一下划线 |

---

## 7. ProGuard 规则

`app/proguard-rules.pro` 中需确保：
[tool-system-refactoring-summary.md](../act_summary/tool-system-refactoring-summary.md)
```proguard
# 保留 LangChain4j @Tool 注解（反射调用）
-keep @dev.langchain4j.agent.tool.Tool class * {
    @dev.langchain4j.agent.tool.Tool <methods>;
}

# 保留 ToolDispatcher 反射调用的目标方法
-keepclassmembers class com.hirain.aiagent.tools.** {
    @dev.langchain4j.agent.tool.Tool *;
}
```

---

## 8. 验证方案

1. `gradlew assembleDebug` 编译通过
2. 检查 `ToolDispatcher` 构造时扫描到的方法数 = 所有 @Tool 方法总数（46 个）
3. 在 ChatServer/SceneServer 中同样用 ToolRegistry 替换手写的 hasTool/if-else dispatch（后续 Phase）
4. 确认 `toolSpecifications` 的 name 全为 snake_case，与 LLM 调用请求一致
