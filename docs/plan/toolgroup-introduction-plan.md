# ToolGroup Introduction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `AgentRuntime` 内引入轻量级 `ToolGroup` 选择层，基于 Phase 2 的 `IntentResult` 生成并记录本轮候选工具组，但不改变当前 AgentLoop、ToolRegistry 或 LLM 可见工具列表。

**Architecture:** 新增 `toolgroup` 包保存纯元数据模型、默认注册表和选择器；`AgentRuntime.startSession(...)` 在 IntentRouter 后调用 ToolGroupSelector，得到 `ToolGroupSelectionResult` 并写入 `RequestSession` 和 Trace。此阶段只做观测和后续策略准备，不把选择结果传给 `AgentLoopOrchestrator`，也不限制 LangChain4j tool specifications。

**Tech Stack:** Java、JUnit4、现有 `AgentRuntime` / `RequestSession` / `IntentResult` / `TraceContext` / `TraceSession`，无新依赖、无 LLM/NLP 模型、无安全裁决。

---

## 0. 范围和默认决策

### 0.1 本阶段生效范围

- 只对已经进入 `AgentRuntime` 的请求生效。
- 当前 demo 阶段真实路径只覆盖 `TEXT`，`VOICE`、`IMAGE`、`CONTROL` 不迁移。
- `ToolGroupSelectionResult` 只写入 `RequestSession` 和 Trace，不进入 `orchestratorContext`。
- `selectedToolNames` 是候选工具名记录，不用于裁剪 `ToolSpecification`。

### 0.2 明确不做的事

- 不修改 `AgentLoopOrchestrator` 的 Tool Calling 流程。
- 不修改 `ToolRegistry`、`ToolDispatcher`、`VehicleStateMachine` 的行为。
- 不复制完整 Tool schema，只保存 `@Tool(name=...)` 的 toolName 字符串。
- 不引入 Skill、Task、PolicyEngine、ContextOrchestrator、Eval。
- 不做安全裁决；`riskLevel` 只是字符串元信息。
- 不根据 ToolGroup 改变用户可见输出。

### 0.3 类型设计假设

- `riskLevel` 使用 `String`，取值先限定为 `"LOW"`、`"MEDIUM"`、`"HIGH"`；本阶段不新增 `ToolGroupRiskLevel`，避免引入安全策略含义。
- `ToolGroupSelectionResult.confidence` 复用 `IntentConfidence`，表示本次 ToolGroup 选择对 IntentResult 的继承置信度。
- `BASIC_STATUS_GROUP` 不绑定实际 `@Tool`，只声明 `requiredContextKeys=["user_id","vehicle_status"]`，表示候选工具组需要基础状态上下文。
- `BASIC_STATUS_GROUP` 只附加到车辆类意图；`WEATHER`、`VISION_QA`、`CHAT` 不附加它。`user_id` 仍由 `RequestSessionFactory` 注入 `orchestratorContext`，不依赖 ToolGroup。
- `COMMON_VEHICLE_GROUP` 包含所有车辆域 toolName，用于 `UNKNOWN` 但文本包含弱车载关键词时的候选组。
- `ALL_SAFE_DEMO_GROUP` 包含当前 demo 阶段所有 toolName，用于调试和未来策略，不作为默认选择。

### 0.4 Trace 字段

优先写入 Trace，不在 runtime 中引入 Android `Log` 依赖。新增字段：

- `agent.tool_group.selected_group_ids`
- `agent.tool_group.selected_tool_names`
- `agent.tool_group.selection_reason`
- `agent.tool_group.confidence`
- `agent.tool_group.fallback_used`

---

## 1. 目标文件结构

### 1.1 新增生产代码

- `app/src/main/java/com/hirain/aiagent/toolgroup/ToolGroupId.java`  
  工具组枚举。

- `app/src/main/java/com/hirain/aiagent/toolgroup/ToolGroup.java`  
  不可变工具组元数据。

- `app/src/main/java/com/hirain/aiagent/toolgroup/ToolGroupRegistry.java`  
  默认工具组注册表，负责 groupId 查询、toolName 查询和 toolName 合并。

- `app/src/main/java/com/hirain/aiagent/toolgroup/ToolGroupSelector.java`  
  工具组选择接口。

- `app/src/main/java/com/hirain/aiagent/toolgroup/DefaultToolGroupSelector.java`  
  基于 `IntentResult` 的默认选择器。

- `app/src/main/java/com/hirain/aiagent/toolgroup/ToolGroupSelectionResult.java`  
  单次工具组选择结果。

### 1.2 修改生产代码

- `app/src/main/java/com/hirain/aiagent/runtime/RequestSession.java`  
  新增 `ToolGroupSelectionResult toolGroupSelectionResult` 字段和 getter。

- `app/src/main/java/com/hirain/aiagent/runtime/RequestSessionFactory.java`  
  `create(...)` 新增 `ToolGroupSelectionResult` 参数，并写入 `RequestSession`。

- `app/src/main/java/com/hirain/aiagent/runtime/AgentRuntime.java`  
  新增 `ToolGroupSelector` 成员；`startSession(...)` 在 IntentRouter 后调用 selector；写入 Trace；执行路径不变。

### 1.3 新增和修改测试

- Create: `app/src/test/java/com/hirain/aiagent/toolgroup/ToolGroupRegistryTest.java`
- Create: `app/src/test/java/com/hirain/aiagent/toolgroup/DefaultToolGroupSelectorTest.java`
- Modify: `app/src/test/java/com/hirain/aiagent/runtime/RequestSessionFactoryTest.java`
- Modify: `app/src/test/java/com/hirain/aiagent/runtime/AgentRuntimeTest.java`
- Create: `app/src/test/java/com/hirain/aiagent/runtime/AgentRuntimeToolGroupTraceTest.java`

### 1.4 阶段总结文档

- Create: `docs/act_summary/toolgroup-introduction-summary.md`

---

## 2. 当前工具分组清单

### 2.1 第一版 ToolGroupId

```java
package com.hirain.aiagent.toolgroup;

public enum ToolGroupId {
    CHAT_ONLY_GROUP,
    BASIC_STATUS_GROUP,
    AC_GROUP,
    WINDOW_GROUP,
    SEAT_GROUP,
    DOOR_GROUP,
    CHASSIS_GROUP,
    FRAGRANCE_GROUP,
    DMS_GROUP,
    WEATHER_GROUP,
    VISION_GROUP,
    COMMON_VEHICLE_GROUP,
    ALL_SAFE_DEMO_GROUP
}
```

### 2.2 真实 toolName 分类

`AC_GROUP`：

```text
set_ac_status
set_ac_drive_temp
set_ac_assist_temp
set_ac_fan_intensity
set_ac_eco_mode
set_ac_anion_status
set_ac_clean_mode
set_ac_cyc_mode
set_ac_drive_sweep_auto
set_ac_assist_sweep_auto
set_ac_drive_left_air_outlet
set_ac_drive_right_air_outlet
set_ac_assist_air_outlet_mode
set_ac_assist_left_air_outlet
set_ac_assist_right_air_outlet
```

`WINDOW_GROUP`：

```text
set_fl_window_status
set_fr_window_status
set_rl_window_status
set_rr_window_status
set_top_window_status
set_sun_shadow_status
set_window_f_defrosting
set_window_r_heat
set_mirror_l_heat
set_mirror_r_heat
set_no_window_opening_passengers
```

`SEAT_GROUP`：

```text
set_seat_fl_heat
set_seat_fr_heat
set_seat_rl_heat
set_seat_rr_heat
set_seat_fl_air
set_seat_fr_air
set_seat_rl_air
set_seat_rr_air
set_seat_massage_mode
set_seat_massage_intensity
set_steering_heat
```

`DOOR_GROUP`：

```text
set_door_lock
```

`CHASSIS_GROUP`：

说明：当前没有单独 `SPEED_GROUP`。`set_vehicle_spd` 定义在 `VehicleSpeedManager`，第一版归入 `CHASSIS_GROUP`，因为车速/巡航设定与底盘动态域共同作为车辆动态控制候选组记录。

```text
set_chassis_mode
set_vehicle_spd
```

`FRAGRANCE_GROUP`：

```text
set_frag_type
set_frag_intensity
```

`DMS_GROUP`：

```text
set_dms_drive_fatigue
set_dms_drive_distractionlevel
set_dms_drive_emotion
```

`WEATHER_GROUP`：

```text
getWeatherForecast
```

`VISION_GROUP`：

```text
front_camera_interaction
```

`BASIC_STATUS_GROUP` 和 `CHAT_ONLY_GROUP` 第一版不包含 toolName。

`COMMON_VEHICLE_GROUP` 包含 AC、WINDOW、SEAT、DOOR、CHASSIS、FRAGRANCE、DMS 的全部 toolName。实现时必须通过已有车辆组列表动态合并，不手写第二份完整 toolName 清单。

`ALL_SAFE_DEMO_GROUP` 包含 COMMON_VEHICLE、WEATHER、VISION 的全部 toolName。实现时基于 `COMMON_VEHICLE_GROUP` 的合并结果再追加天气和视觉工具，不复制车辆清单。

---

## 3. 核心接口设计

### 3.1 ToolGroup

```java
package com.hirain.aiagent.toolgroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ToolGroup {
    private final ToolGroupId groupId;
    private final String groupName;
    private final String description;
    private final List<String> toolNames;
    private final List<String> requiredContextKeys;
    private final String riskLevel;
    private final boolean enabled;

    public ToolGroup(ToolGroupId groupId, String groupName, String description,
                     List<String> toolNames, List<String> requiredContextKeys,
                     String riskLevel, boolean enabled) {
        this.groupId = groupId;
        this.groupName = groupName;
        this.description = description;
        this.toolNames = Collections.unmodifiableList(new ArrayList<>(toolNames));
        this.requiredContextKeys = Collections.unmodifiableList(new ArrayList<>(requiredContextKeys));
        this.riskLevel = riskLevel;
        this.enabled = enabled;
    }

    public ToolGroupId groupId() { return groupId; }
    public String groupName() { return groupName; }
    public String description() { return description; }
    public List<String> toolNames() { return toolNames; }
    public List<String> requiredContextKeys() { return requiredContextKeys; }
    public String riskLevel() { return riskLevel; }
    public boolean enabled() { return enabled; }
}
```

### 3.2 ToolGroupSelectionResult

```java
package com.hirain.aiagent.toolgroup;

import com.hirain.aiagent.intentrouter.IntentConfidence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ToolGroupSelectionResult {
    private final List<ToolGroupId> selectedGroupIds;
    private final List<String> selectedToolNames;
    private final String selectionReason;
    private final IntentConfidence confidence;
    private final boolean fallbackUsed;

    private ToolGroupSelectionResult(List<ToolGroupId> selectedGroupIds,
                                     List<String> selectedToolNames,
                                     String selectionReason,
                                     IntentConfidence confidence,
                                     boolean fallbackUsed) {
        this.selectedGroupIds = Collections.unmodifiableList(new ArrayList<>(selectedGroupIds));
        this.selectedToolNames = Collections.unmodifiableList(new ArrayList<>(selectedToolNames));
        this.selectionReason = selectionReason;
        this.confidence = confidence;
        this.fallbackUsed = fallbackUsed;
    }

    public static ToolGroupSelectionResult of(List<ToolGroupId> selectedGroupIds,
                                              List<String> selectedToolNames,
                                              String selectionReason,
                                              IntentConfidence confidence,
                                              boolean fallbackUsed) {
        return new ToolGroupSelectionResult(selectedGroupIds, selectedToolNames,
                selectionReason, confidence, fallbackUsed);
    }

    public static ToolGroupSelectionResult fallback(String reason) {
        return new ToolGroupSelectionResult(
                List.of(ToolGroupId.CHAT_ONLY_GROUP), List.of(),
                reason, IntentConfidence.NONE, true);
    }

    public List<ToolGroupId> selectedGroupIds() { return selectedGroupIds; }
    public List<String> selectedToolNames() { return selectedToolNames; }
    public String selectionReason() { return selectionReason; }
    public IntentConfidence confidence() { return confidence; }
    public boolean fallbackUsed() { return fallbackUsed; }
}
```

### 3.3 ToolGroupSelector

```java
package com.hirain.aiagent.toolgroup;

import com.hirain.aiagent.intentrouter.IntentResult;

public interface ToolGroupSelector {
    ToolGroupSelectionResult select(IntentResult intentResult, String userInput);
}
```

---

## 4. 实施阶段

### Phase 1: ToolGroup 元数据与 Registry

目标：先建立完全独立、可单测的 toolgroup 元数据层，不接 Runtime。

#### Task 1: 新增 ToolGroupId、ToolGroup、ToolGroupSelectionResult

**Files:**
- Create: `app/src/main/java/com/hirain/aiagent/toolgroup/ToolGroupId.java`
- Create: `app/src/main/java/com/hirain/aiagent/toolgroup/ToolGroup.java`
- Create: `app/src/main/java/com/hirain/aiagent/toolgroup/ToolGroupSelectionResult.java`
- Test: `app/src/test/java/com/hirain/aiagent/toolgroup/ToolGroupRegistryTest.java`

- [ ] **Step 1: 写失败测试**

在 `ToolGroupRegistryTest` 中先写不可变性和 enum 覆盖测试：

```java
package com.hirain.aiagent.toolgroup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class ToolGroupRegistryTest {

    @Test
    public void toolGroupId_containsFirstVersionIds() {
        assertEquals(13, ToolGroupId.values().length);
        assertEquals(ToolGroupId.CHAT_ONLY_GROUP, ToolGroupId.valueOf("CHAT_ONLY_GROUP"));
        assertEquals(ToolGroupId.BASIC_STATUS_GROUP, ToolGroupId.valueOf("BASIC_STATUS_GROUP"));
        assertEquals(ToolGroupId.AC_GROUP, ToolGroupId.valueOf("AC_GROUP"));
        assertEquals(ToolGroupId.WINDOW_GROUP, ToolGroupId.valueOf("WINDOW_GROUP"));
        assertEquals(ToolGroupId.SEAT_GROUP, ToolGroupId.valueOf("SEAT_GROUP"));
        assertEquals(ToolGroupId.DOOR_GROUP, ToolGroupId.valueOf("DOOR_GROUP"));
        assertEquals(ToolGroupId.CHASSIS_GROUP, ToolGroupId.valueOf("CHASSIS_GROUP"));
        assertEquals(ToolGroupId.FRAGRANCE_GROUP, ToolGroupId.valueOf("FRAGRANCE_GROUP"));
        assertEquals(ToolGroupId.DMS_GROUP, ToolGroupId.valueOf("DMS_GROUP"));
        assertEquals(ToolGroupId.WEATHER_GROUP, ToolGroupId.valueOf("WEATHER_GROUP"));
        assertEquals(ToolGroupId.VISION_GROUP, ToolGroupId.valueOf("VISION_GROUP"));
        assertEquals(ToolGroupId.COMMON_VEHICLE_GROUP, ToolGroupId.valueOf("COMMON_VEHICLE_GROUP"));
        assertEquals(ToolGroupId.ALL_SAFE_DEMO_GROUP, ToolGroupId.valueOf("ALL_SAFE_DEMO_GROUP"));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void toolGroup_toolNamesAreImmutable() {
        ToolGroup group = new ToolGroup(ToolGroupId.AC_GROUP, "空调工具组", "空调控制",
                List.of("set_ac_status"), List.of("vehicle_status"), "MEDIUM", true);

        group.toolNames().add("another_tool");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.toolgroup.ToolGroupRegistryTest"
```

Expected: 编译失败，提示 `ToolGroupId` 或 `ToolGroup` 不存在。

- [ ] **Step 3: 实现 ToolGroupId**

按第 3.1 节代码创建 `ToolGroupId.java`，确保 13 个枚举值完整。

- [ ] **Step 4: 实现 ToolGroup**

按第 3.1 节代码创建 `ToolGroup.java`，使用不可变 List 防止外部修改。

- [ ] **Step 5: 实现 ToolGroupSelectionResult**

按第 3.2 节代码创建 `ToolGroupSelectionResult.java`。

- [ ] **Step 6: 运行测试确认通过**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.toolgroup.ToolGroupRegistryTest"
```

Expected: `BUILD SUCCESSFUL`。

#### Task 2: 新增 ToolGroupRegistry

**Files:**
- Create: `app/src/main/java/com/hirain/aiagent/toolgroup/ToolGroupRegistry.java`
- Modify test: `app/src/test/java/com/hirain/aiagent/toolgroup/ToolGroupRegistryTest.java`

- [ ] **Step 1: 追加失败测试**

在 `ToolGroupRegistryTest` 追加：

```java
@Test
public void defaultRegistry_containsAcAndStatusGroups() {
    ToolGroupRegistry registry = ToolGroupRegistry.defaultRegistry();

    ToolGroup ac = registry.group(ToolGroupId.AC_GROUP);
    ToolGroup status = registry.group(ToolGroupId.BASIC_STATUS_GROUP);

    assertEquals(ToolGroupId.AC_GROUP, ac.groupId());
    assertTrue(ac.toolNames().contains("set_ac_status"));
    assertTrue(ac.toolNames().contains("set_ac_drive_temp"));
    assertEquals("MEDIUM", ac.riskLevel());
    assertTrue(ac.enabled());
    assertTrue(status.requiredContextKeys().contains("user_id"));
    assertTrue(status.requiredContextKeys().contains("vehicle_status"));
}

@Test
public void defaultRegistry_canFindGroupsByToolName() {
    ToolGroupRegistry registry = ToolGroupRegistry.defaultRegistry();

    List<ToolGroup> groups = registry.groupsForToolName("set_ac_status");

    assertTrue(groups.stream().anyMatch(group -> group.groupId() == ToolGroupId.AC_GROUP));
    assertTrue(groups.stream().anyMatch(group -> group.groupId() == ToolGroupId.COMMON_VEHICLE_GROUP));
    assertTrue(groups.stream().anyMatch(group -> group.groupId() == ToolGroupId.ALL_SAFE_DEMO_GROUP));
}

@Test
public void defaultRegistry_mergesToolNamesInGroupOrderAndRemovesDuplicates() {
    ToolGroupRegistry registry = ToolGroupRegistry.defaultRegistry();

    List<String> toolNames = registry.toolNamesFor(List.of(
            ToolGroupId.AC_GROUP,
            ToolGroupId.BASIC_STATUS_GROUP,
            ToolGroupId.AC_GROUP));

    assertEquals(15, toolNames.size());
    assertEquals("set_ac_status", toolNames.get(0));
    assertTrue(toolNames.contains("set_ac_assist_right_air_outlet"));
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.toolgroup.ToolGroupRegistryTest"
```

Expected: 编译失败，提示 `ToolGroupRegistry` 不存在。

- [ ] **Step 3: 实现 ToolGroupRegistry 方法签名**

```java
package com.hirain.aiagent.toolgroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class ToolGroupRegistry {
    private final Map<ToolGroupId, ToolGroup> groups;

    private ToolGroupRegistry(Map<ToolGroupId, ToolGroup> groups) {
        this.groups = Collections.unmodifiableMap(new LinkedHashMap<>(groups));
    }

    public static ToolGroupRegistry defaultRegistry() {
        LinkedHashMap<ToolGroupId, ToolGroup> groups = new LinkedHashMap<>();
        // Step 4 填充完整注册表
        return new ToolGroupRegistry(groups);
    }

    public ToolGroup group(ToolGroupId groupId) {
        return groups.get(groupId);
    }

    public List<ToolGroup> allGroups() {
        return List.copyOf(groups.values());
    }

    public List<ToolGroup> groupsForToolName(String toolName) {
        List<ToolGroup> result = new ArrayList<>();
        for (ToolGroup group : groups.values()) {
            if (group.toolNames().contains(toolName)) {
                result.add(group);
            }
        }
        return result;
    }

    public List<String> toolNamesFor(List<ToolGroupId> groupIds) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (ToolGroupId groupId : groupIds) {
            ToolGroup group = groups.get(groupId);
            if (group != null && group.enabled()) {
                merged.addAll(group.toolNames());
            }
        }
        return List.copyOf(merged);
    }
}
```

- [ ] **Step 4: 填充 defaultRegistry**

在 `defaultRegistry()` 内添加局部列表和注册逻辑，按第 2.2 节真实 toolName 填充全部 13 个 group。关键要求：

```java
List<String> acTools = List.of("set_ac_status", "set_ac_drive_temp", "set_ac_assist_temp",
        "set_ac_fan_intensity", "set_ac_eco_mode", "set_ac_anion_status",
        "set_ac_clean_mode", "set_ac_cyc_mode", "set_ac_drive_sweep_auto",
        "set_ac_assist_sweep_auto", "set_ac_drive_left_air_outlet",
        "set_ac_drive_right_air_outlet", "set_ac_assist_air_outlet_mode",
        "set_ac_assist_left_air_outlet", "set_ac_assist_right_air_outlet");
```

`COMMON_VEHICLE_GROUP` 使用 `LinkedHashSet` 合并车辆组，`ALL_SAFE_DEMO_GROUP` 再追加 `getWeatherForecast` 和 `front_camera_interaction`。不要复制第二份完整车辆 toolName 清单。推荐在 `defaultRegistry()` 中按以下形状实现：

```java
LinkedHashSet<String> commonVehicleTools = new LinkedHashSet<>();
for (List<String> tools : List.of(acTools, windowTools, seatTools, doorTools,
        chassisTools, fragranceTools, dmsTools)) {
    commonVehicleTools.addAll(tools);
}

LinkedHashSet<String> allSafeDemoTools = new LinkedHashSet<>(commonVehicleTools);
allSafeDemoTools.addAll(weatherTools);
allSafeDemoTools.addAll(visionTools);
```

这样后续某个车辆组新增 toolName 时，只需要维护对应的基础组清单，`COMMON_VEHICLE_GROUP` 和 `ALL_SAFE_DEMO_GROUP` 会同步包含。

- [ ] **Step 5: 运行 Phase 1 测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.toolgroup.ToolGroupRegistryTest"
```

Expected: `BUILD SUCCESSFUL`。

### Phase 2: DefaultToolGroupSelector

目标：完成基于 `IntentResult` 的选择逻辑，仍不接 Runtime。

#### Task 3: 新增 ToolGroupSelector 和 DefaultToolGroupSelector

**Files:**
- Create: `app/src/main/java/com/hirain/aiagent/toolgroup/ToolGroupSelector.java`
- Create: `app/src/main/java/com/hirain/aiagent/toolgroup/DefaultToolGroupSelector.java`
- Create test: `app/src/test/java/com/hirain/aiagent/toolgroup/DefaultToolGroupSelectorTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.hirain.aiagent.toolgroup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.hirain.aiagent.intentrouter.IntentConfidence;
import com.hirain.aiagent.intentrouter.IntentResult;
import com.hirain.aiagent.intentrouter.IntentTag;

import org.junit.Test;

import java.util.List;

public class DefaultToolGroupSelectorTest {

    private final DefaultToolGroupSelector selector =
            new DefaultToolGroupSelector(ToolGroupRegistry.defaultRegistry());

    @Test
    public void select_vehicleAcReturnsAcAndBasicStatus() {
        IntentResult intent = IntentResult.of(IntentTag.VEHICLE_AC, IntentConfidence.HIGH,
                List.of("空调"), "打开空调", "TEXT", "matched:VEHICLE_AC");

        ToolGroupSelectionResult result = selector.select(intent, "打开空调");

        assertEquals(List.of(ToolGroupId.AC_GROUP, ToolGroupId.BASIC_STATUS_GROUP),
                result.selectedGroupIds());
        assertTrue(result.selectedToolNames().contains("set_ac_status"));
        assertTrue(result.selectedToolNames().contains("set_ac_drive_temp"));
        assertEquals("intent:VEHICLE_AC", result.selectionReason());
        assertEquals(IntentConfidence.HIGH, result.confidence());
        assertFalse(result.fallbackUsed());
    }

    @Test
    public void select_weatherReturnsWeatherGroupOnly() {
        IntentResult intent = IntentResult.of(IntentTag.WEATHER, IntentConfidence.MEDIUM,
                List.of("天气"), "今天北京天气", "TEXT", "matched:WEATHER");

        ToolGroupSelectionResult result = selector.select(intent, "今天北京天气");

        assertEquals(List.of(ToolGroupId.WEATHER_GROUP), result.selectedGroupIds());
        assertEquals(List.of("getWeatherForecast"), result.selectedToolNames());
        assertEquals("intent:WEATHER", result.selectionReason());
    }

    @Test
    public void select_chatReturnsChatOnlyGroup() {
        IntentResult intent = IntentResult.of(IntentTag.CHAT, IntentConfidence.LOW,
                List.of(), "讲个笑话", "TEXT", "fallback_chat");

        ToolGroupSelectionResult result = selector.select(intent, "讲个笑话");

        assertEquals(List.of(ToolGroupId.CHAT_ONLY_GROUP), result.selectedGroupIds());
        assertTrue(result.selectedToolNames().isEmpty());
        assertEquals("intent:CHAT", result.selectionReason());
    }

    @Test
    public void select_unknownWithWeakVehicleKeywordReturnsCommonVehicle() {
        IntentResult intent = IntentResult.unknown("车窗好像有问题", "TEXT", "empty_text");

        ToolGroupSelectionResult result = selector.select(intent, "车窗好像有问题");

        assertEquals(List.of(ToolGroupId.COMMON_VEHICLE_GROUP, ToolGroupId.BASIC_STATUS_GROUP),
                result.selectedGroupIds());
        assertTrue(result.selectedToolNames().contains("set_fl_window_status"));
        assertEquals("fallback:unknown_vehicle_keyword", result.selectionReason());
        assertTrue(result.fallbackUsed());
    }

    @Test
    public void select_unknownWithoutVehicleKeywordReturnsChatOnly() {
        IntentResult intent = IntentResult.unknown("随便聊聊", "TEXT", "empty_text");

        ToolGroupSelectionResult result = selector.select(intent, "随便聊聊");

        assertEquals(List.of(ToolGroupId.CHAT_ONLY_GROUP), result.selectedGroupIds());
        assertTrue(result.selectedToolNames().isEmpty());
        assertEquals("fallback:unknown_chat", result.selectionReason());
        assertTrue(result.fallbackUsed());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.toolgroup.DefaultToolGroupSelectorTest"
```

Expected: 编译失败，提示 `DefaultToolGroupSelector` 不存在。

- [ ] **Step 3: 实现 ToolGroupSelector**

按第 3.3 节代码创建接口。

- [ ] **Step 4: 实现 DefaultToolGroupSelector**

实现要求：

```java
package com.hirain.aiagent.toolgroup;

import com.hirain.aiagent.intentrouter.IntentConfidence;
import com.hirain.aiagent.intentrouter.IntentResult;
import com.hirain.aiagent.intentrouter.IntentTag;

import java.util.List;

public class DefaultToolGroupSelector implements ToolGroupSelector {
    private final ToolGroupRegistry registry;

    public DefaultToolGroupSelector(ToolGroupRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ToolGroupSelectionResult select(IntentResult intentResult, String userInput) {
        if (intentResult == null || intentResult.intentTag() == null) {
            return ToolGroupSelectionResult.fallback("fallback:null_intent");
        }
        List<ToolGroupId> groupIds = groupIdsFor(intentResult.intentTag(), textFor(intentResult, userInput));
        boolean fallback = intentResult.intentTag() == IntentTag.UNKNOWN;
        String reason = reasonFor(intentResult.intentTag(), textFor(intentResult, userInput));
        IntentConfidence confidence = intentResult.confidence() != null
                ? intentResult.confidence()
                : IntentConfidence.NONE;
        return ToolGroupSelectionResult.of(groupIds, registry.toolNamesFor(groupIds),
                reason, confidence, fallback);
    }

    private List<ToolGroupId> groupIdsFor(IntentTag tag, String text) {
        switch (tag) {
            case VEHICLE_AC:
                return List.of(ToolGroupId.AC_GROUP, ToolGroupId.BASIC_STATUS_GROUP);
            case VEHICLE_WINDOW:
                return List.of(ToolGroupId.WINDOW_GROUP, ToolGroupId.BASIC_STATUS_GROUP);
            case VEHICLE_SEAT:
                return List.of(ToolGroupId.SEAT_GROUP, ToolGroupId.BASIC_STATUS_GROUP);
            case VEHICLE_DOOR:
                return List.of(ToolGroupId.DOOR_GROUP, ToolGroupId.BASIC_STATUS_GROUP);
            case VEHICLE_CHASSIS:
                return List.of(ToolGroupId.CHASSIS_GROUP, ToolGroupId.BASIC_STATUS_GROUP);
            case VEHICLE_FRAGRANCE:
                return List.of(ToolGroupId.FRAGRANCE_GROUP, ToolGroupId.BASIC_STATUS_GROUP);
            case VEHICLE_DMS:
                return List.of(ToolGroupId.DMS_GROUP, ToolGroupId.BASIC_STATUS_GROUP);
            case WEATHER:
                return List.of(ToolGroupId.WEATHER_GROUP);
            case VISION_QA:
                return List.of(ToolGroupId.VISION_GROUP);
            case CHAT:
                return List.of(ToolGroupId.CHAT_ONLY_GROUP);
            case UNKNOWN:
            default:
                return hasWeakVehicleKeyword(text)
                        ? List.of(ToolGroupId.COMMON_VEHICLE_GROUP, ToolGroupId.BASIC_STATUS_GROUP)
                        : List.of(ToolGroupId.CHAT_ONLY_GROUP);
        }
    }

    private String reasonFor(IntentTag tag, String text) {
        if (tag == IntentTag.UNKNOWN) {
            return hasWeakVehicleKeyword(text)
                    ? "fallback:unknown_vehicle_keyword"
                    : "fallback:unknown_chat";
        }
        return "intent:" + tag.name();
    }

    private static String textFor(IntentResult intentResult, String userInput) {
        if (intentResult.normalizedText() != null && !intentResult.normalizedText().isEmpty()) {
            return intentResult.normalizedText();
        }
        return userInput != null ? userInput.trim().toLowerCase() : "";
    }

    private static boolean hasWeakVehicleKeyword(String text) {
        if (text == null || text.isEmpty()) return false;
        return text.contains("车") || text.contains("空调") || text.contains("车窗")
                || text.contains("窗户") || text.contains("座椅") || text.contains("车门")
                || text.contains("底盘") || text.contains("悬架") || text.contains("香氛")
                || text.contains("香薰") || text.contains("dms") || text.contains("驾驶员");
    }
}
```

- [ ] **Step 5: 运行 Phase 2 测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.toolgroup.*"
```

Expected: `BUILD SUCCESSFUL`。

### Phase 3: Runtime Session 集成

目标：将选择结果写入 `RequestSession`，不改变执行上下文。

#### Task 4: RequestSession 写入 ToolGroupSelectionResult

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/runtime/RequestSession.java`
- Modify: `app/src/main/java/com/hirain/aiagent/runtime/RequestSessionFactory.java`
- Modify test: `app/src/test/java/com/hirain/aiagent/runtime/RequestSessionFactoryTest.java`

- [ ] **Step 1: 写失败测试**

在 `RequestSessionFactoryTest` 追加：

同步补充 imports：

```java
import static org.junit.Assert.assertFalse;

import com.hirain.aiagent.toolgroup.ToolGroupId;
import com.hirain.aiagent.toolgroup.ToolGroupSelectionResult;
```

```java
@Test
public void create_storesToolGroupSelectionResultWithoutAddingToOrchestratorContext() {
    AgentRequest request = new AgentRequest();
    request.setInputType("TEXT");
    request.setText("打开空调");
    IntentResult intentResult = IntentResult.of(IntentTag.VEHICLE_AC, IntentConfidence.HIGH,
            List.of("空调"), "打开空调", "TEXT", "matched:VEHICLE_AC");
    ToolGroupSelectionResult toolGroups = ToolGroupSelectionResult.of(
            List.of(ToolGroupId.AC_GROUP, ToolGroupId.BASIC_STATUS_GROUP),
            List.of("set_ac_status"),
            "intent:VEHICLE_AC",
            IntentConfidence.HIGH,
            false);
    RequestSessionFactory factory = new RequestSessionFactory(() -> "req-fixed", () -> 1234L);

    RequestSession session = factory.create(request, "chat", null, intentResult, toolGroups);

    assertEquals(List.of(ToolGroupId.AC_GROUP, ToolGroupId.BASIC_STATUS_GROUP),
            session.toolGroupSelectionResult().selectedGroupIds());
    assertEquals("intent:VEHICLE_AC", session.toolGroupSelectionResult().selectionReason());
    assertFalse(session.orchestratorContext().containsKey("selected_tool_groups"));
    assertFalse(session.orchestratorContext().containsKey("selected_tool_names"));
}
```

- [ ] **Step 2: 更新旧测试调用点**

把所有 `factory.create(request, "chat", traceContext, intentResult)` 改为：

```java
factory.create(request, "chat", traceContext, intentResult,
        ToolGroupSelectionResult.fallback("test_default"))
```

- [ ] **Step 3: 修改 RequestSession**

新增字段、构造参数和 getter：

```java
private final ToolGroupSelectionResult toolGroupSelectionResult;
public ToolGroupSelectionResult toolGroupSelectionResult() { return toolGroupSelectionResult; }
```

- [ ] **Step 4: 修改 RequestSessionFactory**

签名改为：

```java
public RequestSession create(AgentRequest request, String personaId,
                             TraceContext traceContext, IntentResult intentResult,
                             ToolGroupSelectionResult toolGroupSelectionResult)
```

null 降级：

```java
if (toolGroupSelectionResult == null) {
    toolGroupSelectionResult = ToolGroupSelectionResult.fallback("missing_tool_group_selection");
}
```

传入 `RequestSession`，但不要放入 `orchestratorContext`。

- [ ] **Step 5: 运行 RequestSessionFactoryTest**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.runtime.RequestSessionFactoryTest"
```

Expected: `BUILD SUCCESSFUL`。

#### Task 5: AgentRuntime 接入 ToolGroupSelector

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/runtime/AgentRuntime.java`
- Modify test: `app/src/test/java/com/hirain/aiagent/runtime/AgentRuntimeTest.java`

- [ ] **Step 1: 写失败测试：Runtime 调用 selector 但不影响 executor**

在 `AgentRuntimeTest` 追加：

同步补充 imports：

```java
import static org.junit.Assert.assertFalse;

import com.hirain.aiagent.toolgroup.ToolGroupId;
import com.hirain.aiagent.toolgroup.ToolGroupSelectionResult;
import com.hirain.aiagent.toolgroup.ToolGroupSelector;
```

```java
@Test
public void startSession_selectsToolGroupsAndExecuteStillUsesOriginalExecutor() {
    IntentRouter router = (text, sourceInputType) ->
            IntentResult.of(IntentTag.VEHICLE_AC, IntentConfidence.HIGH,
                    List.of("空调"), text, sourceInputType, "matched:VEHICLE_AC");
    ToolGroupSelector selector = (intentResult, userInput) -> ToolGroupSelectionResult.of(
            List.of(ToolGroupId.AC_GROUP, ToolGroupId.BASIC_STATUS_GROUP),
            List.of("set_ac_status"),
            "intent:VEHICLE_AC",
            IntentConfidence.HIGH,
            false);
    AtomicReference<Map<String, Object>> context = new AtomicReference<>();
    AgentRuntime runtime = new AgentRuntime(
            (userInput, ctx) -> {
                context.set(ctx);
                return AgentResult.success("完成", 1, 10L, List.of());
            },
            router,
            selector,
            () -> "req-fixed",
            () -> 3000L);
    AgentRequest request = new AgentRequest();
    request.setInputType("TEXT");
    request.setText("打开空调");

    RequestSession session = runtime.startSession(request, null);
    RuntimeResult result = runtime.execute(session);

    assertEquals(List.of(ToolGroupId.AC_GROUP, ToolGroupId.BASIC_STATUS_GROUP),
            session.toolGroupSelectionResult().selectedGroupIds());
    assertTrue(result.success());
    assertFalse(context.get().containsKey("selected_tool_groups"));
    assertFalse(context.get().containsKey("selected_tool_names"));
}
```

- [ ] **Step 2: 写失败测试：selector 异常不影响执行**

```java
@Test
public void startSession_toolGroupSelectorExceptionFallsBackAndExecuteContinues() {
    ToolGroupSelector failingSelector = (intentResult, userInput) -> {
        throw new IllegalStateException("selector failed");
    };
    AgentRuntime runtime = new AgentRuntime(
            (userInput, ctx) -> AgentResult.success("继续执行", 1, 10L, List.of()),
            (text, sourceInputType) -> IntentResult.of(IntentTag.CHAT, IntentConfidence.LOW,
                    List.of(), text, sourceInputType, "fallback_chat"),
            failingSelector,
            () -> "req-fixed",
            () -> 3000L);

    RequestSession session = runtime.startSession(new AgentRequest(), null);
    RuntimeResult result = runtime.execute(session);

    assertEquals(List.of(ToolGroupId.CHAT_ONLY_GROUP),
            session.toolGroupSelectionResult().selectedGroupIds());
    assertEquals("tool_group_selector_exception",
            session.toolGroupSelectionResult().selectionReason());
    assertTrue(session.toolGroupSelectionResult().fallbackUsed());
    assertTrue(result.success());
}
```

- [ ] **Step 3: 修改 AgentRuntime 构造函数**

保留所有旧构造函数，并新增可注入 selector 的构造函数。当前已有 `(AgentExecutor, IdGenerator, TimeProvider)` 测试构造函数，必须显式保留并让它包装默认 `DefaultToolGroupSelector`，否则现有 `AgentRuntimeTest` 调用路径会缺少 selector。

```java
public AgentRuntime(AgentExecutor chatExecutor) {
    this(chatExecutor,
            new KeywordIntentRouter(),
            new DefaultToolGroupSelector(ToolGroupRegistry.defaultRegistry()),
            new UuidIdGenerator(),
            new SystemTimeProvider());
}

public AgentRuntime(AgentExecutor chatExecutor,
                    IdGenerator idGenerator,
                    TimeProvider timeProvider) {
    this(chatExecutor,
            new KeywordIntentRouter(),
            new DefaultToolGroupSelector(ToolGroupRegistry.defaultRegistry()),
            idGenerator,
            timeProvider);
}

public AgentRuntime(AgentExecutor chatExecutor,
                    IntentRouter intentRouter,
                    IdGenerator idGenerator,
                    TimeProvider timeProvider) {
    this(chatExecutor, intentRouter,
            new DefaultToolGroupSelector(ToolGroupRegistry.defaultRegistry()),
            idGenerator, timeProvider);
}

public AgentRuntime(AgentExecutor chatExecutor,
                    IntentRouter intentRouter,
                    ToolGroupSelector toolGroupSelector,
                    IdGenerator idGenerator,
                    TimeProvider timeProvider) {
    this.chatExecutor = chatExecutor;
    this.intentRouter = intentRouter;
    this.toolGroupSelector = toolGroupSelector;
    this.idGenerator = idGenerator;
    this.timeProvider = timeProvider;
    this.sessionFactory = new RequestSessionFactory(idGenerator, timeProvider);
}
```

- [ ] **Step 4: 修改 startSession**

核心顺序必须是：

```java
IntentResult intentResult = routeIntentSafely(request);
ToolGroupSelectionResult toolGroupSelectionResult =
        selectToolGroupsSafely(intentResult, request);
writeIntentToTrace(traceContext, intentResult);
writeToolGroupsToTrace(traceContext, toolGroupSelectionResult);
return sessionFactory.create(request, CHAT_PERSONA, traceContext,
        intentResult, toolGroupSelectionResult);
```

- [ ] **Step 5: 新增 selectToolGroupsSafely**

```java
private ToolGroupSelectionResult selectToolGroupsSafely(IntentResult intentResult, AgentRequest request) {
    String text = request != null && request.getText() != null ? request.getText() : "";
    try {
        ToolGroupSelectionResult result = toolGroupSelector.select(intentResult, text);
        return result != null
                ? result
                : ToolGroupSelectionResult.fallback("tool_group_selector_null");
    } catch (Exception e) {
        return ToolGroupSelectionResult.fallback("tool_group_selector_exception");
    }
}
```

- [ ] **Step 6: 运行 AgentRuntimeTest**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.runtime.AgentRuntimeTest"
```

Expected: `BUILD SUCCESSFUL`。

### Phase 4: Trace 和回归验证

目标：验证 observability 完成，并证明没有改变 AgentLoop 行为。

#### Task 6: 写入 ToolGroup Trace attributes

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/runtime/AgentRuntime.java`
- Create test: `app/src/test/java/com/hirain/aiagent/runtime/AgentRuntimeToolGroupTraceTest.java`

- [ ] **Step 1: 写失败测试**

创建 `AgentRuntimeToolGroupTraceTest`，用真实 `TraceSession` 验证 root span attribute：

```java
package com.hirain.aiagent.runtime;

import static org.junit.Assert.assertEquals;

import com.hirain.aiagent.AgentRequest;
import com.hirain.aiagent.core.AgentResult;
import com.hirain.aiagent.intentrouter.IntentConfidence;
import com.hirain.aiagent.intentrouter.IntentResult;
import com.hirain.aiagent.intentrouter.IntentTag;
import com.hirain.aiagent.toolgroup.ToolGroupId;
import com.hirain.aiagent.toolgroup.ToolGroupSelectionResult;
import com.hirain.aiagent.trace.TraceAttributeWriter;
import com.hirain.aiagent.trace.TraceConfig;
import com.hirain.aiagent.trace.TraceContext;
import com.hirain.aiagent.trace.TraceRedactor;
import com.hirain.aiagent.trace.TraceSession;
import com.hirain.aiagent.trace.TraceSpanNames;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class AgentRuntimeToolGroupTraceTest {

    @Test
    public void startSession_writesToolGroupSelectionToTrace() {
        CapturingExporter exporter = new CapturingExporter();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        Tracer tracer = tracerProvider.get("test");
        TraceSession traceSession = new TraceSession(
                tracer.spanBuilder(TraceSpanNames.AGENT_REQUEST).startSpan(),
                tracer,
                new TraceAttributeWriter(
                        TraceConfig.builder()
                                .contentCaptureMode(TraceConfig.ContentCaptureMode.REDACTED)
                                .build(),
                        new TraceRedactor()));

        AgentRuntime runtime = new AgentRuntime(
                (userInput, ctx) -> AgentResult.success("完成", 1, 1L, List.of()),
                (text, sourceInputType) -> IntentResult.of(IntentTag.VEHICLE_AC, IntentConfidence.HIGH,
                        List.of("空调"), text, sourceInputType, "matched:VEHICLE_AC"),
                (intentResult, userInput) -> ToolGroupSelectionResult.of(
                        List.of(ToolGroupId.AC_GROUP, ToolGroupId.BASIC_STATUS_GROUP),
                        List.of("set_ac_status"),
                        "intent:VEHICLE_AC",
                        IntentConfidence.HIGH,
                        false),
                () -> "req-fixed",
                () -> 3000L);
        AgentRequest request = new AgentRequest();
        request.setInputType("TEXT");
        request.setText("打开空调");

        runtime.startSession(request, new TraceContext("trace-1", "span-1", traceSession));
        traceSession.close();

        SpanData root = exporter.spans.get(0);
        assertEquals("AC_GROUP,BASIC_STATUS_GROUP", root.getAttributes()
                .get(AttributeKey.stringKey("agent.tool_group.selected_group_ids")));
        assertEquals("set_ac_status", root.getAttributes()
                .get(AttributeKey.stringKey("agent.tool_group.selected_tool_names")));
        assertEquals("intent:VEHICLE_AC", root.getAttributes()
                .get(AttributeKey.stringKey("agent.tool_group.selection_reason")));
        assertEquals("HIGH", root.getAttributes()
                .get(AttributeKey.stringKey("agent.tool_group.confidence")));
        assertEquals(false, root.getAttributes()
                .get(AttributeKey.booleanKey("agent.tool_group.fallback_used")));
        tracerProvider.close();
    }

    private static final class CapturingExporter implements SpanExporter {
        private final List<SpanData> spans = new ArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            this.spans.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.runtime.AgentRuntimeToolGroupTraceTest"
```

Expected: 断言失败或编译失败，因为 Trace attribute 尚未写入。

- [ ] **Step 3: 实现 writeToolGroupsToTrace**

在 `AgentRuntime` 中新增：

```java
private void writeToolGroupsToTrace(TraceContext traceContext,
                                    ToolGroupSelectionResult result) {
    if (traceContext == null || traceContext.session() == null || result == null) return;
    traceContext.session().setAttribute("agent.tool_group.selected_group_ids",
            result.selectedGroupIds().stream()
                    .map(Enum::name)
                    .collect(java.util.stream.Collectors.joining(",")));
    traceContext.session().setAttribute("agent.tool_group.selected_tool_names",
            String.join(",", result.selectedToolNames()));
    traceContext.session().setAttribute("agent.tool_group.selection_reason",
            result.selectionReason());
    traceContext.session().setAttribute("agent.tool_group.confidence",
            result.confidence().name());
    traceContext.session().setAttribute("agent.tool_group.fallback_used",
            result.fallbackUsed());
}
```

- [ ] **Step 4: 运行 Trace 测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.runtime.AgentRuntimeToolGroupTraceTest"
```

Expected: `BUILD SUCCESSFUL`。

#### Task 7: 回归验证和不变性检查

**Files:**
- Existing tests only

- [ ] **Step 1: 运行 ToolGroup 测试**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.toolgroup.*"
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 2: 运行 Runtime 测试**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.runtime.*"
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 3: 运行 IntentRouter 测试**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.intentrouter.*"
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 4: 运行全量 JVM 单测**

```powershell
.\gradlew.bat testDebugUnitTest --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 5: 静态检查执行路径不变**

Run:

```powershell
git diff -- app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java `
           app/src/main/java/com/hirain/aiagent/ai/langchain4j/tool/ToolRegistry.java `
           app/src/main/java/com/hirain/aiagent/ai/langchain4j/tool/ToolDispatcher.java `
           app/src/main/java/com/hirain/aiagent/VirtualStateMachine/VehicleStateMachine.java
```

Expected: 没有 diff。

- [ ] **Step 6: 静态检查 AIAgentService 只保留 Runtime TEXT 接入**

Run:

```powershell
Select-String -Path "app/src/main/java/com/hirain/aiagent/AIAgentService.kt" `
    -Pattern "agentRuntime.startSession|handleVoiceRequest|handleImageRequest|handleControlRequest" `
    -Context 1,2
```

Expected:

- `agentRuntime.startSession(...)` 仍只在 TEXT 链路中出现。
- `handleVoiceRequest` 不迁移。
- `handleImageRequest` 不迁移。
- `handleControlRequest` 不迁移。

### Phase 5: 阶段总结

#### Task 8: 写入阶段总结文档

**Files:**
- Create: `docs/act_summary/toolgroup-introduction-summary.md`

- [ ] **Step 1: 写入总结**

总结必须包含：

- 新增 `ToolGroupId` / `ToolGroup` / `ToolGroupRegistry` / `ToolGroupSelector` / `DefaultToolGroupSelector` / `ToolGroupSelectionResult`。
- 第一版 ToolGroupId 和真实 toolName 分类。
- `RequestSession` 新增 `toolGroupSelectionResult`。
- `AgentRuntime` 在 IntentRouter 之后调用 ToolGroupSelector。
- selected group/tool 只写入 `RequestSession` 和 Trace，不影响 executor，不进入 `orchestratorContext`。
- 未修改 `ToolRegistry`、`ToolDispatcher`、`AgentLoopOrchestrator`、`VehicleStateMachine`。
- 说明 ToolGroup 的 toolName 字符串与 `@Tool(name=...)` 之间是人工维护关系；本阶段不引入自动一致性检查，后续新增或改名工具时需同步更新 `ToolGroupRegistry`。
- 实际执行过的测试命令和结果。

- [ ] **Step 2: 记录后续阶段**

后续建议写清楚：

1. Phase 4 Context 阶段再根据 ToolGroup 限制 LLM 可见工具。
2. PolicyEngine 阶段再解释 `riskLevel`，当前它只是元信息。
3. Eval 阶段建立 intent → selectedToolGroups 的回归集。
4. 如果后续迁移真实 VOICE 到 Runtime，ToolGroup 会随 IntentResult 自动生效。

---

## 5. 验收标准

### 5.1 功能验收

- `ToolGroupId` 至少包含 13 个第一版枚举值。
- `ToolGroupRegistry.defaultRegistry()` 能返回所有 group。
- `ToolGroupRegistry` 能按 `ToolGroupId` 查询 group。
- `ToolGroupRegistry` 能按 toolName 反查包含该 tool 的 group。
- `DefaultToolGroupSelector` 能按 `IntentTag` 选择候选 group：
  - `VEHICLE_AC` → `AC_GROUP + BASIC_STATUS_GROUP`
  - `VEHICLE_WINDOW` → `WINDOW_GROUP + BASIC_STATUS_GROUP`
  - `VEHICLE_SEAT` → `SEAT_GROUP + BASIC_STATUS_GROUP`
  - `VEHICLE_DOOR` → `DOOR_GROUP + BASIC_STATUS_GROUP`
  - `VEHICLE_CHASSIS` → `CHASSIS_GROUP + BASIC_STATUS_GROUP`
  - `VEHICLE_FRAGRANCE` → `FRAGRANCE_GROUP + BASIC_STATUS_GROUP`
  - `VEHICLE_DMS` → `DMS_GROUP + BASIC_STATUS_GROUP`
  - `WEATHER` → `WEATHER_GROUP`
  - `VISION_QA` → `VISION_GROUP`
  - `CHAT` → `CHAT_ONLY_GROUP`
  - `UNKNOWN` + 弱车载关键词 → `COMMON_VEHICLE_GROUP + BASIC_STATUS_GROUP`
  - `UNKNOWN` + 无弱车载关键词 → `CHAT_ONLY_GROUP`
- `ToolGroupSelectionResult` 包含 `selectedGroupIds`、`selectedToolNames`、`selectionReason`、`confidence`、`fallbackUsed`。
- `AgentRuntime.startSession(...)` 将选择结果写入 `RequestSession`。
- ToolGroup 选择异常不会阻断 `AgentRuntime.execute(...)`。
- Trace 中能看到 selected group/tool 信息。

### 5.2 非功能验收

- 不引入新依赖。
- 不调用 LLM。
- 不执行 Tool。
- 不修改 LangChain4j 实际绑定给 LLM 的 tool specification 列表。
- 不修改 ToolRegistry / ToolDispatcher / VehicleStateMachine 行为。
- 不修改 AgentLoopOrchestrator 的工具执行逻辑。
- TEXT 用户可见输出与迁移前基本一致。

### 5.3 手动验收建议

在现有 TEXT demo 对话中观察 Trace：

- 输入“打开空调”，Trace 应包含 `agent.intent.tag=VEHICLE_AC`，并包含 `agent.tool_group.selected_group_ids=AC_GROUP,BASIC_STATUS_GROUP`。
- 输入“今天北京天气怎么样”，Trace 应包含 `agent.tool_group.selected_group_ids=WEATHER_GROUP`。
- 输入“你好，讲个笑话”，Trace 应包含 `agent.tool_group.selected_group_ids=CHAT_ONLY_GROUP`。
- 用户可见回复仍由原 AgentLoop 生成，不因 ToolGroup 选择改变。

---

## 6. 自检结果

- 需求覆盖：任务目标 1-10 均已映射到 Phase 1-5。
- 边界覆盖：不限制 LLM 工具、不修改 ToolRegistry/ToolDispatcher/AgentLoop/VehicleStateMachine 已写入 Phase 4 静态检查。
- 类型一致性：Runtime 使用 `ToolGroupSelectionResult`，selector 复用 `IntentConfidence`，RequestSession 不将 selection 写入 `orchestratorContext`。
- 构造函数一致性：已显式保留 `AgentRuntime(AgentExecutor)`、`AgentRuntime(AgentExecutor, IdGenerator, TimeProvider)`、`AgentRuntime(AgentExecutor, IntentRouter, IdGenerator, TimeProvider)`，并新增带 `ToolGroupSelector` 的全可注入构造函数。
- 已明确假设：真实 VOICE 本阶段不迁移；`riskLevel` 仅为字符串元信息；`BASIC_STATUS_GROUP` 不包含实际 toolName；`set_vehicle_spd` 第一版归入 `CHASSIS_GROUP`；ToolGroup toolName 与 `@Tool(name=...)` 需要人工同步维护。
