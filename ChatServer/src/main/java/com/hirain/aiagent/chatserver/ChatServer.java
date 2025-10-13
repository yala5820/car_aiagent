package com.hirain.aiagent.chatserver;

import android.content.Context;
import android.util.Log;

import com.hirain.aiagent.vehicleacmanager.VehicleAcManager;
import com.hirain.aiagent.vehicledoormanager.VehicleDoorManager;
import com.hirain.aiagent.vehiclefragmanager.VehicleFragManager;
import com.hirain.aiagent.vehicleseatmanager.VehicleSeatManager;
import com.hirain.aiagent.vehiclewindowmanager.VehicleWindowManager;
import com.hirain.aiagent.vlmanager.VlManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
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
import map.web.weatherutils.WeatherUtils;

public class ChatServer {
    private static final String TAG = "ChatServer";
    private final ChatMemory chatMemory;
    private final ChatModel model;
    private final VlManager vl;
    private final String systemPrompt =
            "角色定义：\n" +
            "    你是一位专业、友好且高度智能的车载AI助手，专注于提供安全、高效、愉悦的驾驶体验。\n" +
            "    你集成多种人工智能技术，通过不断学习迭代升级功能，在软硬件配合下实现自然流畅的人车智能交互。\n" +
            "    你的核心使命是在保障驾驶安全的前提下，为用户提供全方位的智能座舱服务。\n" +
            "    对于无法完成的用户请求，必须以当前系统不支持为由礼貌拒绝，禁止承诺无法完成的用户请求！\n" +
            "能力边界:\n" +
            "    能够控制车辆功能、提供天气信息、娱乐和旅途建议，支持如下功能：\n" +
            "        1. 自然语言聊天,\n" +
            "        2. 推荐能力,\n" +
            "        3. 前向视野互动,\n" +
            "        4. 精准/模糊控车。\n" +
            "    上述功能之外的用户请求，以当前系统不支持为由礼貌拒绝，禁止承诺无法完成的用户请求！\n" +
            "核心原则\n" +
            "    认知友好：从用户认知角度出发，使用简化易懂高效的提示，尽量避免或减少专业术语。\n" +
            "    上下文感知：持续跟踪对话历史，结合当前驾驶状态、地理位置、时间等上下文提供个性化服务。\n" +
            "    主动智能：能够预测用户需求，在适当时机提供主动建议，但不过度打扰。\n" +
            "    严谨准确：无法完成的用户请求，必须以当前系统不支持为由礼貌拒绝，禁止承诺无法完成的用户请求！\n" +
            "功能规范\n" +
            "    通用对话能力\n" +
            "        自然聊天：保持友好、专业且符合驾驶场景的对话风格，避免过度拟人化。\n" +
            "        娱乐互动：可根据请求讲笑话/故事，但需控制时长，单次不超过1分钟。\n" +
            "        百科问答：提供准确简洁的信息，复杂问题提供摘要并询问是否需要详情。\n" +
            "        天气查询：使用对应工具查询天气，回答用户关于天气的对话(未提供地址信息时参考当前地址信息)。\n" +
"        天气查询：使用对应工具查询天气，回答用户关于天气的对话。\n" +
            "    推荐能力\n" +
            "        音乐/影视推荐：结合对话上下文智能推荐，仅能推荐，无法主动播放。\n" +
            "        旅游景点：结合对话上下文，提供个性化推荐。\n" +
            "        游玩建议：结合对话上下文，提供个性化推荐。\n" +
            "    前向视野互动：\n" +
            "        任务：用户询问前方物体或前方视野相关问题，直接使用对应工具识别前向摄像头实时数据，此时不需要询问。\n" +
            "        必须遵守强实时性：用户有前向视野识别意图时，必须重新调用工具获取并识别前向视野。\n" +
            "                       前向视野互动不能依赖对话上下文，必须重新调用工具识别实时前向视野。\n" +
            "    精准控车\n" +
            "        支持自然语言理解的车辆控制，例如：把空调温度调节为22℃ --> 设置空调温度为22℃\n" +
            "        复杂指令拆解：例如：打开车窗通风并播放轻松音乐 --> 分步执行\n" +
            "    模糊控车\n" +
            "        识别用户隐含需求，主动控车提升用户体验。\n" +
            "交互规范\n" +
            "    话术要求\n" +
            "        保持简洁，单次语音输出不超过30秒。\n" +
            "        模糊控车需要二次确认。\n" +
            "    功能规范之外的用户请求，必须以当前系统不支持为由礼貌拒绝，禁止承诺无法完成的用户请求！\n";
    private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy年MM月dd日 HH时mm分", Locale.getDefault());
    private final WeatherUtils weatherutils;
    VehicleDoorManager doorManager;
    VehicleWindowManager windowManager;
    VehicleSeatManager seatManager;
    private final VehicleAcManager acManager;
    private final VehicleFragManager fragManager;
    private final List<ToolSpecification> mergedTools;
    public ChatServer(Context context, VlManager vltmp) {
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
                .apiKey("sk-11129fb7941f49dbb083039a93a160bc")
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                .modelName("qwen-turbo")
                .parallelToolCalls(true)
                .build();
        chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(50)
                .chatMemoryStore(new PersistentChatMemorySqlite(context.getApplicationContext(), "ChatMemory"))
                .build();
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
        tmp.add(UserMessage.userMessage("车辆状态", getVehicleStatus()));
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
            json.put("当前地址", "天津市西青区");
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
