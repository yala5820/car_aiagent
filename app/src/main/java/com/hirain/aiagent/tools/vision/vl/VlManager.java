package com.hirain.aiagent.tools.vision.vl;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.hirain.aiagent.BuildConfig;
import com.hirain.aiagent.prompt.PromptConstants;
import com.hirain.aiagent.prompt.PromptManager;

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
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import langchain4j.http_client_ok.OkHttpClient;
import langchain4j.http_client_ok.OkHttpClientBuilder;

public class VlManager {

    private static final String TAG = "VlManager";

    private final Context ctx;
    private final ChatModel vlModel;
    private final PromptManager promptManager;
    private byte[] mFrontImage = null;

    public VlManager(Context context, PromptManager promptManager) {
        this.ctx = context.getApplicationContext();
        this.promptManager = promptManager;
        OkHttpClientBuilder httpBuilder = OkHttpClient.builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(120));
        this.vlModel = OpenAiChatModel.builder()
                .httpClientBuilder(httpBuilder)
                .apiKey(BuildConfig.DASHSCOPE_API_KEY)
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                .modelName("qwen-vl-max")
                .build();
    }

    // ── 私有辅助 ──

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
            return Base64.encodeToString(byteOutputStream.toByteArray(), Base64.DEFAULT);
        } catch (IOException e) {
            Log.e(TAG, "Failed to read file: " + filePath, e);
            return "";
        } finally {
            if (inputStream != null) {
                try { inputStream.close(); } catch (IOException ignored) {}
            }
            if (byteOutputStream != null) {
                try { byteOutputStream.close(); } catch (IOException ignored) {}
            }
        }
    }

    public long writeFile(String path, byte[] data) {
        File file = new File(path);
        FileOutputStream out = null;
        try {
            File parent = file.getParentFile();
            if (!parent.exists()) {
                parent.mkdirs();
                file.createNewFile();
            }
            out = new FileOutputStream(file);
            out.write(data);
            return data.length;
        } catch (IOException e) {
            Log.e(TAG, "Failed to write file", e);
        } finally {
            if (out != null) {
                try { out.close(); } catch (IOException ignored) {}
            }
        }
        return 0;
    }

    // ── 工具方法 ──

    /**
     * 前向摄像头数据识别工具。
     * 当用户询问前方视野中的物体、路况或环境时调用此工具获取摄像头图像并回答。
     */
    @Tool(name = "front_camera_interaction",
          value = "前向摄像头数据识别工具。当用户询问前方视野中的物体、路况、交通标志或环境时，"
                + "通过此工具获取前置摄像头实时图像并给出回答。")
    public String frontCameraInteraction(
            @P("用户关于前方视野的文本提问，简洁清晰") String text) {
        Log.d(TAG, "frontCameraInteraction invoked");

        if (mFrontImage != null) {
            return frontCameraInteractionPositive(text, mFrontImage);
        }
        Log.d(TAG, "frontCameraInteraction: using default image");

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
            return frontCameraInteractionPositive(text, byteOutputStream.toByteArray());
        } catch (IOException e) {
            Log.e(TAG, "Failed to load default image", e);
            return "";
        } finally {
            if (inputStream != null) {
                try { inputStream.close(); } catch (IOException ignored) {}
            }
            if (byteOutputStream != null) {
                try { byteOutputStream.close(); } catch (IOException ignored) {}
            }
        }
    }

    public void frontCameraSave(String text, byte[] byteArray) {
        Log.d(TAG, "frontCameraSave: saving image data");
        this.mFrontImage = byteArray.clone();
    }

    public String frontCameraInteractionPositive(String text, byte[] byteArray) {
        String imgB64 = getBase64(ctx, byteArray);
        SystemMessage systemMsg = SystemMessage.from(
                promptManager.render(PromptConstants.TASK_FRONT_VIEW_QA));
        UserMessage userMsg = UserMessage.from(
                TextContent.from(text),
                ImageContent.from(imgB64, "image/jpeg")
        );

        String response = vlModel.chat(systemMsg, userMsg).aiMessage().text();
        String warning = promptManager.render(PromptConstants.MSG_VL_WARNING);
        return response + warning;
    }
}
