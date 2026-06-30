package com.hirain.aiagent.core.safety;

import com.hirain.aiagent.core.AgentLoopContext;
import com.hirain.aiagent.core.SafetyVerdict;
import com.hirain.aiagent.core.component.SafetyGuard;

import org.json.JSONObject;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

/**
 * 车速安全审查 — 当车速超过 5 km/h 时阻止车门解锁。
 */
public class SpeedBasedDoorLockGuard implements SafetyGuard {

    private static final int MAX_UNLOCK_SPEED_KMH = 5;
    private static final String DOOR_LOCK_TOOL = "set_door_lock";

    private final SpeedProvider speedProvider;

    public SpeedBasedDoorLockGuard(SpeedProvider speedProvider) {
        this.speedProvider = speedProvider;
    }

    @Override
    public SafetyVerdict evaluate(ToolExecutionRequest request, AgentLoopContext ctx) {
        if (!DOOR_LOCK_TOOL.equals(request.name())) {
            return SafetyVerdict.allow();
        }

        try {
            JSONObject args = new JSONObject(request.arguments());
            boolean isLock = args.optBoolean("arg0", true);
            if (isLock) {
                return SafetyVerdict.allow(); // 锁门始终安全
            }

            int speed = speedProvider.getCurrentSpeedKmh();
            if (speed > MAX_UNLOCK_SPEED_KMH) {
                return SafetyVerdict.veto(String.format(
                        "出于安全考虑，车速 %d km/h 时不允许解锁车门。请先停车。", speed));
            }
        } catch (Exception ignored) {
            // 参数解析失败时放行，避免阻塞正常工具调用
        }
        return SafetyVerdict.allow();
    }

    @FunctionalInterface
    public interface SpeedProvider {
        int getCurrentSpeedKmh();
    }
}
