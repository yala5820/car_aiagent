package com.hirain.aiagent.core.preprocessor;

import com.hirain.aiagent.core.AgentLoopContext;
import com.hirain.aiagent.core.component.PreProcessor;
import com.hirain.aiagent.prompt.PromptConstants;
import com.hirain.aiagent.prompt.PromptManager;

import java.util.List;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

/**
 * 注入车辆状态作为当轮 LLM 调用的临时上下文。
 */
public class VehicleStatusPreProcessor implements PreProcessor {

    private final PromptManager promptManager;
    private final VehicleStatusProvider statusProvider;

    public VehicleStatusPreProcessor(PromptManager promptManager, VehicleStatusProvider statusProvider) {
        this.promptManager = promptManager;
        this.statusProvider = statusProvider;
    }

    @Override
    public List<ChatMessage> prepare(AgentLoopContext ctx) {
        String status = statusProvider.getVehicleStatus();
        String rendered = promptManager.render(PromptConstants.USER_VEHICLE_STATUS,
                java.util.Map.of("vehicle_status", status));
        return List.of(UserMessage.from(rendered));
    }

    /** 车辆状态提供者 — 从各个 Manager 采集状态 */
    @FunctionalInterface
    public interface VehicleStatusProvider {
        String getVehicleStatus();
    }
}
