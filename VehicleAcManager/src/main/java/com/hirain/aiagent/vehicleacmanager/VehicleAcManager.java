package com.hirain.aiagent.vehicleacmanager;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import org.json.JSONException;
import org.json.JSONObject;
public class VehicleAcManager {
    private static final String KEY_AC_STATUS = "空调开启";
    private static final String KEY_DRIVER_TEMPERATURE = "主驾侧温度（摄氏度）";
    private static final String KEY_ASSIST_TEMPERATURE = "副驾侧温度（摄氏度）";
    private static final String KEY_FAN_INTENSITY = "空调风量挡位（1-7递增）";
    private static final String KEY_ECO_MODE = "空调经济模式开启";
    private static final String KEY_ANION_STATUS = "负离子开启";
    private static final String KEY_CLEAN_MODE = "空调干燥除味模式";
    private static final String KEY_CIRCULATION_MODE = "空调循环模式";
    private static final String KEY_DRIVE_AUTO_SWEEP = "主驾自动扫风开启";
    private static final String KEY_ASSIST_AUTO_SWEEP = "副驾自动扫风开启";
    private static final String KEY_DRIVE_LEFT_AIR_OUTLET = "主驾左侧出风口开关";
    private static final String KEY_DRIVE_RIGHT_AIR_OUTLET = "主驾右侧出风口开关";
    private static final String KEY_ASSIST_AIR_OUTLET_MODE = "副驾电动出风口模式";
    private static final String KEY_ASSIST_LEFT_AIR_OUTLET = "副驾左侧出风口开关";
    private static final String KEY_ASSIST_RIGHT_AIR_OUTLET = "副驾右侧出风口开关";
    private boolean ac_status;
    private int ac_drive_temp;
    private int ac_assist_temp;
    private int ac_fan_intensity;
    private boolean ac_eco_mode;
    private boolean ac_anion_status;
    private String ac_clean_mode;
    private String ac_cyc_mode;
    private boolean ac_drive_sweep_auto;
    private boolean ac_assist_sweep_auto;
    private boolean ac_drive_left_air_outlet;
    private boolean ac_drive_right_air_outlet;
    private String ac_assist_air_outlet_mode;
    private boolean ac_assist_left_air_outlet;
    private boolean ac_assist_right_air_outlet;

    public VehicleAcManager() {
        this.ac_status = false;
        this.ac_drive_temp = 26;
        this.ac_assist_temp = 26;
        this.ac_fan_intensity = 1;
        this.ac_eco_mode = false;
        this.ac_anion_status = false;
        this.ac_clean_mode = "关闭";
        this.ac_cyc_mode = "自动";
        this.ac_drive_sweep_auto = false;
        this.ac_assist_sweep_auto = false;
        this.ac_drive_left_air_outlet = false;
        this.ac_drive_right_air_outlet = false;
        this.ac_assist_air_outlet_mode = "关闭";
        this.ac_assist_left_air_outlet = false;
        this.ac_assist_right_air_outlet = false;
    }

    public String getAcStatus() {
        JSONObject json = new JSONObject();
        try {
            json.put(KEY_AC_STATUS, ac_status);
            json.put(KEY_DRIVER_TEMPERATURE, ac_drive_temp);
            json.put(KEY_ASSIST_TEMPERATURE, ac_assist_temp);
            json.put(KEY_FAN_INTENSITY, ac_fan_intensity);
            json.put(KEY_ECO_MODE, ac_eco_mode);
            json.put(KEY_ANION_STATUS, ac_anion_status);
            json.put(KEY_CLEAN_MODE, ac_clean_mode);
            json.put(KEY_CIRCULATION_MODE, ac_cyc_mode);
            json.put(KEY_DRIVE_AUTO_SWEEP, ac_drive_sweep_auto);
            json.put(KEY_ASSIST_AUTO_SWEEP, ac_assist_sweep_auto);
            json.put(KEY_DRIVE_LEFT_AIR_OUTLET, ac_drive_left_air_outlet);
            json.put(KEY_DRIVE_RIGHT_AIR_OUTLET, ac_drive_right_air_outlet);
            json.put(KEY_ASSIST_AIR_OUTLET_MODE, ac_assist_air_outlet_mode);
            json.put(KEY_ASSIST_LEFT_AIR_OUTLET, ac_assist_left_air_outlet);
            json.put(KEY_ASSIST_RIGHT_AIR_OUTLET, ac_assist_right_air_outlet);
        } catch (JSONException e) {
            return "获取空调系统状态失败。";
        }
        return json.toString();
    }
    @Tool("控制空调开启/关闭。")
    public String set_ac_status(@P(value = "开启：true, 关闭：false") boolean status) {
        this.ac_status = status;
        String tmp = status ? "开启": "关闭";
        return "空调" + tmp + "成功";
    }
    @Tool("调节主驾空调温度。")
    public String set_ac_drive_temp(@P(value = "温度（摄氏度），整数，范围：16-31") int temp) {
        this.ac_drive_temp = temp;
        return "主驾温度调节成功";
    }
    @Tool("调节副驾空调温度。")
    public String set_ac_assist_temp(@P(value = "温度（摄氏度），整数，范围：16-31") int temp) {
        this.ac_assist_temp = temp;
        return "副驾温度调节成功";
    }
    @Tool("调节空调风量挡位。")
    public String set_fan_intensity(@P(value = "整数，范围：1-7,风量递增") int intensity) {
        this.ac_fan_intensity = intensity;
        return "风量挡位调节成功";
    }
    @Tool("控制空调经济模式开启/关闭。")
    public String set_ac_eco_mode(@P(value = "开启：true, 关闭：false") boolean mode) {
        this.ac_eco_mode = mode;
        String tmp = mode ? "开启": "关闭";
        return "空调经济模式" + tmp + "成功";
    }
    @Tool("控制负离子开启/关闭。")
    public String set_ac_anion_status(@P(value = "开启：true, 关闭：false") boolean status) {
        this.ac_anion_status = status;
        String tmp = status ? "开启": "关闭";
        return "负离子" + tmp + "成功";
    }
    @Tool("控制空调干燥除味模式。")
    public String set_ac_clean_mode(@P(value = "模式，必须为：‘关闭’、‘标准清洁’、‘深度清洁’中的一个。") String mode) {
        this.ac_clean_mode = mode;
        return "空调干燥除味模式成功设置为：" + mode;
    }
    @Tool("控制空调内外循环模式。")
    public String set_ac_cyc_mode(@P(value = "模式，必须为：‘内循环’、‘外循环’、‘自动’中的一个。") String mode) {
        this.ac_cyc_mode = mode;
        return "空调内外循环模式成功设置为：" + mode;
    }
    @Tool("控制主驾自动扫风功能开启/关闭。")
    public String set_ac_drive_sweep_auto(@P(value = "开启：true, 关闭：false") boolean auto) {
        this.ac_drive_sweep_auto = auto;
        String tmp = auto ? "开启": "关闭";
        return "主驾自动扫风功能" + tmp + "成功";
    }
    @Tool("控制副驾自动扫风功能开启/关闭。")
    public String set_ac_assist_sweep_auto(@P(value = "开启：true, 关闭：false") boolean auto) {
        this.ac_assist_sweep_auto = auto;
        String tmp = auto ? "开启": "关闭";
        return "副驾自动扫风功能" + tmp + "成功";
    }

    @Tool("主驾左侧出风口功能开启/关闭。")
    public String set_ac_drive_left_air_outlet(@P(value = "开启：true, 关闭：false") boolean auto) {
        this.ac_drive_left_air_outlet = auto;
        String tmp = auto ? "开启": "关闭";
        return "主驾左侧出风口功能" + tmp + "成功";
    }

    @Tool("主驾右侧出风口开关功能开启/关闭。")
    public String set_ac_drive_right_air_outlet(@P(value = "开启：true, 关闭：false") boolean auto) {
        this.ac_drive_right_air_outlet = auto;
        String tmp = auto ? "开启": "关闭";
        return "副驾自动扫风功能" + tmp + "成功";
    }

    @Tool("副驾电动出风口模式。")
    public String set_ac_assist_air_outlet_mode(@P(value = "模式，必须为：'AirManual OFF', 'Air Vertical', 'Air Horizontal', 'Air Point', 'Mirror Wind', 'One way', 'AirnoVent', 'AirToVent', 'AirAuto', 'AirOFF' 中的一个。") String mode) {
        this.ac_assist_air_outlet_mode = mode;
        return "副驾电动出风口模式：" + mode;
    }

    @Tool("副驾左侧出风口功能开启/关闭。")
    public String set_ac_assist_left_air_outlet(@P(value = "开启：true, 关闭：false") boolean auto) {
        this.ac_assist_left_air_outlet = auto;
        String tmp = auto ? "开启": "关闭";
        return "副驾左侧出风口功能" + tmp + "成功";
    }

    @Tool("副驾右侧出风口功能开启/关闭。")
    public String set_ac_assist_right_air_outlet(@P(value = "开启：true, 关闭：false") boolean auto) {
        this.ac_assist_right_air_outlet = auto;
        String tmp = auto ? "开启": "关闭";
        return "副驾右侧出风口功能" + tmp + "成功";
    }


    public boolean hasTool(String toolname) {
        return toolname.equals("set_ac_status") || toolname.equals("set_ac_drive_temp")
            || toolname.equals("set_ac_assist_temp") || toolname.equals("set_fan_intensity")
            || toolname.equals("set_ac_eco_mode") || toolname.equals("set_ac_anion_status")
            || toolname.equals("set_ac_clean_mode") || toolname.equals("set_ac_cyc_mode")
            || toolname.equals("set_ac_drive_sweep_auto") || toolname.equals("set_ac_assist_sweep_auto")
            || toolname.equals("set_ac_drive_left_air_outlet") || toolname.equals("set_ac_drive_right_air_outlet")
            || toolname.equals("set_ac_assist_air_outlet_mode") || toolname.equals("set_ac_assist_left_air_outlet")
            || toolname.equals("set_ac_assist_right_air_outlet");
    }
    public String handleToolRequest(ToolExecutionRequest request) {
        try {
            JSONObject json = new JSONObject(request.arguments());
            if (request.name().equals("set_ac_status")) {
                return set_ac_status(json.getBoolean("arg0"));
            } else if (request.name().equals("set_ac_drive_temp")) {
                return set_ac_drive_temp(json.getInt("arg0"));
            } else if (request.name().equals("set_ac_assist_temp")) {
                return set_ac_assist_temp(json.getInt("arg0"));
            } else if (request.name().equals("set_fan_intensity")) {
                return set_fan_intensity(json.getInt("arg0"));
            } else if (request.name().equals("set_ac_eco_mode")) {
                return set_ac_eco_mode(json.getBoolean("arg0"));
            } else if (request.name().equals("set_ac_anion_status")) {
                return set_ac_anion_status(json.getBoolean("arg0"));
            } else if (request.name().equals("set_ac_clean_mode")) {
                return set_ac_clean_mode(json.getString("arg0"));
            } else if (request.name().equals("set_ac_cyc_mode")) {
                return set_ac_cyc_mode(json.getString("arg0"));
            } else if (request.name().equals("set_ac_drive_sweep_auto")) {
                return set_ac_drive_sweep_auto(json.getBoolean("arg0"));
            } else if (request.name().equals("set_ac_assist_sweep_auto")) {
                return set_ac_assist_sweep_auto(json.getBoolean("arg0"));
            } else if (request.name().equals("set_ac_drive_left_air_outlet")) {
                return set_ac_drive_left_air_outlet(json.getBoolean("arg0"));
            } else if (request.name().equals("set_ac_drive_right_air_outlet")) {
                return set_ac_drive_right_air_outlet(json.getBoolean("arg0"));
            } else if (request.name().equals("set_ac_assist_air_outlet_mode")) {
                return set_ac_assist_air_outlet_mode(json.getString("arg0"));
            } else if (request.name().equals("set_ac_assist_left_air_outlet")) {
                return set_ac_assist_left_air_outlet(json.getBoolean("arg0"));
            } else if (request.name().equals("set_ac_assist_right_air_outlet")) {
                return set_ac_assist_right_air_outlet(json.getBoolean("arg0"));
            } else {
                return "无效的工具请求。";
            }
        } catch (JSONException e) {
            return "无效的工具请求。";
        }
    }

}
