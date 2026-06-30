package com.hirain.aiagent.engines.chat;

import android.content.Context;
import android.util.Log;

import com.hirain.aiagent.tools.vehicle.ac.VehicleAcManager;
import com.hirain.aiagent.tools.vehicle.door.VehicleDoorManager;
import com.hirain.aiagent.tools.vehicle.frag.VehicleFragManager;
import com.hirain.aiagent.tools.vehicle.seat.VehicleSeatManager;
import com.hirain.aiagent.tools.vehicle.window.VehicleWindowManager;
import com.hirain.aiagent.tools.vision.vl.VlManager;

import com.hirain.aiagent.BuildConfig;
import com.hirain.aiagent.prompt.PromptConstants;
import com.hirain.aiagent.prompt.PromptManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
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
import com.hirain.aiagent.tools.external.weather.WeatherUtils;

public class ChatServer {
    private static final String TAG = "ChatServer";
    private final ChatMemory chatMemory;
    private final ChatModel model;
    private final VlManager vl;
    private final PromptManager promptManager;
    private final String systemPrompt;
    private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy年MM月dd日 HH时mm分", Locale.getDefault());
    private final WeatherUtils weatherutils;
    VehicleDoorManager doorManager;
    VehicleWindowManager windowManager;
    VehicleSeatManager seatManager;
    private final VehicleAcManager acManager;
    private final VehicleFragManager fragManager;
    private final List<ToolSpecification> mergedTools;
    public ChatServer(Context context, VlManager vltmp, PromptManager promptManager) {
        OkHttpClientBuilder okHttpClientBuilder = langchain4j.http_client_ok.OkHttpClient.builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(120));
        weatherutils = new WeatherUtils("c9af807ed95f93b56855a928417586f9");
        List<ToolSpecification> weatherTools = ToolSpecifications.toolSpecificationsFrom(WeatherUtils.class);
        doorManager = new VehicleDoorManager();
        List<ToolSpecification> doorTools = ToolSpecifications.toolSpecificationsFrom(VehicleDoorManager.class);
        windowManager = new VehicleWindowManager();
        List<ToolSpecification> windowTools = ToolSpecifications.toolSpecificationsFrom(VehicleWindowManager.class);
        seatManager = new VehicleSeatManager();
        List<ToolSpecification> seatTools = ToolSpecifications.toolSpecificationsFrom(VehicleSeatManager.class);
        acManager = new VehicleAcManager();
        List<ToolSpecification> acTools = ToolSpecifications.toolSpecificationsFrom(VehicleAcManager.class);
        fragManager = new VehicleFragManager();
        List<ToolSpecification> fragTools = ToolSpecifications.toolSpecificationsFrom(VehicleFragManager.class);
        List<ToolSpecification> vlTools = ToolSpecifications.toolSpecificationsFrom(VlManager.class);
        mergedTools = Stream
                .of(weatherTools, doorTools, windowTools, seatTools, acTools, fragTools, vlTools)
                .flatMap(List::stream).collect(Collectors.toList());
        model = OpenAiChatModel.builder()
                .httpClientBuilder(okHttpClientBuilder)
                .apiKey(BuildConfig.DASHSCOPE_API_KEY)
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                .modelName("qwen-turbo")
                .parallelToolCalls(true)
                .build();
        chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(50)
                .chatMemoryStore(new PersistentChatMemorySqlite(context.getApplicationContext(), "ChatMemory"))
                .build();
        this.promptManager = promptManager;
        systemPrompt = promptManager.render(PromptConstants.SYSTEM_ASSISTANT_DEFAULT);
        chatMemory.add(SystemMessage.systemMessage(systemPrompt));
        vl = vltmp;
    }

    private String handleTools(ToolExecutionRequest request) {
        if (doorManager.hasTool(request.name())) {
            return doorManager.handleToolRequest(request);
        } else if (windowManager.hasTool(request.name())) {
            return windowManager.handleToolRequest(request);
        } else if (weatherutils.hasTool(request.name())) {
            return weatherutils.handleToolRequest(request);
        } else if (seatManager.hasTool(request.name())) {
            return seatManager.handleToolRequest(request);
        } else if (acManager.hasTool(request.name())) {
            return acManager.handleToolRequest(request);
        } else if (fragManager.hasTool(request.name())) {
            return fragManager.handleToolRequest(request);
        } else if (vl.hasTool(request.name())) {
            return vl.handleToolRequest(request);
        }
        return "无效的工具调用。";
    }
    private String processAiResponse(ChatResponse aiResponse) {
        AiMessage aiMessage = aiResponse.aiMessage();
        chatMemory.add(aiMessage);
        if (aiMessage.hasToolExecutionRequests()) {
            List<ToolExecutionRequest> tooExecutionRequests = aiMessage.toolExecutionRequests();
            for (ToolExecutionRequest toolrequest : tooExecutionRequests) {
                String result = handleTools(toolrequest);
                Log.d(TAG, "Tools: " + "工具["  + toolrequest.name() + toolrequest.arguments() + "] 执行中");
                ToolExecutionResultMessage toolExecutionResultMessage = ToolExecutionResultMessage.from(toolrequest, result);
                chatMemory.add(toolExecutionResultMessage);
            }
            ChatRequest request_with_tool = ChatRequest.builder()
                    .messages(chatMemory.messages())
                    .toolSpecifications(mergedTools)
                    .build();
            ChatResponse aiResponse_with_tool = model.chat(request_with_tool);
            return processAiResponse(aiResponse_with_tool);
        } else {
            return aiResponse.aiMessage().text();
        }
    }
    private String chatWithVehicleStatus() {
        List<ChatMessage> tmp = new ArrayList<>();
        tmp.add(UserMessage.userMessage(
                promptManager.render(PromptConstants.USER_VEHICLE_STATUS,
                        Map.of("vehicle_status", getVehicleStatus()))));
        tmp.addAll(chatMemory.messages());
        ChatRequest request = ChatRequest.builder()
                .messages(tmp)
                .toolSpecifications(mergedTools)
                .build();
        ChatResponse aiResponse = model.chat(request);
        return processAiResponse(aiResponse);
    }
    private String getVehicleStatus() {
        try {
            JSONObject json = new JSONObject();
            JSONObject doorjson = new JSONObject(doorManager.getDoorStatus());
            JSONObject windowjson = new JSONObject(windowManager.getWindowStatus());
            JSONObject seatjson = new JSONObject(seatManager.getSeatStatus());
            JSONObject acjson = new JSONObject(acManager.getAcStatus());
            JSONObject fragjson = new JSONObject(fragManager.getFragStatus());
            json.put("车门", doorjson);
            json.put("车窗", windowjson);
            json.put("座椅、方向盘", seatjson);
            json.put("空调", acjson);
            json.put("香氛", fragjson);
            json.put("当前地址", "北京市东城区");
            return json.toString();
        } catch (JSONException e){
            return "无效的车辆状态";
        }
    }
    public String chat(String userMessage) {
        try {
            Log.d(TAG, "User: " + userMessage);
            chatMemory.add(UserMessage.userMessage(userMessage));
            String resp = chatWithVehicleStatus();
            Log.d(TAG, "AI: " + resp);
            return resp;
        } catch (Exception e) {
            Log.d(TAG, "error");
            return("系统: 请求失败 - " + e.getMessage());
        }
    }
    public void cleanMemory() {
        chatMemory.clear();
        chatMemory.add(SystemMessage.systemMessage(systemPrompt));
    }
}
