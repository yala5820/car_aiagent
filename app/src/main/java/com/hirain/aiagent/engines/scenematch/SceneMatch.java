package com.hirain.aiagent.engines.scenematch;

import static dev.langchain4j.model.chat.request.ResponseFormatType.JSON;

import java.time.Duration;
import org.json.JSONException;
import org.json.JSONObject;
import android.util.Log;

import com.hirain.aiagent.BuildConfig;
import com.hirain.aiagent.prompt.PromptConstants;
import com.hirain.aiagent.prompt.PromptManager;

import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import langchain4j.http_client_ok.OkHttpClientBuilder;

public class SceneMatch {
    private String TAG = "SceneService";
    private final ChatModel vlModel;
    private final PromptManager promptManager;
    private final ResponseFormat respFmt = ResponseFormat.builder()
            .type(JSON)
            .jsonSchema(JsonSchema.builder()
                    .name("Scene")
                    .rootElement(JsonObjectSchema.builder()
                            .addStringProperty("name", "场景名称，必须为以下精确值之一：'舱外浓烟'、'雨雪天气'、'工程施工'、'乘客休息'、'驾驶员疲劳'、'其他'。")
                            .addStringProperty("description", "结合识别到的场景，对图片中内容进行详细描述、并提供与当前场景匹配的座舱相关舒适调节建议与安全驾驶相关建议。")
                            .required("name", "description")
                            .build())
                    .build())
            .build();
    public SceneMatch(PromptManager promptManager) {
        OkHttpClientBuilder okHttpClientBuilder = langchain4j.http_client_ok.OkHttpClient.builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(120));
        this.promptManager = promptManager;
        this.vlModel = OpenAiChatModel.builder()
                .httpClientBuilder(okHttpClientBuilder)
                .apiKey(BuildConfig.DASHSCOPE_API_KEY)
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                .modelName("qwen3-vl-plus")
                .build();
    }
    public static class Scene {
        public String name;
        public String description;
        public Scene(String scene_name, String scene_description) {
            if (scene_name.equals("舱外浓烟") ||
                scene_name.equals("雨雪天气") ||
                scene_name.equals("工程施工") ||
                scene_name.equals("乘客休息") ||
                scene_name.equals("驾驶员疲劳")) {
                name = scene_name;
            } else {
                name = "其他";
            }
            description = scene_description;
        }
        public String to_string() {
            try {
                JSONObject json_ = new JSONObject();
                json_.put("场景名称", name);
                json_.put("场景描述", description);
                return json_.toString();
            } catch (JSONException e) {
                return "无效的场景信息。";
            }
        }
    }
    public Scene vl_scene_match(String img_b64, String mimeType) {
        SystemMessage system = SystemMessage.from(
                promptManager.render(PromptConstants.TASK_SCENE_RECOGNITION));
        UserMessage usrmsg = UserMessage.from(
                TextContent.from("请根据以下图片识别场景："),
                ImageContent.from(img_b64, mimeType)
        );
        ChatRequest chatReq = ChatRequest.builder()
                .responseFormat(respFmt)
                .messages(system, usrmsg)
                .build();
        ChatResponse aiResponse = vlModel.chat(chatReq);
        try {
            JSONObject resp_json = new JSONObject(aiResponse.aiMessage().text());
            Log.d("SceneService", aiResponse.aiMessage().text());
            return new Scene(resp_json.getString("name"), resp_json.getString("description"));
        } catch (JSONException e) {
            return new Scene("其他", e.toString());
        }
    }
}
