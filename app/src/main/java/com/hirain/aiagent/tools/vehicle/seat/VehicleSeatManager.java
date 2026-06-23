package com.hirain.aiagent.tools.vehicle.seat;
import org.json.JSONException;
import org.json.JSONObject;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import com.hirain.aiagent.infra.soa.SoaService;

public class VehicleSeatManager {
    private static final String KEY_SEAT_FL_HEAT = "左前座椅加热开启";
    private static final String KEY_SEAT_FR_HEAT = "右前座椅加热开启";
    private static final String KEY_SEAT_RL_HEAT = "左后座椅加热开启";
    private static final String KEY_SEAT_RR_HEAT = "右后座椅加热开启";
    private static final String KEY_SEAT_FL_AIR = "左前座椅通风百分比";
    private static final String KEY_SEAT_FR_AIR = "右前座椅通风百分比";
    private static final String KEY_SEAT_RL_AIR = "左后座椅通风百分比";
    private static final String KEY_SEAT_RR_AIR = "右后座椅通风百分比";
    private static final String KEY_SEAT_DRIVE_MASSAGE_MODE = "主驾座椅按摩模式";
    private static final String KEY_SEAT_DRIVE_MASSAGE_INTENSITY = "主驾座椅按摩强度";
    private static final String KEY_STEERING_HEAT = "方向盘加热开启";
    private boolean formalfunc = false;
    private boolean seat_fl_heat;
    private boolean seat_fr_heat;
    private boolean seat_rl_heat;
    private boolean seat_rr_heat;
    private int seat_fl_air;
    private int seat_fr_air;
    private int seat_rl_air;
    private int seat_rr_air;
    private String seat_massage_mode;
    private String seat_massage_intensity;
    private boolean steering_heat;
    public VehicleSeatManager() {
        this.seat_fl_heat = false;
        this.seat_fr_heat = false;
        this.seat_rl_heat = false;
        this.seat_rr_heat = false;
        this.seat_fl_air = 0;
        this.seat_fr_air = 0;
        this.seat_rl_air = 0;
        this.seat_rr_air = 0;
        this.seat_massage_mode = "波浪";
        this.seat_massage_intensity = "关闭";
        this.steering_heat = false;
    }
    public String getSeatStatus() {
        SoaService.Companion.getInstance().getSeatStatus();

        JSONObject json = new JSONObject();
        try {
            json.put(KEY_SEAT_FL_HEAT, seat_fl_heat);
            json.put(KEY_SEAT_FR_HEAT, seat_fr_heat);
            json.put(KEY_SEAT_RL_HEAT, seat_rl_heat);
            json.put(KEY_SEAT_RR_HEAT, seat_rr_heat);
            json.put(KEY_SEAT_FL_AIR, seat_fl_air);
            json.put(KEY_SEAT_FR_AIR, seat_fr_air);
            json.put(KEY_SEAT_RL_AIR, seat_rl_air);
            json.put(KEY_SEAT_RR_AIR, seat_rr_air);
            json.put(KEY_SEAT_DRIVE_MASSAGE_MODE, seat_massage_mode);
            json.put(KEY_SEAT_DRIVE_MASSAGE_INTENSITY, seat_massage_intensity);
            json.put(KEY_STEERING_HEAT, steering_heat);
        } catch (JSONException e) {
            return "获取座椅、方向盘状态失败。";
        }
        return json.toString();
    }
    @Tool("控制左前座椅加热开启/关闭。")
    public String set_seat_fl_heat(@P(value = "开启：true, 关闭：false") boolean heat) {
        SoaService.Companion.getInstance().set_seat_fl_heat(heat);

        if (formalfunc) this.seat_fl_heat = heat;
        String tmp = heat ? "开启": "关闭";
        return "左前座椅加热" + tmp + "成功";
    }
    @Tool("控制右前座椅加热开启/关闭。")
    public String set_seat_fr_heat(@P(value = "开启：true, 关闭：false") boolean heat) {
        SoaService.Companion.getInstance().set_seat_fr_heat(heat);

        if (formalfunc) this.seat_fr_heat = heat;
        String tmp = heat ? "开启": "关闭";
        return "右前座椅加热" + tmp + "成功";
    }
    @Tool("控制左后座椅加热开启/关闭。")
    public String set_seat_rl_heat(@P(value = "开启：true, 关闭：false") boolean heat) {
        SoaService.Companion.getInstance().set_seat_rl_heat(heat);

        if (formalfunc) this.seat_rl_heat = heat;
        String tmp = heat ? "开启": "关闭";
        return "左后座椅加热" + tmp + "成功";
    }
    @Tool("控制右后座椅加热开启/关闭。")
    public String set_seat_rr_heat(@P(value = "开启：true, 关闭：false") boolean heat) {
        SoaService.Companion.getInstance().set_seat_rr_heat(heat);

        if (formalfunc) this.seat_rr_heat = heat;
        String tmp = heat ? "开启": "关闭";
        return "右后座椅加热" + tmp + "成功";
    }

    @Tool("控制左前座椅通风百分比。")
    public String set_seat_fl_air(@P(value = "通风百分比") int air) {
        SoaService.Companion.getInstance().set_seat_fl_air(air);

        if (formalfunc) this.seat_fl_air = air;
        return "左前座椅通风控制成功";
    }
    @Tool("控制右前座椅通风百分比。")
    public String set_seat_fr_air(@P(value = "通风百分比") int air) {
        SoaService.Companion.getInstance().set_seat_fr_air(air);
        if (formalfunc) this.seat_fr_air = air;
        return "右前座椅通风控制成功";
    }
    @Tool("控制左后座椅通风百分比。")
    public String set_seat_rl_air(@P(value = "通风百分比") int air) {
        SoaService.Companion.getInstance().set_seat_rl_air(air);

        if (formalfunc) this.seat_rl_air = air;
        return "左后座椅通风控制成功";
    }
    @Tool("控制右后座椅通风百分比。")
    public String set_seat_rr_air(@P(value = "通风百分比") int air) {
        SoaService.Companion.getInstance().set_seat_rr_air(air);

        if (formalfunc) this.seat_rr_air = air;
        return "右后座椅通风控制成功";
    }
    @Tool("控制主驾座椅按摩模式。")
    public String set_seat_massage_mode(@P(value = "按摩模式，必须为：‘波浪’、‘脉冲’、‘揉捏’、‘震动’、‘腰部聚焦’中的一个。") String mode) {
        SoaService.Companion.getInstance().set_seat_massage_mode(mode);

        if (formalfunc)  this.seat_massage_mode = mode;
        return "主驾座椅按摩模式成功设置为：" + mode;
    }
    @Tool("控制主驾座椅按摩强度。")
    public String set_seat_massage_intensity(@P(value = "按摩强度，必须为：‘关闭’、‘弱’、‘中等’、‘强力’中的一个。") String intensity) {
        SoaService.Companion.getInstance().set_seat_massage_intensity(intensity);

        if (formalfunc) this.seat_massage_intensity = intensity;
        return "主驾座椅按摩强度成功设置为：" + intensity;
    }
    @Tool("控制方向盘加热开启/关闭。")
    public String set_steering_heat(@P(value = "开启：true, 关闭：false") boolean heat) {
        SoaService.Companion.getInstance().set_steering_heat(heat);

        if (formalfunc) this.steering_heat = heat;
        String tmp = heat ? "开启": "关闭";
        return "方向盘加热" + tmp + "成功";
    }
    public boolean hasTool(String toolname) {
        return toolname.equals("set_seat_fl_heat") || toolname.equals("set_seat_fr_heat")
            || toolname.equals("set_seat_rl_heat") || toolname.equals("set_seat_rr_heat")
            || toolname.equals("set_seat_fl_air") || toolname.equals("set_seat_fr_air")
            || toolname.equals("set_seat_rl_air") || toolname.equals("set_seat_rr_air")
            || toolname.equals("set_seat_massage_mode") || toolname.equals("set_seat_massage_intensity")
            || toolname.equals("set_steering_heat");
    }
    public String handleToolRequest(ToolExecutionRequest request) {
        try {
            JSONObject json = new JSONObject(request.arguments());
            if (request.name().equals("set_seat_fl_heat")) {
                return set_seat_fl_heat(json.getBoolean("arg0"));
            } else if (request.name().equals("set_seat_fr_heat")) {
                return set_seat_fr_heat(json.getBoolean("arg0"));
            } else if (request.name().equals("set_seat_rl_heat")) {
                return set_seat_rl_heat(json.getBoolean("arg0"));
            } else if (request.name().equals("set_seat_rr_heat")) {
                return set_seat_rr_heat(json.getBoolean("arg0"));
            } else if (request.name().equals("set_seat_fl_air")) {
                return set_seat_fl_air(json.getInt("arg0"));
            } else if (request.name().equals("set_seat_fr_air")) {
                return set_seat_fr_air(json.getInt("arg0"));
            } else if (request.name().equals("set_seat_rl_air")) {
                return set_seat_rl_air(json.getInt("arg0"));
            } else if (request.name().equals("set_seat_rr_air")) {
                return set_seat_rr_air(json.getInt("arg0"));
            } else if (request.name().equals("set_seat_massage_mode")) {
                return set_seat_massage_mode(json.getString("arg0"));
            } else if (request.name().equals("set_seat_massage_intensity")) {
                return set_seat_massage_intensity(json.getString("arg0"));
            } else if (request.name().equals("set_steering_heat")) {
                return set_steering_heat(json.getBoolean("arg0"));
            } else {
                return "无效的工具请求。";
            }
        } catch (JSONException e) {
            return "无效的工具请求。";
        }
    }
}
