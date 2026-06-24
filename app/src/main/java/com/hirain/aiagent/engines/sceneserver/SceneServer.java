package com.hirain.aiagent.engines.sceneserver;

import android.content.Context;
import android.util.Log;

import com.hirain.aiagent.tools.vehicle.ac.VehicleAcManager;
import com.hirain.aiagent.tools.vehicle.door.VehicleDoorManager;
import com.hirain.aiagent.tools.vehicle.frag.VehicleFragManager;
import com.hirain.aiagent.tools.vehicle.seat.VehicleSeatManager;
import com.hirain.aiagent.tools.vehicle.window.VehicleWindowManager;
import com.hirain.aiagent.tools.vehicle.chassis.VehicleChassisManager;
import com.hirain.aiagent.engines.scenematch.*;

import com.hirain.aiagent.BuildConfig;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final ChatMemory chatMemory;
    private final ChatModel model;
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
            "    智能座舱专属功能\n" +
            "        识别隐含需求，根据场景模糊控车。\n" +
            "交互规范\n" +
            "    话术必须包括如下内容：\n" +
            "        1. 简单描述场景且概扩调用的工具\n" +
            "        2. 必要时提供安全驾驶建议\n " +
            "    自然语言响应限制在80字以内。\n";
    VehicleDoorManager doorManager = new VehicleDoorManager();
    VehicleWindowManager windowManager = new VehicleWindowManager();
    private final List<ToolSpecification> windowTools = ToolSpecifications.toolSpecificationsFrom(VehicleWindowManager.class);
    VehicleSeatManager seatManager = new VehicleSeatManager();
    private final List<ToolSpecification> seatTools = ToolSpecifications.toolSpecificationsFrom(VehicleSeatManager.class);
    private final VehicleAcManager acManager = new VehicleAcManager();
    private final List<ToolSpecification> acTools = ToolSpecifications.toolSpecificationsFrom(VehicleAcManager.class);
    private final VehicleFragManager fragManager = new VehicleFragManager();
    private final List<ToolSpecification> fragTools = ToolSpecifications.toolSpecificationsFrom(VehicleFragManager.class);
    private final VehicleChassisManager chassisManager = new VehicleChassisManager();
    private final List<ToolSpecification> chassisTools = ToolSpecifications.toolSpecificationsFrom(VehicleChassisManager.class);
    private final Map<String, List<ToolSpecification>> tools = new HashMap<String, List<ToolSpecification>>() {{
        put("雨雪天气", Stream.of(windowTools, seatTools, acTools, chassisTools).
                flatMap(List::stream).collect(Collectors.toList()));
        put("工程施工", Stream.of(windowTools, acTools).
                flatMap(List::stream).collect(Collectors.toList()));
        put("舱外浓烟", Stream.of(windowTools, acTools).
                flatMap(List::stream).collect(Collectors.toList()));
        put("驾驶员疲劳", Stream.of(fragTools, seatTools, acTools).
                flatMap(List::stream).collect(Collectors.toList()));
        put("乘客休息", Stream.of(fragTools, windowTools, acTools).
                flatMap(List::stream).collect(Collectors.toList()));
    }};
    public SceneServer(Context context) {
        OkHttpClientBuilder okHttpClientBuilder = langchain4j.http_client_ok.OkHttpClient.builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(120));
        model = OpenAiChatModel.builder()
                .httpClientBuilder(okHttpClientBuilder)
                .apiKey(BuildConfig.DASHSCOPE_API_KEY)
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                .modelName("qwen-flash")
                .parallelToolCalls(true)
                .build();
        chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(50)
                .chatMemoryStore(new PersistentChatMemorySqlite(context.getApplicationContext(), "SceneMemory"))
                .build();

        chatMemory.add(SystemMessage.systemMessage(systemPrompt_));
    }
    public String scene_server(SceneMatch.Scene scene) {
        chatMemory.add(UserMessage.userMessage("场景描述",scene.to_string()));
        chatMemory.add(UserMessage.userMessage("车辆状态", getVehicleStatus()));
        String userPrompt =
                "基于当前座舱内/外场景描述，帮我主动控车，使用工具能力一次把座舱调节至最理想状态。\n" +
                "工具使用规则：\n" +
                "    1. 保证仅在以下场景调节空调温度，其他场景禁止调节空调温度：\n" +
                "        1) 雪天寒冷场景，空调设置在27-30摄氏度之间；\n" +
                "        2) 驾驶员疲劳场景，空调温度设置在19-22摄氏度之间。\n" +
                "        3) 乘员休息场景，空调设置为舒适温度。\n" +
                "    2. 如果调节了空调温度，需要同时关闭已开启的车窗。\n" +
                "    3. 保证仅在雪天寒冷场景下控制方向盘与座椅加热，其他场景禁止开启加热" +
                "    4. 雪天需要把行驶模式切换为雪地模式，保证安全驾驶";        chatMemory.add(UserMessage.from(userPrompt));
        ChatRequest request = ChatRequest.builder()
                .messages(chatMemory.messages())
                .toolSpecifications(tools.get(scene.name))
                .build();
        ChatResponse aiResponse = model.chat(request);
        String res = processFunctionCall(aiResponse, scene);
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
            JSONObject chassisjson = new JSONObject(chassisManager.getChassisStatus());
            json.put("车门", doorjson);
            json.put("车窗", windowjson);
            json.put("座椅、方向盘", seatjson);
            json.put("空调", acjson);
            json.put("香氛", fragjson);
            json.put("底盘",chassisjson);
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
        } else if (chassisManager.hasTool(request.name())) {
            return chassisManager.handleToolRequest(request);
        }
        return "无效的工具调用。";
    }
    private String handleSceneSpecified (SceneMatch.Scene scene) {
        if (scene.name.equals("驾驶员疲劳")) {
//            TODO：ctrl voice, safety belt, drive-support.
            return "方向盘震动已开启，并开启驾驶辅助。";
        } else if (scene.name.equals("舱外浓烟")) {
//            TODO: ctrl light.
            return "已打开双闪提醒后车。";
        } else if (scene.name.equals("雨雪天气")) {
//            TODO: ctrl light.
            return "已打开雾灯、示廓灯，已切换到雪地模式。";
        } else if (scene.name.equals("乘客休息")) {
//            TODO: ctrl voice.
            return "已降低座舱系统音量。";
        } else if (scene.name.equals("工程施工")) {
//            TODO: ctrl light, drive-support.
            return "已开双闪提醒后车，已关闭驾驶辅助。";
        }
        return "";
    }
    private String processFunctionCall(ChatResponse aiResponse, SceneMatch.Scene scene) {
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
            for (ChatMessage msg : chatMemory.messages()) {
                Log.d(TAG, msg.toString());
            }
            ChatRequest request_with_tool = ChatRequest.builder()
                    .messages(chatMemory.messages())
                    .build();
            ChatResponse aiResponse_with_tool = model.chat(request_with_tool);
            chatMemory.clear();
            String first = aiResponse_with_tool.aiMessage().text();
            String second = handleSceneSpecified(scene);
            Log.d(TAG, first);
            Log.d(TAG, second);
            ChatRequest request_with_specified = ChatRequest.builder()
                    .messages(UserMessage.userMessage("整合下面两段话，字数80字以内：\n" + first + "\n" + second))
                    .build();
            ChatResponse aiResponse_with_specified = model.chat(request_with_specified);
            return aiResponse_with_specified.aiMessage().text();
        } else {
            return "Error FunctionCalling: " + aiResponse.aiMessage().text();
        }
    }
}
