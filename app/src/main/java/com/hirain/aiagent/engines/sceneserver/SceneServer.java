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
import com.hirain.aiagent.prompt.PromptConstants;
import com.hirain.aiagent.prompt.PromptManager;

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
    private final PromptManager promptManager;
    private final String systemPrompt;
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
    public SceneServer(Context context, PromptManager promptManager) {
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

        this.promptManager = promptManager;
        systemPrompt = promptManager.render(PromptConstants.SYSTEM_ASSISTANT_SCENE);
        chatMemory.add(SystemMessage.systemMessage(systemPrompt));
    }
    public String scene_server(SceneMatch.Scene scene) {
        chatMemory.add(UserMessage.userMessage(
                promptManager.render(PromptConstants.USER_SCENE_DESCRIPTION,
                        Map.of("scene_description", scene.to_string()))));
        chatMemory.add(UserMessage.userMessage(
                promptManager.render(PromptConstants.USER_VEHICLE_STATUS,
                        Map.of("vehicle_status", getVehicleStatus()))));
        chatMemory.add(UserMessage.from(
                promptManager.render(PromptConstants.USER_ACTIVE_CONTROL)));
        ChatRequest request = ChatRequest.builder()
                .messages(chatMemory.messages())
                .toolSpecifications(tools.get(scene.name))
                .build();
        ChatResponse aiResponse = model.chat(request);
        String res = processFunctionCall(aiResponse, scene);
        chatMemory.clear();
        chatMemory.add(SystemMessage.systemMessage(systemPrompt));
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
                    .messages(UserMessage.userMessage(
                            promptManager.render(PromptConstants.USER_SUMMARIZE,
                                    Map.of("first_part", first,
                                            "second_part", second))))
                    .build();
            ChatResponse aiResponse_with_specified = model.chat(request_with_specified);
            return aiResponse_with_specified.aiMessage().text();
        } else {
            return "Error FunctionCalling: " + aiResponse.aiMessage().text();
        }
    }
}
