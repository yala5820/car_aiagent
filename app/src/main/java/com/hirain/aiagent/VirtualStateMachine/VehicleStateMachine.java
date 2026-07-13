package com.hirain.aiagent.VirtualStateMachine;

import com.hirain.aiagent.VirtualStateMachine.state.*;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 虚拟车辆状态机 — Demo 阶段车辆 tool 的唯一状态入口。
 * <p>
 * 持有 8 个子系统状态对象，为每个 {@code @Tool} 方法提供参数校验 + 状态变更。
 * 所有方法返回 {@code String}，成功返回操作结果描述，失败返回失败原因。
 */
public class VehicleStateMachine {

    private static final Set<String> VALID_CLEAN_MODES = setOf("关闭", "标准清洁", "深度清洁");
    private static final Set<String> VALID_CYC_MODES = setOf("内循环", "外循环", "自动");
    private static final Set<String> VALID_ASSIST_OUTLET_MODES = setOf(
            "AirManual OFF", "Air Vertical", "Air Horizontal", "Air Point",
            "Mirror Wind", "One way", "AirnoVent", "AirToVent", "AirAuto", "AirOFF");
    private static final Set<String> VALID_CHASSIS_MODES = setOf("普通模式", "越野模式", "雪地模式");
    private static final Set<String> VALID_FRAG_TYPES = setOf("晨间松木", "正午丁香", "午夜橙香");
    private static final Set<String> VALID_FRAG_INTENSITIES = setOf("关闭", "低", "中", "高");
    private static final Set<String> VALID_MASSAGE_MODES = setOf("波浪", "脉冲", "揉捏", "震动", "腰部聚焦");
    private static final Set<String> VALID_MASSAGE_INTENSITIES = setOf("关闭", "弱", "中等", "强力");
    private static final Set<String> VALID_FATIGUE = setOf("清醒", "轻度", "中度", "重度");
    private static final Set<String> VALID_DISTRACTION = setOf("专注", "轻度分心", "中度分心", "重度分心");
    private static final Set<String> VALID_EMOTION = setOf("中性", "高兴", "惊讶", "悲伤", "愤怒", "厌恶", "恐惧");

    private static final int TEMP_MIN = 16;
    private static final int TEMP_MAX = 31;
    private static final int FAN_MIN = 1;
    private static final int FAN_MAX = 7;
    private static final int PERCENT_MIN = 0;
    private static final int PERCENT_MAX = 100;
    private static final int SPD_MIN = 0;
    private static final int SPD_MAX = 240;

    private final AcState ac = new AcState();
    private final DoorState door = new DoorState();
    private final WindowState window = new WindowState();
    private final SeatState seat = new SeatState();
    private final SpeedState speed = new SpeedState();
    private final ChassisState chassis = new ChassisState();
    private final FragState frag = new FragState();
    private final DmsState dms = new DmsState();

    // ── 工具方法 ──

    // ════════════════ AC ════════════════

    public String setAcStatus(boolean status) {
        ac.setAcStatus(status);
        return "空调" + (status ? "开启" : "关闭") + "成功";
    }

    public String setAcDriveTemp(int temp) {
        if (temp < TEMP_MIN || temp > TEMP_MAX) {
            return "主驾温度" + TEMP_MIN + "-" + TEMP_MAX + "摄氏度，当前值：" + temp;
        }
        ac.setAcDriveTemp(temp);
        return "主驾温度调节成功";
    }

    public String setAcAssistTemp(int temp) {
        if (temp < TEMP_MIN || temp > TEMP_MAX) {
            return "副驾温度" + TEMP_MIN + "-" + TEMP_MAX + "摄氏度，当前值：" + temp;
        }
        ac.setAcAssistTemp(temp);
        return "副驾温度调节成功";
    }

    public String setAcFanIntensity(int intensity) {
        if (intensity < FAN_MIN || intensity > FAN_MAX) {
            return "风量挡位" + FAN_MIN + "-" + FAN_MAX + "，当前值：" + intensity;
        }
        ac.setAcFanIntensity(intensity);
        return "风量挡位调节成功";
    }

    public String setAcEcoMode(boolean mode) {
        ac.setAcEcoMode(mode);
        return "空调经济模式" + (mode ? "开启" : "关闭") + "成功";
    }

    public String setAcAnionStatus(boolean status) {
        ac.setAcAnionStatus(status);
        return "负离子" + (status ? "开启" : "关闭") + "成功";
    }

    public String setAcCleanMode(String mode) {
        if (!VALID_CLEAN_MODES.contains(mode)) {
            return "无效的干燥除味模式：" + mode + "，可选：" + VALID_CLEAN_MODES;
        }
        ac.setAcCleanMode(mode);
        return "空调干燥除味模式成功设置为：" + mode;
    }

    public String setAcCycMode(String mode) {
        if (!VALID_CYC_MODES.contains(mode)) {
            return "无效的循环模式：" + mode + "，可选：" + VALID_CYC_MODES;
        }
        ac.setAcCycMode(mode);
        return "空调内外循环模式成功设置为：" + mode;
    }

    public String setAcDriveSweepAuto(boolean auto) {
        ac.setAcDriveSweepAuto(auto);
        return "主驾自动扫风功能" + (auto ? "开启" : "关闭") + "成功";
    }

    public String setAcAssistSweepAuto(boolean auto) {
        ac.setAcAssistSweepAuto(auto);
        return "副驾自动扫风功能" + (auto ? "开启" : "关闭") + "成功";
    }

    public String setAcDriveLeftAirOutlet(boolean auto) {
        ac.setAcDriveLeftAirOutlet(auto);
        return "主驾左侧出风口功能" + (auto ? "开启" : "关闭") + "成功";
    }

    public String setAcDriveRightAirOutlet(boolean auto) {
        ac.setAcDriveRightAirOutlet(auto);
        return "主驾右侧出风口功能" + (auto ? "开启" : "关闭") + "成功";
    }

    public String setAcAssistAirOutletMode(String mode) {
        if (!VALID_ASSIST_OUTLET_MODES.contains(mode)) {
            return "无效的副驾出风口模式：" + mode;
        }
        ac.setAcAssistAirOutletMode(mode);
        return "副驾电动出风口模式：" + mode;
    }

    public String setAcAssistLeftAirOutlet(boolean auto) {
        ac.setAcAssistLeftAirOutlet(auto);
        return "副驾左侧出风口功能" + (auto ? "开启" : "关闭") + "成功";
    }

    public String setAcAssistRightAirOutlet(boolean auto) {
        ac.setAcAssistRightAirOutlet(auto);
        return "副驾右侧出风口功能" + (auto ? "开启" : "关闭") + "成功";
    }

    public String getAcStatus() {
        JSONObject json = new JSONObject();
        try {
            json.put("空调开启", ac.isAcStatus());
            json.put("主驾侧温度（摄氏度）", ac.getAcDriveTemp());
            json.put("副驾侧温度（摄氏度）", ac.getAcAssistTemp());
            json.put("空调风量挡位（1-7递增）", ac.getAcFanIntensity());
            json.put("空调经济模式开启", ac.isAcEcoMode());
            json.put("负离子开启", ac.isAcAnionStatus());
            json.put("空调干燥除味模式", ac.getAcCleanMode());
            json.put("空调循环模式", ac.getAcCycMode());
            json.put("主驾自动扫风开启", ac.isAcDriveSweepAuto());
            json.put("副驾自动扫风开启", ac.isAcAssistSweepAuto());
            json.put("主驾左侧出风口开关", ac.isAcDriveLeftAirOutlet());
            json.put("主驾右侧出风口开关", ac.isAcDriveRightAirOutlet());
            json.put("副驾电动出风口模式", ac.getAcAssistAirOutletMode());
            json.put("副驾左侧出风口开关", ac.isAcAssistLeftAirOutlet());
            json.put("副驾右侧出风口开关", ac.isAcAssistRightAirOutlet());
        } catch (JSONException e) {
            return "获取空调系统状态失败。";
        }
        return json.toString();
    }

    // ════════════════ Door ════════════════

    public String setDoorLock(boolean lock) {
        if (lock && door.isAnyDoorOpen()) {
            return "车门未关闭，车门闭锁失败";
        }
        door.setDoorLocked(lock);
        return lock ? "车门闭锁成功" : "车门解锁成功";
    }

    public String getDoorStatus() {
        JSONObject json = new JSONObject();
        try {
            json.put("左前门开启", door.isDoorFlOpen());
            json.put("右前门开启", door.isDoorFrOpen());
            json.put("左后门开启", door.isDoorRlOpen());
            json.put("右后门开启", door.isDoorRrOpen());
            json.put("车门闭锁", door.isDoorLocked());
        } catch (JSONException e) {
            return "获取车门状态失败。";
        }
        return json.toString();
    }

    // ════════════════ Window ════════════════

    private String setWindowPercent(int value, String label) {
        if (value < PERCENT_MIN || value > PERCENT_MAX) {
            return label + "范围 0-100%，当前值：" + value;
        }
        return null;
    }

    public String setFlWindowStatus(int status) {
        String err = setWindowPercent(status, "左前车窗");
        if (err != null) return err;
        window.setWindowFlOpen(status);
        return "左前车窗控制成功";
    }

    public String setFrWindowStatus(int status) {
        String err = setWindowPercent(status, "右前车窗");
        if (err != null) return err;
        window.setWindowFrOpen(status);
        return "右前车窗控制成功";
    }

    public String setRlWindowStatus(int status) {
        String err = setWindowPercent(status, "左后车窗");
        if (err != null) return err;
        window.setWindowRlOpen(status);
        return "左后车窗控制成功";
    }

    public String setRrWindowStatus(int status) {
        String err = setWindowPercent(status, "右后车窗");
        if (err != null) return err;
        window.setWindowRrOpen(status);
        return "右后车窗控制成功";
    }

    public String setTopWindowStatus(int status) {
        String err = setWindowPercent(status, "天窗");
        if (err != null) return err;
        window.setWindowTopOpen(status);
        return "天窗控制成功";
    }

    public String setSunShadowStatus(int status) {
        String err = setWindowPercent(status, "遮阳帘");
        if (err != null) return err;
        window.setSunShadowOpen(status);
        return "遮阳帘控制成功";
    }

    public String setWindowFDefrosting(boolean defrosting) {
        window.setWindowFDefrosting(defrosting);
        return "前风挡除霜" + (defrosting ? "开启" : "关闭") + "成功";
    }

    public String setWindowRHeat(boolean heat) {
        window.setWindowRHeat(heat);
        return "后风挡加热" + (heat ? "开启" : "关闭") + "成功";
    }

    public String setMirrorLHeat(boolean heat) {
        window.setMirrorLHeat(heat);
        return "左后视镜加热" + (heat ? "开启" : "关闭") + "成功";
    }

    public String setMirrorRHeat(boolean heat) {
        window.setMirrorRHeat(heat);
        return "右后视镜加热" + (heat ? "开启" : "关闭") + "成功";
    }

    public String setNoWindowOpeningPassengers(boolean open) {
        window.setNoWindowOpeningPassengers(open);
        return "乘员禁止开窗功能" + (open ? "开启" : "关闭") + "成功";
    }

    public String getWindowStatus() {
        JSONObject json = new JSONObject();
        try {
            json.put("左前车窗开度百分比", window.getWindowFlOpen());
            json.put("右前车窗开度百分比", window.getWindowFrOpen());
            json.put("左后车窗开度百分比", window.getWindowRlOpen());
            json.put("右后车窗开度百分比", window.getWindowRrOpen());
            json.put("天窗开度百分比", window.getWindowTopOpen());
            json.put("遮阳帘开度百分比", window.getSunShadowOpen());
            json.put("前风挡除霜开启", window.isWindowFDefrosting());
            json.put("后风挡加热开启", window.isWindowRHeat());
            json.put("左后视镜加热开启", window.isMirrorLHeat());
            json.put("右后视镜加热开启", window.isMirrorRHeat());
            json.put("乘员禁止开窗功能", window.isNoWindowOpeningPassengers());
        } catch (JSONException e) {
            return "获取车窗、天窗、遮阳帘开度状态失败。";
        }
        return json.toString();
    }

    // ════════════════ Seat ════════════════

    public String setSeatFlHeat(boolean heat) {
        seat.setSeatFlHeat(heat);
        return "左前座椅加热" + (heat ? "开启" : "关闭") + "成功";
    }

    public String setSeatFrHeat(boolean heat) {
        seat.setSeatFrHeat(heat);
        return "右前座椅加热" + (heat ? "开启" : "关闭") + "成功";
    }

    public String setSeatRlHeat(boolean heat) {
        seat.setSeatRlHeat(heat);
        return "左后座椅加热" + (heat ? "开启" : "关闭") + "成功";
    }

    public String setSeatRrHeat(boolean heat) {
        seat.setSeatRrHeat(heat);
        return "右后座椅加热" + (heat ? "开启" : "关闭") + "成功";
    }

    public String setSeatFlAir(int air) {
        String err = setWindowPercent(air, "左前座椅通风");
        if (err != null) return err;
        seat.setSeatFlAir(air);
        return "左前座椅通风控制成功";
    }

    public String setSeatFrAir(int air) {
        String err = setWindowPercent(air, "右前座椅通风");
        if (err != null) return err;
        seat.setSeatFrAir(air);
        return "右前座椅通风控制成功";
    }

    public String setSeatRlAir(int air) {
        String err = setWindowPercent(air, "左后座椅通风");
        if (err != null) return err;
        seat.setSeatRlAir(air);
        return "左后座椅通风控制成功";
    }

    public String setSeatRrAir(int air) {
        String err = setWindowPercent(air, "右后座椅通风");
        if (err != null) return err;
        seat.setSeatRrAir(air);
        return "右后座椅通风控制成功";
    }

    public String setSeatMassageMode(String mode) {
        if (!VALID_MASSAGE_MODES.contains(mode)) {
            return "无效的按摩模式：" + mode + "，可选：" + VALID_MASSAGE_MODES;
        }
        seat.setSeatMassageMode(mode);
        return "主驾座椅按摩模式成功设置为：" + mode;
    }

    public String setSeatMassageIntensity(String intensity) {
        if (!VALID_MASSAGE_INTENSITIES.contains(intensity)) {
            return "无效的按摩强度：" + intensity + "，可选：" + VALID_MASSAGE_INTENSITIES;
        }
        seat.setSeatMassageIntensity(intensity);
        return "主驾座椅按摩强度成功设置为：" + intensity;
    }

    public String setSteeringHeat(boolean heat) {
        seat.setSteeringHeat(heat);
        return "方向盘加热" + (heat ? "开启" : "关闭") + "成功";
    }

    public String getSeatStatus() {
        JSONObject json = new JSONObject();
        try {
            json.put("左前座椅加热开启", seat.isSeatFlHeat());
            json.put("右前座椅加热开启", seat.isSeatFrHeat());
            json.put("左后座椅加热开启", seat.isSeatRlHeat());
            json.put("右后座椅加热开启", seat.isSeatRrHeat());
            json.put("左前座椅通风百分比", seat.getSeatFlAir());
            json.put("右前座椅通风百分比", seat.getSeatFrAir());
            json.put("左后座椅通风百分比", seat.getSeatRlAir());
            json.put("右后座椅通风百分比", seat.getSeatRrAir());
            json.put("主驾座椅按摩模式", seat.getSeatMassageMode());
            json.put("主驾座椅按摩强度", seat.getSeatMassageIntensity());
            json.put("方向盘加热开启", seat.isSteeringHeat());
        } catch (JSONException e) {
            return "获取座椅、方向盘状态失败。";
        }
        return json.toString();
    }

    // ════════════════ Speed ════════════════

    public String setVehicleSpd(int spd) {
        if (spd < SPD_MIN || spd > SPD_MAX) {
            return "车速范围" + SPD_MIN + "-" + SPD_MAX + " km/h，当前值：" + spd;
        }
        speed.setVehicleSpd(spd);
        return "车速大小调节成功";
    }

    /**
     * 读取当前车速，供执行前安全规则进行确定性判断。
     * 与 getSpeedStatus() 的展示型 JSON 不同，此方法直接返回类型明确的状态值，
     * 避免安全判断依赖中文 JSON 字段和字符串解析。
     */
    public int getVehicleSpd() {
        return speed.getVehicleSpd();
    }

    public String getSpeedStatus() {
        JSONObject json = new JSONObject();
        try {
            json.put("车速", speed.getVehicleSpd());
        } catch (JSONException e) {
            return "获取速度系统状态失败。";
        }
        return json.toString();
    }

    // ════════════════ Chassis ════════════════

    public String setChassisMode(String mode) {
        if (!VALID_CHASSIS_MODES.contains(mode)) {
            return "无效的底盘行驶模式：" + mode + "，可选：" + VALID_CHASSIS_MODES;
        }
        chassis.setChassisMode(mode);
        return "底盘行驶模式成功设置为：" + mode;
    }

    public String getChassisStatus() {
        JSONObject json = new JSONObject();
        try {
            json.put("底盘行驶模式", chassis.getChassisMode());
        } catch (JSONException e) {
            return "获取底盘系统状态失败。";
        }
        return json.toString();
    }

    // ════════════════ Frag ════════════════

    public String setFragType(String type) {
        if (!VALID_FRAG_TYPES.contains(type)) {
            return "无效的香氛类型：" + type + "，可选：" + VALID_FRAG_TYPES;
        }
        frag.setFragType(type);
        return "车载香氛类型成功设置为：" + type;
    }

    public String setFragIntensity(String intensity) {
        if (!VALID_FRAG_INTENSITIES.contains(intensity)) {
            return "无效的香氛强度：" + intensity + "，可选：" + VALID_FRAG_INTENSITIES;
        }
        frag.setFragIntensity(intensity);
        return "车载香氛强度成功设置为：" + intensity;
    }

    public String getFragStatus() {
        JSONObject json = new JSONObject();
        try {
            json.put("车载香氛类型", frag.getFragType());
            json.put("车载香氛浓度", frag.getFragIntensity());
        } catch (JSONException e) {
            return "获取香氛系统状态失败。";
        }
        return json.toString();
    }

    // ════════════════ DMS ════════════════

    public String setDmsDriveFatigue(String type) {
        if (!VALID_FATIGUE.contains(type)) {
            return "无效的疲劳等级：" + type + "，可选：" + VALID_FATIGUE;
        }
        dms.setDmsDriveFatigue(type);
        return "驾驶员疲劳等级成功设置为：" + type;
    }

    public String setDmsDriveDistractionLevel(String type) {
        if (!VALID_DISTRACTION.contains(type)) {
            return "无效的分心等级：" + type + "，可选：" + VALID_DISTRACTION;
        }
        dms.setDmsDriveDistractionLevel(type);
        return "驾驶员分心等级成功设置为：" + type;
    }

    public String setDmsDriveEmotion(String type) {
        if (!VALID_EMOTION.contains(type)) {
            return "无效的情绪：" + type + "，可选：" + VALID_EMOTION;
        }
        dms.setDmsDriveEmotion(type);
        return "驾驶员情绪成功设置为：" + type;
    }

    public String getDmsStatus() {
        JSONObject json = new JSONObject();
        try {
            json.put("驾驶员疲劳等级", dms.getDmsDriveFatigue());
            json.put("驾驶员分心等级", dms.getDmsDriveDistractionLevel());
            json.put("驾驶员情绪", dms.getDmsDriveEmotion());
        } catch (JSONException e) {
            return "获取DMS系统状态失败。";
        }
        return json.toString();
    }

    // ── 辅助 ──

    private static Set<String> setOf(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }
}
