package com.hirain.aiagent;

import android.content.Context;
import android.util.Log;

import com.hirain.aiagent.vehicleacmanager.VehicleAcManager;
import com.hirain.aiagent.vehicledoormanager.VehicleDoorManager;
import com.hirain.aiagent.vehiclefragmanager.VehicleDMSManager;
import com.hirain.aiagent.vehiclefragmanager.VehicleFragManager;
import com.hirain.aiagent.vehiclefragmanager.VehicleSpeedManager;
import com.hirain.aiagent.vehicleseatmanager.VehicleSeatManager;
import com.hirain.aiagent.vehiclewindowmanager.VehicleWindowManager;
import com.hirain.aiagent.scenematch.*;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
import langchain4j.http_client_ok.OkHttpClientBuilder;

public class SceneServer {
    private static final String TAG = "SceneService";
    private ChatMemory chatMemory;
    private ChatModel model;
    private final String systemPrompt_ =
            "角色定义：\n" +
            "    你是一位专业、友好且高度智能的车载AI助手，专注于提供安全、高效、愉悦的驾驶体验。\n" +
            "    你集成多种人工智能技术，通过不断学习迭代升级功能，在软硬件配合下实现自然流畅的人车智能交互。\n" +
            "    你的核心使命是在保障驾驶安全的前提下，为用户提供全方位的智能座舱服务。\n" +
            "核心原则\n" +
            "    认知友好：从用户认知角度出发，使用简化易懂高效的提示，尽量避免或减少专业术语。\n" +
            "    上下文感知：持续跟踪对话历史，结合当前车辆状态、舱内外场景描述等上下文提供个性化服务。\n" +
            "    主动智能：能够预测用户需求，在适当时机提供主动建议，但不过度打扰。\n" +
            "    安全相关：施工、火灾、车祸、大雾雨雪等极端场景，必须提供安全驾驶建议。\n" +
            "功能规范\n" +
            "    通用对话能力\n" +
            "        1. 对话风格：保持友好、专业且符合驾驶场景的对话风格，避免过度拟人化。\n" +
            "        2. 根据场景描述和实际执行结果，完整概括所有成功执行的座舱体验。\n" +
            "        3. 施工、火灾、大雾雨雪等极端场景，必须提供安全驾驶建议。\n" +
            "    智能座舱专属功能\n" +
            "        根据场景模糊控车\n" +
            "            识别隐含需求：例如：\"座舱升温\" --> 自动调高空调温度。" +
            "交互规范\n" +
            "    话术要求\n" +
            "        保持简洁，单次语音输出不超过30秒。\n";
    VehicleDoorManager doorManager = new VehicleDoorManager();
    VehicleWindowManager windowManager = new VehicleWindowManager();
    private final List<ToolSpecification> windowTools = ToolSpecifications.toolSpecificationsFrom(VehicleWindowManager.class);
    VehicleSeatManager seatManager = new VehicleSeatManager();
    private final List<ToolSpecification> seatTools = ToolSpecifications.toolSpecificationsFrom(VehicleSeatManager.class);
    private final VehicleAcManager acManager = new VehicleAcManager();
    private final List<ToolSpecification> acTools = ToolSpecifications.toolSpecificationsFrom(VehicleAcManager.class);
    private final VehicleFragManager fragManager = new VehicleFragManager();
    private final VehicleSpeedManager speedManager = new VehicleSpeedManager();
    private final VehicleDMSManager dmsManager = new VehicleDMSManager();
    private final List<ToolSpecification> speedTools = ToolSpecifications.toolSpecificationsFrom(VehicleSpeedManager.class);
    private final List<ToolSpecification> dmsTools = ToolSpecifications.toolSpecificationsFrom(VehicleDMSManager.class);

    private final List<ToolSpecification> fragTools = ToolSpecifications.toolSpecificationsFrom(VehicleFragManager.class);

    private final List<ToolSpecification> mergedTools = Stream
            .of(windowTools, seatTools, acTools, fragTools, speedTools, dmsTools)
            .flatMap(List::stream).collect(Collectors.toList());

    public SceneServer(Context context) {
        OkHttpClientBuilder okHttpClientBuilder = langchain4j.http_client_ok.OkHttpClient.builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(120));
        model = OpenAiChatModel.builder()
                .httpClientBuilder(okHttpClientBuilder)
                .apiKey("sk-11129fb7941f49dbb083039a93a160bc")
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                .modelName("qwen-turbo")
                .parallelToolCalls(true)
                .build();
        chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(50)
                .chatMemoryStore(new PersistentChatMemorySqlite(context.getApplicationContext()))
                .build();

        chatMemory.add(SystemMessage.systemMessage(systemPrompt_));
    }
    public String scene_server(SceneMatch.Scene scene) {
        chatMemory.add(UserMessage.userMessage("场景描述",scene.to_string()));
        chatMemory.add(UserMessage.userMessage("车辆状态", getVehicleStatus()));
        String userPrompt =
                "基于当前座舱内/外场景描述，帮我主动控车" +
                "使用工具能力一次把座舱调节至最舒适的理想状态，" +
                "座舱温度调节或者隔离舱外环境时别忘记把开启的车窗关闭，" +
                "必要时提供安全驾驶建议，" +
                "当且仅当场景为驾驶员疲劳时调节目标为提神醒脑，此时不要过于舒适。";
        chatMemory.add(UserMessage.from(userPrompt));
        for (ChatMessage msg : chatMemory.messages()) {
            Log.d(TAG, msg.toString());
        }
        ChatRequest request = ChatRequest.builder()
                .messages(chatMemory.messages())
                .toolSpecifications(mergedTools)
                .build();
        ChatResponse aiResponse = model.chat(request);
        String res = processFunctionCall(aiResponse);
        chatMemory.clear();
        chatMemory.add(SystemMessage.systemMessage(systemPrompt_));
        return res;
    }
    private String getVehicleStatus() {
        try {
            JSONObject json = new JSONObject();
            JSONObject doorjson = new JSONObject(doorManager.getDoorStatus());
            JSONObject windowjson = new JSONObject(windowManager.getWindowStatus());
            JSONObject seatjson = new JSONObject(seatManager.getSeatStatus());
            JSONObject acjson = new JSONObject(acManager.getAcStatus());
            JSONObject fragjson = new JSONObject(fragManager.getFragStatus());
            JSONObject speedjson = new JSONObject(speedManager.getSpeedStatus());
            JSONObject dmsjson = new JSONObject(dmsManager.getDmsStatus());
            json.put("车门", doorjson);
            json.put("车窗", windowjson);
            json.put("座椅、方向盘", seatjson);
            json.put("空调", acjson);
            json.put("香氛", fragjson);
            json.put("车速", speedjson);
            json.put("DMS", dmsjson);
            return json.toString();
        } catch (JSONException e){
            return "无效的车辆状态";
        }
    }
    private String handleTools(ToolExecutionRequest request) {
        if (windowManager.hasTool(request.name())) {
            return windowManager.handleToolRequest(request);
        } else if (seatManager.hasTool(request.name())) {
            return seatManager.handleToolRequest(request);
        } else if (acManager.hasTool(request.name())) {
            return acManager.handleToolRequest(request);
        } else if (fragManager.hasTool(request.name())) {
            return fragManager.handleToolRequest(request);
        } else if (speedManager.hasTool(request.name())) {
            return speedManager.handleToolRequest(request);
        } else if (dmsManager.hasTool(request.name())) {
            return dmsManager.handleToolRequest(request);
        }

        return "无效的工具调用。";
    }
    private String processFunctionCall(ChatResponse aiResponse) {
        AiMessage aiMessage = aiResponse.aiMessage();
        if (aiMessage.hasToolExecutionRequests()) {
            chatMemory.add(aiMessage);
            List<ToolExecutionRequest> tooExecutionRequests = aiMessage.toolExecutionRequests();
            for (ToolExecutionRequest toolrequest : tooExecutionRequests) {
                String result = handleTools(toolrequest);
                Log.d(TAG,"Tools: " + "工具["  + toolrequest.name() + toolrequest.arguments() + "]");
                ToolExecutionResultMessage toolExecutionResultMessage = ToolExecutionResultMessage.from(toolrequest, result);
                chatMemory.add(toolExecutionResultMessage);
            }
            ChatRequest request_with_tool = ChatRequest.builder()
                    .messages(chatMemory.messages())
                    .build();
            ChatResponse aiResponse_with_tool = model.chat(request_with_tool);
            return aiResponse_with_tool.aiMessage().text();
        } else {
            return "Error FunctionCalling: " + aiResponse.aiMessage().text();
        }
    }
}
