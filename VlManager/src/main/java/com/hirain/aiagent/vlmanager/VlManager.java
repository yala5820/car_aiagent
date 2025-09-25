package com.hirain.aiagent.vlmanager;

import static androidx.fragment.app.FragmentManager.TAG;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;

import langchain4j.http_client_ok.*;
public class VlManager {
    private Context ctx;
    private ChatModel vlModel;
    private byte[] mFrontImage = null;
    private final String front_camera_system_msg =
        "你是一个运行在智能座舱中的多模态视觉问答助手，名为'窗景随问'。"
        + "你的任务是根据车辆前置/舱内摄像头实时拍摄的画面（图像模态）和驾驶员的自然语言提问（文本模态），"
        + "在单轮交互中准确、简洁、安全地回答关于前方和车内视野内可见物体、场景、交通状况或环境信息的问题。\n"
        + "请遵循一下原则响应：\n"
        + "1. 以图像内容为核心依据。\n"
        + "2. 聚焦前方视野：仅关注前置/舱内摄像头拍摄的道路及周边可见区域（如车道、车辆、行人、交通标志、信号灯、建筑物、天气状况等）\n"
        + "3. 安全优先：避免提供可能分散驾驶注意力的冗长或无关信息。"
        + "4. 不确定时诚实回应：若图像模糊、目标不清晰或问题超出视觉理解能力，回应‘看不太清楚’或‘当前画面中未发现相关对象’。"
        + "5. 不支持多轮记忆：每次提问均为独立交互，无需记忆上下文。";

    public VlManager(Context context) {
        ctx = context.getApplicationContext();
        OkHttpClientBuilder okHttpClientBuilder = langchain4j.http_client_ok.OkHttpClient.builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(120));
        this.vlModel = OpenAiChatModel.builder()
                .httpClientBuilder(okHttpClientBuilder)
                .apiKey("sk-11129fb7941f49dbb083039a93a160bc")
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                .modelName("qwen-vl-max")
                .build();
    }
    private String getBase64(Context context, byte[] byteArray) {

        return Base64.encodeToString(byteArray, Base64.DEFAULT);
    }
    private String getBase64(Context context, String filePath) {
        InputStream inputStream = null;
        ByteArrayOutputStream byteOutputStream = null;
        try {
            inputStream = context.getAssets().open(filePath);
            byteOutputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[500 * 1024];
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                byteOutputStream.write(buffer, 0, len);
            }

            byte[] bytes = byteOutputStream.toByteArray();

            return Base64.encodeToString(bytes, Base64.DEFAULT);

        } catch (IOException e) {
            e.printStackTrace();
            return "";
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (byteOutputStream != null) {
                try {
                    byteOutputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
    public long writeFile(String path, byte[] data) {
        File file = new File(path);

        FileOutputStream out = null;
        try {
            File fileParent = file.getParentFile();
            if (!fileParent.exists()) {
                boolean isMkdirs = fileParent.mkdirs();
                boolean isNewFile = file.createNewFile();
                if (isMkdirs & isNewFile) {
                    Log.d(TAG,"create new file success");
                }
            }

            out = new FileOutputStream(file);
            out.write(data);
            out.close();
            return data.length;
        } catch (IOException ex) {
            Log.d(TAG, "Failed to write data " + ex);
        } finally {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (IOException ex) {
                Log.d(TAG, "Failed to close file after write " + ex);
            }
        }
        return 0;
    }
    @Tool("用于解决车主提出的前方视野相关问题，该工具可以获取前置舱外摄像头实时图像数据，并根据图像数据与车主的文本输入，给出车主回应。")
    public String front_camera_interaction(@P(value = "经过处理后的车主文本输入，尽量简洁清晰")String text) {

        Log.d(TAG, "front_camera_interaction xxxxxxxxxyyyywwwwwwwwwwwwwwwwwwwwwwwwww");
        if (mFrontImage != null) {
            //writeFile("/sdcard/Android/data/com.hirain.aiagent/files/xxx.jpg", mFrontImage);
            return front_camera_interactionPositive(text, mFrontImage);
        }
        Log.d(TAG, "front_camera_interaction use audi image");

        InputStream inputStream = null;
        ByteArrayOutputStream byteOutputStream = null;
        try {
            inputStream = ctx.getAssets().open("documents/audi.jpg");
            byteOutputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[500 * 1024];
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                byteOutputStream.write(buffer, 0, len);
            }

            byte[] bytes = byteOutputStream.toByteArray();

            return front_camera_interactionPositive(text, bytes);

        } catch (IOException e) {
            e.printStackTrace();
            return "";
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (byteOutputStream != null) {
                try {
                    byteOutputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }


    }

    public void front_camera_save(@P(value = "经过处理后的车主文本输入，尽量简洁清晰")String text, byte[] byteArray) {
        Log.d("TAG", "front_camera_save end aaaa");

        mFrontImage = byteArray.clone();
    }
    public String front_camera_interactionPositive(@P(value = "经过处理后的车主文本输入，尽量简洁清晰")String text, byte[] byteArray) {

        Log.d("TAG", "front_camera_interactionPositive end ccccccccccccccccccc");

        String img_b64 = getBase64(ctx, byteArray);
        SystemMessage systemmsg = SystemMessage.from(front_camera_system_msg);
           //     Log.d("TAG", "front_camera_interaction text = " + text);

                UserMessage usrmsg = UserMessage.from(
                TextContent.from(text),
                ImageContent.from(img_b64, "image/jpeg")
        );
        Log.d("TAG", "front_camera_interactionPositive tool ccccccccccccc imagesize1 = " +  byteArray.length);
        ChatResponse aiResponse = vlModel.chat(systemmsg, usrmsg);
        Log.d("TAG", "front_camera_interactionPositive end ccccccccccccccccccc");

        return aiResponse.aiMessage().text();

    }
    public boolean hasTool(String toolname) {
        return toolname.equals("front_camera_interaction");
    }
    public String handleToolRequest(ToolExecutionRequest request) {
        try {
            JSONObject json = new JSONObject(request.arguments());
            if (request.name().equals("front_camera_interaction")) {
                return front_camera_interaction(json.getString("arg0"));
            } else {
                return "无效的工具请求。";
            }
        } catch (JSONException e) {
            return "无效的工具请求。";
        }
    }
}
