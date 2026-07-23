package com.hirain.aiagent.toolgroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import dev.langchain4j.agent.tool.ToolSpecification;

/**
 * 工具组注册表 — 维护 ToolGroupId 到 ToolGroup 的映射。
 * <p>
 * 负责 groupId 查询、toolName 反查和合并去重。
 * 不执行 Tool，不替代现有 ToolRegistry / ToolDispatcher。
 */
public class ToolGroupRegistry {
    private final LinkedHashMap<ToolGroupId, ToolGroup> groups;

    private ToolGroupRegistry(LinkedHashMap<ToolGroupId, ToolGroup> groups) {
        this.groups = new LinkedHashMap<>(groups);
    }

    public static ToolGroupRegistry defaultRegistry() {
        LinkedHashMap<ToolGroupId, ToolGroup> groups = new LinkedHashMap<>();

        // ── 工具名列表 ──
        List<String> acTools = List.of("set_ac_status", "set_ac_drive_temp", "set_ac_assist_temp",
                "set_ac_fan_intensity", "set_ac_eco_mode", "set_ac_anion_status",
                "set_ac_clean_mode", "set_ac_cyc_mode", "set_ac_drive_sweep_auto",
                "set_ac_assist_sweep_auto", "set_ac_drive_left_air_outlet",
                "set_ac_drive_right_air_outlet", "set_ac_assist_air_outlet_mode",
                "set_ac_assist_left_air_outlet", "set_ac_assist_right_air_outlet");

        List<String> windowTools = List.of("set_fl_window_status", "set_fr_window_status",
                "set_rl_window_status", "set_rr_window_status", "set_top_window_status",
                "set_sun_shadow_status", "set_window_f_defrosting", "set_window_r_heat",
                "set_mirror_l_heat", "set_mirror_r_heat", "set_no_window_opening_passengers");

        List<String> seatTools = List.of("set_seat_fl_heat", "set_seat_fr_heat",
                "set_seat_rl_heat", "set_seat_rr_heat", "set_seat_fl_air",
                "set_seat_fr_air", "set_seat_rl_air", "set_seat_rr_air",
                "set_seat_massage_mode", "set_seat_massage_intensity", "set_steering_heat");

        List<String> doorTools = List.of("set_door_lock");

        List<String> chassisTools = List.of("set_chassis_mode");

        List<String> fragranceTools = List.of("set_frag_type", "set_frag_intensity");

        List<String> dmsTools = List.of("set_dms_drive_fatigue", "set_dms_drive_distractionlevel",
                "set_dms_drive_emotion");

        List<String> weatherTools = List.of("getWeatherForecast");

        List<String> visionTools = List.of("front_camera_interaction");
        // 知识 Tool 不加入 ALL_SAFE_DEMO_GROUP；REQUIRED 请求必须以独占组收敛能力边界。
        List<String> knowledgeTools = List.of("searchVehicleKnowledge");

        // ── COMMON_VEHICLE_GROUP：合并全部车辆域 toolName ──
        LinkedHashSet<String> commonVehicleTools = new LinkedHashSet<>();
        commonVehicleTools.addAll(acTools);
        commonVehicleTools.addAll(windowTools);
        commonVehicleTools.addAll(seatTools);
        commonVehicleTools.addAll(doorTools);
        commonVehicleTools.addAll(chassisTools);
        commonVehicleTools.addAll(fragranceTools);
        commonVehicleTools.addAll(dmsTools);

        // ── ALL_SAFE_DEMO_GROUP：COMMON_VEHICLE + Weather + Vision ──
        LinkedHashSet<String> allSafeDemoTools = new LinkedHashSet<>(commonVehicleTools);
        allSafeDemoTools.addAll(weatherTools);
        allSafeDemoTools.addAll(visionTools);

        // ── BASIC_STATUS_GROUP ──
        List<String> basicStatusContextKeys = List.of("user_id", "vehicle_status");
        List<String> emptyTools = List.of();

        // ── 注册 ──
        groups.put(ToolGroupId.CHAT_ONLY_GROUP,
                new ToolGroup(ToolGroupId.CHAT_ONLY_GROUP, "纯聊天组", "不含任何 tool，仅 LLM 自行回复",
                        emptyTools, emptyTools, "LOW", true));

        groups.put(ToolGroupId.BASIC_STATUS_GROUP,
                new ToolGroup(ToolGroupId.BASIC_STATUS_GROUP, "基础状态组", "车辆请求所需的基础上下文",
                        emptyTools, basicStatusContextKeys, "LOW", true));

        groups.put(ToolGroupId.AC_GROUP,
                new ToolGroup(ToolGroupId.AC_GROUP, "空调工具组", "空调及温控相关控制",
                        acTools, basicStatusContextKeys, "MEDIUM", true));

        groups.put(ToolGroupId.WINDOW_GROUP,
                new ToolGroup(ToolGroupId.WINDOW_GROUP, "车窗工具组", "车窗、天窗、遮阳帘、除霜",
                        windowTools, basicStatusContextKeys, "MEDIUM", true));

        groups.put(ToolGroupId.SEAT_GROUP,
                new ToolGroup(ToolGroupId.SEAT_GROUP, "座椅工具组", "座椅加热、通风、按摩、方向盘加热",
                        seatTools, basicStatusContextKeys, "MEDIUM", true));

        groups.put(ToolGroupId.DOOR_GROUP,
                new ToolGroup(ToolGroupId.DOOR_GROUP, "车门工具组", "车门闭锁/解锁",
                        doorTools, basicStatusContextKeys, "HIGH", true));

        groups.put(ToolGroupId.CHASSIS_GROUP,
                new ToolGroup(ToolGroupId.CHASSIS_GROUP, "底盘工具组", "底盘模式、车速控制",
                        chassisTools, basicStatusContextKeys, "HIGH", true));

        groups.put(ToolGroupId.FRAGRANCE_GROUP,
                new ToolGroup(ToolGroupId.FRAGRANCE_GROUP, "香氛工具组", "香氛类型、浓度",
                        fragranceTools, basicStatusContextKeys, "LOW", true));

        groups.put(ToolGroupId.DMS_GROUP,
                new ToolGroup(ToolGroupId.DMS_GROUP, "DMS工具组", "驾驶员状态监测",
                        dmsTools, basicStatusContextKeys, "MEDIUM", true));

        groups.put(ToolGroupId.WEATHER_GROUP,
                new ToolGroup(ToolGroupId.WEATHER_GROUP, "天气工具组", "天气查询",
                        weatherTools, emptyTools, "LOW", true));

        groups.put(ToolGroupId.VISION_GROUP,
                new ToolGroup(ToolGroupId.VISION_GROUP, "视觉工具组", "前向摄像头交互",
                        visionTools, emptyTools, "LOW", true));

        groups.put(ToolGroupId.VEHICLE_KNOWLEDGE_GROUP,
                new ToolGroup(ToolGroupId.VEHICLE_KNOWLEDGE_GROUP, "车辆知识工具组", "只读车辆官方资料检索",
                        knowledgeTools, emptyTools, "LOW", true));

        // COMMON_VEHICLE_GROUP 从各车辆组动态合并。
        // 风险语义：聚合组的 riskLevel 取所含工具组的最高风险（DOOR/CHASSIS 为 HIGH，因此上浮至 HIGH）。
        groups.put(ToolGroupId.COMMON_VEHICLE_GROUP,
                new ToolGroup(ToolGroupId.COMMON_VEHICLE_GROUP, "通用车辆组", "全部车辆域工具",
                        List.copyOf(commonVehicleTools), basicStatusContextKeys, "HIGH", true));

        // ALL_SAFE_DEMO_GROUP 包含当前 demo 阶段所有 toolName。
        // 风险语义：虽然名为 SAFE，但实际包含全量门锁/底盘/车速工具，风险上浮至 HIGH。
        groups.put(ToolGroupId.ALL_SAFE_DEMO_GROUP,
                new ToolGroup(ToolGroupId.ALL_SAFE_DEMO_GROUP, "全量安全演示组", "调试/演示用全量工具",
                        List.copyOf(allSafeDemoTools), emptyTools, "HIGH", true));

        return new ToolGroupRegistry(groups);
    }

    public ToolGroup group(ToolGroupId groupId) {
        return groups.get(groupId);
    }

    public List<ToolGroup> allGroups() {
        return List.copyOf(groups.values());
    }

    /**
     * 按 toolName 反查所有包含该工具的组（含聚合组如 COMMON_VEHICLE_GROUP 和 ALL_SAFE_DEMO_GROUP）。
     * <p>
     * 语义：allContainingGroups — 返回的是"所有包含"而非"主功能域"。
     * 一个空调工具可能同时在 AC_GROUP、COMMON_VEHICLE_GROUP、ALL_SAFE_DEMO_GROUP 中。
     * 后续如需按主功能域反查，应使用独立的 primary/domainGroups 方法或约定排除聚合组前缀。
     */
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

    // ── 扩展查询接口 ──

    /**
     * 返回 registry 中所有 enabled 业务组（非聚合、非上下文标记）的唯一 toolName。
     * 不包含 disabled 组和 BASIC_STATUS_GROUP（上下文标记组，无工具）。
     */
    public List<String> allToolNames() {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (ToolGroup group : groups.values()) {
            if (!group.enabled()) continue;
            if (isAggregationGroup(group.groupId())) continue;
            if (isContextMarkerGroup(group.groupId())) continue;
            merged.addAll(group.toolNames());
        }
        return List.copyOf(merged);
    }

    /**
     * 按 groupIds 顺序合并去重 requiredContextKeys。
     * 例如 AC_GROUP + BASIC_STATUS_GROUP → ["user_id", "vehicle_status"]。
     */
    public List<String> requiredContextKeysFor(List<ToolGroupId> groupIds) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (ToolGroupId groupId : groupIds) {
            ToolGroup group = groups.get(groupId);
            if (group != null) {
                merged.addAll(group.requiredContextKeys());
            }
        }
        return List.copyOf(merged);
    }

    /**
     * 计算最高风险等级。空列表返回 LOW。顺序：LOW < MEDIUM < HIGH。
     */
    public String highestRiskLevelFor(List<ToolGroupId> groupIds) {
        int max = 0;
        for (ToolGroupId groupId : groupIds) {
            ToolGroup group = groups.get(groupId);
            if (group == null) continue;
            int level = riskLevelToInt(group.riskLevel());
            if (level > max) max = level;
        }
        return intToRiskLevel(max);
    }

    /**
     * 当 groupIds 中包含 COMMON_VEHICLE_GROUP 或 ALL_SAFE_DEMO_GROUP 时返回 true。
     */
    public boolean containsAggregationGroup(List<ToolGroupId> groupIds) {
        for (ToolGroupId groupId : groupIds) {
            if (isAggregationGroup(groupId)) return true;
        }
        return false;
    }

    /**
     * 上下文标记组：无工具但有 requiredContextKeys（如 BASIC_STATUS_GROUP）。
     */
    public boolean isContextMarkerGroup(ToolGroupId groupId) {
        ToolGroup group = groups.get(groupId);
        return group != null && group.isContextMarker();
    }

    /**
     * 聚合组：COMMON_VEHICLE_GROUP 或 ALL_SAFE_DEMO_GROUP。
     */
    public boolean isAggregationGroup(ToolGroupId groupId) {
        ToolGroup group = groups.get(groupId);
        return group != null && group.isAggregation();
    }

    // ── 内部辅助 ──

    private static int riskLevelToInt(String risk) {
        if ("HIGH".equals(risk)) return 2;
        if ("MEDIUM".equals(risk)) return 1;
        return 0; // LOW or unknown
    }

    private static String intToRiskLevel(int level) {
        if (level >= 2) return "HIGH";
        if (level >= 1) return "MEDIUM";
        return "LOW";
    }

    // ── LangChain4j 一致性校验 ──

    /**
     * 与 LangChain4j ToolSpecification 做双向一致性校验。
     * <p>
     * missing：registry 中声明了但 ToolSpecification 中不存在的 toolName。
     * ungrouped：ToolSpecification 中存在但 registry 中未覆盖的 toolName。
     * 本阶段不强制 fail fast，仅返回校验结果供测试/日志使用。
     */
    public ToolGroupRegistryValidationResult validateAgainstToolSpecifications(
            List<ToolSpecification> langchain4jSpecs) {
        Set<String> specNames = new LinkedHashSet<>();
        if (langchain4jSpecs != null) {
            for (ToolSpecification spec : langchain4jSpecs) {
                specNames.add(spec.name());
            }
        }
        Set<String> registryNames = new LinkedHashSet<>(allToolNames());

        List<String> missing = new ArrayList<>();
        for (String name : registryNames) {
            if (!specNames.contains(name)) missing.add(name);
        }
        List<String> ungrouped = new ArrayList<>();
        for (String name : specNames) {
            if (!registryNames.contains(name)) ungrouped.add(name);
        }

        if (missing.isEmpty() && ungrouped.isEmpty()) {
            return ToolGroupRegistryValidationResult.empty();
        }
        return ToolGroupRegistryValidationResult.of(missing, ungrouped);
    }
}
