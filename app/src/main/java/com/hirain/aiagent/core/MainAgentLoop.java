package com.hirain.aiagent.core;

import android.content.Context;
import android.util.Log;

import com.hirain.aiagent.BuildConfig;

import com.hirain.aiagent.ai.langchain4j.tool.ToolRegistry;
import com.hirain.aiagent.prompt.PromptConstants;
import com.hirain.aiagent.prompt.PromptManager;
import com.hirain.aiagent.tools.external.weather.WeatherUtils;
import com.hirain.aiagent.tools.vehicle.ac.VehicleAcManager;
import com.hirain.aiagent.tools.vehicle.chassis.VehicleChassisManager;
import com.hirain.aiagent.tools.vehicle.dms.VehicleDMSManager;
import com.hirain.aiagent.tools.vehicle.door.VehicleDoorManager;
import com.hirain.aiagent.tools.vehicle.frag.VehicleFragManager;
import com.hirain.aiagent.tools.vehicle.seat.VehicleSeatManager;
import com.hirain.aiagent.tools.vehicle.speed.VehicleSpeedManager;
import com.hirain.aiagent.tools.vehicle.window.VehicleWindowManager;
import com.hirain.aiagent.tools.vision.vl.VlManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import langchain4j.chat_memory_sqlite.PersistentChatMemorySqlite;
import langchain4j.http_client_ok.OkHttpClient;
import langchain4j.http_client_ok.OkHttpClientBuilder;

/**
 * 主 Agent 循环 — 迭代式 Tool Calling Loop
 *
 * 流程：
 *   chat(userMessage)
 *     → 加入记忆
 *     → executeLoop()
 *        每次迭代：注入车辆状态 → 调用 LLM（含工具声明）
 *        ├─ 有 ToolExecutionRequest → 执行工具 → 结果回填 → 下一轮迭代
 *        └─ 无 → 返回最终文本
 */
public class MainAgentLoop {

    private static final String TAG = "MainAgentLoop";
    private static final int MAX_ITERATIONS = 10;
    private static final int MAX_MESSAGES = 50;

    private final ChatModel model;
    private final ChatMemory chatMemory;
    private final String systemPrompt;

    private final ToolRegistry toolRegistry = new ToolRegistry();
    private final List<ToolSpecification> toolSpecifications;
    private final PromptManager promptManager;

    private final WeatherUtils weatherUtils;
    private final VehicleDoorManager doorManager;
    private final VehicleWindowManager windowManager;
    private final VehicleSeatManager seatManager;
    private final VehicleAcManager acManager;
    private final VehicleChassisManager chassisManager;
    private final VehicleFragManager fragManager;
    private final VehicleSpeedManager speedManager;
    private final VehicleDMSManager dmsManager;
    private final VlManager vlManager;

    public MainAgentLoop(Context context, PromptManager promptManager) {
        // ── 模型初始化 ──
        OkHttpClientBuilder httpBuilder = OkHttpClient.builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(120));
        model = OpenAiChatModel.builder()
                .httpClientBuilder(httpBuilder)
                .apiKey(BuildConfig.DASHSCOPE_API_KEY)
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                .modelName("qwen-turbo")
                .parallelToolCalls(true)
                .build();

        // ── 记忆初始化 ──
        chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(MAX_MESSAGES)
                .chatMemoryStore(new PersistentChatMemorySqlite(context, "MainAgentMemory"))
                .build();

        // ── 系统提示词 ──
        this.promptManager = promptManager;
        systemPrompt = promptManager.render(PromptConstants.SYSTEM_ASSISTANT_DEFAULT);
        chatMemory.add(SystemMessage.systemMessage(systemPrompt));

        // ── 工具管理器 ──
        weatherUtils = new WeatherUtils(BuildConfig.WEATHER_API_KEY);
        doorManager = new VehicleDoorManager();
        windowManager = new VehicleWindowManager();
        seatManager = new VehicleSeatManager();
        acManager = new VehicleAcManager();
        chassisManager = new VehicleChassisManager();
        fragManager = new VehicleFragManager();
        speedManager = new VehicleSpeedManager();
        dmsManager = new VehicleDMSManager();
        vlManager = new VlManager(context, promptManager);

        // ── 工具注册表 + 工具声明 ──
        toolRegistry.registerAll(
                weatherUtils, doorManager, windowManager, seatManager,
                acManager, chassisManager, fragManager, speedManager,
                dmsManager, vlManager
        );
        toolSpecifications = toolRegistry.getToolSpecifications();
    }

    // ──────────────────── 公开接口 ────────────────────

    /** 发送用户消息并返回 AI 回复 */
    public String chat(String userMessage) {
        Log.d(TAG, "User: " + userMessage);
        chatMemory.add(UserMessage.userMessage(userMessage));
        try {
            return executeLoop();
        } catch (Exception e) {
            Log.e(TAG, "chat failed", e);
            return "系统: 请求失败 - " + e.getMessage();
        }
    }

    /** 清空记忆并重建系统提示词 */
    public void cleanMemory() {
        chatMemory.clear();
        chatMemory.add(SystemMessage.systemMessage(systemPrompt));
    }

    // ──────────────────── 核心循环 ────────────────────

    private String executeLoop() {
        for (int round = 0; round < MAX_ITERATIONS; round++) {
            // 注入车辆状态作为当前上下文
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(UserMessage.userMessage(
                    promptManager.render(PromptConstants.USER_VEHICLE_STATUS,
                            Map.of("vehicle_status", getVehicleStatus()))));
            messages.addAll(chatMemory.messages());

            ChatRequest request = ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(toolSpecifications)
                    .build();

            ChatResponse response = model.chat(request);
            AiMessage aiMessage = response.aiMessage();
            chatMemory.add(aiMessage);

            if (!aiMessage.hasToolExecutionRequests()) {
                return aiMessage.text();
            }

            // 执行工具调用
            for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                String result = dispatchTool(req);
                Log.d(TAG, "Tool[" + req.name() + "] -> " + result);
                chatMemory.add(ToolExecutionResultMessage.from(req, result));
            }
        }

        Log.w(TAG, "Reached max iterations (" + MAX_ITERATIONS + ")");
        return "系统: 已达到最大迭代次数，请简化您的问题。";
    }

    // ──────────────────── 工具调度 ────────────────────

    private String dispatchTool(ToolExecutionRequest request) {
        return toolRegistry.dispatch(request);
    }

    // ──────────────────── 车辆状态采集 ────────────────────

    private String getVehicleStatus() {
        try {
            JSONObject json = new JSONObject();
            json.put("车门", new JSONObject(doorManager.getDoorStatus()));
            json.put("车窗", new JSONObject(windowManager.getWindowStatus()));
            json.put("座椅、方向盘", new JSONObject(seatManager.getSeatStatus()));
            json.put("空调", new JSONObject(acManager.getAcStatus()));
            json.put("底盘", new JSONObject(chassisManager.getChassisStatus()));
            json.put("香氛", new JSONObject(fragManager.getFragStatus()));
            json.put("车速", new JSONObject(speedManager.getSpeedStatus()));
            json.put("DMS", new JSONObject(dmsManager.getDmsStatus()));
            json.put("当前地址", "北京市东城区");
            return json.toString();
        } catch (JSONException e) {
            return "无效的车辆状态";
        }
    }

}
