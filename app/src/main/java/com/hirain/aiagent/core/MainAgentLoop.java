package com.hirain.aiagent.core;

import android.content.Context;
import android.util.Log;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
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

    private final Map<String, ToolExecutor> toolRegistry = new LinkedHashMap<>();
    private final List<ToolSpecification> toolSpecifications;

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

    @FunctionalInterface
    private interface ToolExecutor {
        String execute(ToolExecutionRequest request);
    }

    public MainAgentLoop(Context context) {
        // ── 模型初始化 ──
        OkHttpClientBuilder httpBuilder = OkHttpClient.builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(120));
        model = OpenAiChatModel.builder()
                .httpClientBuilder(httpBuilder)
                .apiKey("sk-11129fb7941f49dbb083039a93a160bc")
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
        systemPrompt = buildSystemPrompt();
        chatMemory.add(SystemMessage.systemMessage(systemPrompt));

        // ── 工具管理器 ──
        weatherUtils = new WeatherUtils("c9af807ed95f93b56855a928417586f9");
        doorManager = new VehicleDoorManager();
        windowManager = new VehicleWindowManager();
        seatManager = new VehicleSeatManager();
        acManager = new VehicleAcManager();
        chassisManager = new VehicleChassisManager();
        fragManager = new VehicleFragManager();
        speedManager = new VehicleSpeedManager();
        dmsManager = new VehicleDMSManager();
        vlManager = new VlManager(context);

        // ── 工具注册表 + 工具声明 ──
        toolSpecifications = registerTools();
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
            messages.add(UserMessage.userMessage("车辆状态", getVehicleStatus()));
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
        ToolExecutor executor = toolRegistry.get(request.name());
        if (executor != null) {
            return executor.execute(request);
        }
        return "无效的工具调用: " + request.name();
    }

    private List<ToolSpecification> registerTools() {
        List<Object> managers = List.of(
                weatherUtils, doorManager, windowManager, seatManager,
                acManager, chassisManager, fragManager, speedManager,
                dmsManager, vlManager
        );

        List<ToolSpecification> specs = new ArrayList<>();
        for (Object manager : managers) {
            List<ToolSpecification> mgrSpecs = ToolSpecifications.toolSpecificationsFrom(manager.getClass());
            specs.addAll(mgrSpecs);
            for (ToolSpecification spec : mgrSpecs) {
                ToolExecutor executor = req -> dispatchToManager(manager, req);
                toolRegistry.put(spec.name(), executor);
            }
        }
        return List.copyOf(specs);
    }

    private String dispatchToManager(Object manager, ToolExecutionRequest request) {
        if (manager instanceof WeatherUtils) return ((WeatherUtils) manager).handleToolRequest(request);
        if (manager instanceof VehicleDoorManager) return ((VehicleDoorManager) manager).handleToolRequest(request);
        if (manager instanceof VehicleWindowManager) return ((VehicleWindowManager) manager).handleToolRequest(request);
        if (manager instanceof VehicleSeatManager) return ((VehicleSeatManager) manager).handleToolRequest(request);
        if (manager instanceof VehicleAcManager) return ((VehicleAcManager) manager).handleToolRequest(request);
        if (manager instanceof VehicleChassisManager) return ((VehicleChassisManager) manager).handleToolRequest(request);
        if (manager instanceof VehicleFragManager) return ((VehicleFragManager) manager).handleToolRequest(request);
        if (manager instanceof VehicleSpeedManager) return ((VehicleSpeedManager) manager).handleToolRequest(request);
        if (manager instanceof VehicleDMSManager) return ((VehicleDMSManager) manager).handleToolRequest(request);
        if (manager instanceof VlManager) return ((VlManager) manager).handleToolRequest(request);
        return "未知工具管理器";
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

    // ──────────────────── 系统提示词 ────────────────────

    private String buildSystemPrompt() {
        return "角色定义：\n" +
                "    你是一位专业、友好且高度智能的车载AI助手，专注于提供安全、高效、愉悦的驾驶体验。\n" +
                "    你的核心使命是在保障驾驶安全的前提下，为用户提供全方位的智能座舱服务。\n" +
                "核心原则\n" +
                "    认知友好：使用简化易懂的表达，尽量避免专业术语。\n" +
                "    上下文感知：结合当前驾驶状态、地理位置、时间等上下文提供个性化服务。\n" +
                "    主动智能：能够预测用户需求，但不过度打扰。\n" +
                "    严谨准确：无法完成的用户请求，以当前系统不支持为由礼貌拒绝。\n" +
                "功能规范\n" +
                "    通用对话：自然聊天、娱乐互动（单次不超过1分钟）、百科问答。\n" +
                "    天气查询：使用对应工具查询天气。\n" +
                "    推荐能力：音乐/影视、旅游景点、游玩建议。\n" +
                "    前向视野互动：用户询问前方物体时，必须重新调用工具获取实时前向视野数据。\n" +
                "    精准控车：支持自然语言的车辆控制，复杂指令拆解分步执行。\n" +
                "    模糊控车：识别隐含需求（如\"有点冷\"自动调高空调温度），需二次确认。\n" +
                "交互规范\n" +
                "    保持简洁，单次语音输出不超过30秒。\n" +
                "    模糊控车需要二次确认。\n" +
                "能力边界\n" +
                "    能够控制车辆功能、提供天气信息、娱乐和旅途建议。\n" +
                "    上述功能之外的请求，以当前系统不支持为由礼貌拒绝。";
    }
}
