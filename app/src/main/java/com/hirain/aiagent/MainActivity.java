package com.hirain.aiagent;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.json.JSONException;
import org.json.JSONObject;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.data.message.AiMessage;
import langchain4j.http_client_ok.*;
import langchain4j.chat_memory_sqlite.*;
import map.web.weatherutils.*;
import com.hirain.aiagent.vehicledoormanager.*;
import com.hirain.aiagent.vehiclewindowmanager.*;
import com.hirain.aiagent.vehicleseatmanager.*;
import com.hirain.aiagent.vehicleacmanager.*;
import com.hirain.aiagent.vehiclefragmanager.*;
import com.hirain.aiagent.vlmanager.*;
import android.content.Context;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "AIAgent";
    private TextView chatHistory;
    private EditText userInput;
    private ChatMemory chatMemory;
    private AIAgentService.AIAgentBinder mAIAgentBinder;
    private ChatModel model;
    private VlManager vl;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final String systemPrompt =
                "角色定义：\n" +
                "    你是一位专业、友好且高度智能的车载AI助手，专注于提供安全、高效、愉悦的驾驶体验。\n" +
                "    你集成多种人工智能技术，通过不断学习迭代升级功能，在软硬件配合下实现自然流畅的人车智能交互。\n" +
                "    你的核心使命是在保障驾驶安全的前提下，为用户提供全方位的智能座舱服务。\n" +
                "核心原则\n" +
                "    认知友好：从用户认知角度出发，使用简化易懂高效的提示，尽量避免或减少专业术语。\n" +
                "    上下文感知：持续跟踪对话历史，结合当前驾驶状态、地理位置、时间等上下文提供个性化服务。\n" +
                "    主动智能：能够预测用户需求，在适当时机提供主动建议，但不过度打扰。\n" +
                "功能规范\n" +
                "    通用对话能力\n" +
                "        自然聊天：保持友好、专业且符合驾驶场景的对话风格，避免过度拟人化。\n" +
                "        娱乐互动：可根据请求讲笑话/故事，但需控制时长，单次不超过1分钟。\n" +
                "        百科问答：提供准确简洁的信息，复杂问题提供摘要并询问是否需要详情。\n" +
                "        天气查询：使用对应工具查询天气，回答用户关于天气的对话。\n" +
                "        推荐能力\n" +
                "            音乐/影视推荐：结合对话上下文智能推荐。\n" +
                "            旅游景点：结合对话上下文，提供个性化推荐。\n" +
                "            游玩建议：结合对话上下文，提供个性化推荐。\n" +
                "    智能座舱专属功能\n" +
                "        前向窗景互动：结合前向窗景识别工具的能力，在用户提及时提供相关信息。\n" +
                "        精准控车\n" +
                "            支持自然语言理解的车辆控制，例如：把空调温度调节为22℃ --> 设置空调温度为22℃\n" +
                "            复杂指令拆解：例如：打开车窗通风并播放轻松音乐 --> 分步执行\n" +
                "        模糊控车\n" +
                "            识别隐含需求：例如：\"有点冷\" --> 自动调高空调温度。" +
                "交互规范\n" +
                "    话术要求\n" +
                "        保持简洁，单次语音输出不超过30秒。\n" +
                "        模糊控车需要二次确认。\n" +
                "能力边界声明\n" +
                "    关于订单预定等功能将在后续的版本退出，当前版本仅能提供语音或文本建议。\n" +
                "    我能够帮助您控制车辆功能、提供天气信息、娱乐服务和旅途建议，但无法代替您进行驾驶操作。请始终将注意力集中在道路上，安全驾驶。\n";
    private final WeatherUtils weatherutils = new WeatherUtils(this, "c9af807ed95f93b56855a928417586f9");
    private final List<ToolSpecification> wheatherTools = ToolSpecifications.toolSpecificationsFrom(WeatherUtils.class);
    VehicleDoorManager doorManager = new VehicleDoorManager();
    private final List<ToolSpecification> doorTools = ToolSpecifications.toolSpecificationsFrom(VehicleDoorManager.class);
    VehicleWindowManager windowManager = new VehicleWindowManager();
    private final List<ToolSpecification> windowTools = ToolSpecifications.toolSpecificationsFrom(VehicleWindowManager.class);
    VehicleSeatManager seatManager = new VehicleSeatManager();
    private final List<ToolSpecification> seatTools = ToolSpecifications.toolSpecificationsFrom(VehicleSeatManager.class);
    private final VehicleAcManager acManager = new VehicleAcManager();
    private final List<ToolSpecification> acTools = ToolSpecifications.toolSpecificationsFrom(VehicleAcManager.class);
    private final VehicleFragManager fragManager = new VehicleFragManager();
    private final List<ToolSpecification> fragTools = ToolSpecifications.toolSpecificationsFrom(VehicleFragManager.class);
    private final List<ToolSpecification> vlTools = ToolSpecifications.toolSpecificationsFrom(VlManager.class);
    private final List<ToolSpecification> mergedTools = Stream
        .of(wheatherTools, doorTools, windowTools, seatTools, acTools, fragTools, vlTools)
        .flatMap(List::stream).collect(Collectors.toList());
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        chatHistory = findViewById(R.id.chatHistory);
        userInput = findViewById(R.id.userInput);
        Button sendButton = findViewById(R.id.sendButton);
        Button clearButton = findViewById(R.id.clearButton);
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
                .chatMemoryStore(new PersistentChatMemorySqlite(getApplicationContext()))
                .build();

        chatMemory.add(SystemMessage.systemMessage(systemPrompt));
        vl = new VlManager(this);
        sendButton.setOnClickListener(this::onSendClick);
        clearButton.setOnClickListener(this::onClearClick);
        initAIAgentClient();
        Log.d(TAG, "created");
    }
    private void onClearClick(View view) {
        chatMemory.clear();
        chatMemory.add(SystemMessage.systemMessage(systemPrompt));
        cleanChat();
    }
    private void onSendClick(View view) {
        String inputText = userInput.getText().toString().trim();
        if (!inputText.isEmpty()) {
            appendToChat( inputText);
            userInput.setText("");

            new Thread(() -> processUserRequest(inputText)).start();
        }
    }
    private void chatWithVehicleStatus() {
        List<ChatMessage> tmp = new ArrayList<>();
        tmp.add(UserMessage.userMessage("车辆状态", getVehicleStatus()));
        tmp.addAll(chatMemory.messages());
        ChatRequest request = ChatRequest.builder()
                .messages(tmp)
                .toolSpecifications(mergedTools)
                .build();
        ChatResponse aiResponse = model.chat(request);
        processAiResponse(aiResponse);
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
            return json.toString();
        } catch (JSONException e){
            return "无效的车辆状态";
        }
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
    private void processAiResponse(ChatResponse aiResponse) {
        AiMessage aiMessage = aiResponse.aiMessage();
        chatMemory.add(aiMessage);
        if (aiMessage.hasToolExecutionRequests()) {
            List<ToolExecutionRequest> tooExecutionRequests = aiMessage.toolExecutionRequests();
            for (ToolExecutionRequest toolrequest : tooExecutionRequests) {
                String result = handleTools(toolrequest);
                appendToChat("Tools: " + "工具["  + toolrequest.name() + toolrequest.arguments() + "] 执行中");
                ToolExecutionResultMessage toolExecutionResultMessage = ToolExecutionResultMessage.from(toolrequest, result);
                chatMemory.add(toolExecutionResultMessage);
            }
            ChatRequest request_with_tool = ChatRequest.builder()
                .messages(chatMemory.messages())
                .toolSpecifications(mergedTools)
                .build();
            ChatResponse aiResponse_with_tool = model.chat(request_with_tool);
            processAiResponse(aiResponse_with_tool);
        }

        else {
            appendNagivateResponseToChat("AI: ", aiResponse.aiMessage().text());
        }
    }
    private void processUserRequest(String userMessage) {
        try {
            chatMemory.add(UserMessage.userMessage(userMessage));
            chatWithVehicleStatus();
        } catch (Exception e) {
            appendToChat("系统: 请求失败 - " + e.getMessage());
        }
    }
    private void cleanChat() {
        mainHandler.post(() -> chatHistory.setText(""));
    }
    private void appendToChat(String message) {
        mainHandler.post(() -> {
            String current = chatHistory.getText().toString();
            mAIAgentBinder.updateRequest(message , 0);


        });
    }
    private void appendPositiveResponse(String message) {
        mainHandler.post(() -> {

            mAIAgentBinder.updatePositiveResponse(message);


        });
    }
    private void appendNagivateResponseToChat(String prefix, String message) {
        mainHandler.post(() -> {
            String current = chatHistory.getText().toString();
            mAIAgentBinder.updateNagivateResponse("\n", 0);
            //   chatHistory.setText(String.format("%s\n\n%s", current, message));
            for (int idx = 0; idx < message.length(); ++idx) {
                mAIAgentBinder.updateNagivateResponse(message.substring(idx, idx + 1), idx);
            }


        });
    }
    private void initAIAgentClient() {



        ServiceConnection conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                //绑定成功，回调这个方法
                mAIAgentBinder = (AIAgentService.AIAgentBinder) service;
                Log.e("onServiceConnected", "AIAgentService Thread: " + Thread.currentThread().getName());


            }
            @Override
            public void onServiceDisconnected(ComponentName name) {
                //如果Service中途挂掉，client端也能通过onServiceDisconnected感知到（通过Binder的linkToDeath实现）
            }

        };
        Intent intent = new Intent(getApplicationContext(), AIAgentService.class);
        getApplicationContext().startForegroundService(intent);
        bindService(new Intent(this, AIAgentService.class), conn, Context.BIND_AUTO_CREATE);
    }
}
