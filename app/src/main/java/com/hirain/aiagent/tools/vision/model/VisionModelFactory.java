package com.hirain.aiagent.tools.vision.model;

import com.hirain.aiagent.BuildConfig;
import com.hirain.aiagent.runtime.RequestCallRegistry;
import com.hirain.aiagent.trace.TracingOkHttpInterceptor;
import java.time.Duration;
import dev.langchain4j.model.openai.OpenAiChatModel;
import langchain4j.http_client_ok.OkHttpClient;
import langchain4j.http_client_ok.OkHttpClientBuilder;

/** 与 TEXT 模型一致地接入 deadline/cancel/trace 的 qwen-vl-max 工厂。 */
public final class VisionModelFactory {
    private VisionModelFactory() {}
    public static OpenAiChatModel create(RequestCallRegistry calls) {
        OkHttpClientBuilder http = OkHttpClient.builder()
                .connectTimeout(Duration.ofSeconds(30)).readTimeout(Duration.ofSeconds(60));
        http.requestCallRegistry(calls);
        http.httpClientBuilder().addInterceptor(new TracingOkHttpInterceptor());
        return OpenAiChatModel.builder().httpClientBuilder(http)
                .apiKey(BuildConfig.DASHSCOPE_API_KEY)
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                .modelName("qwen-vl-max").temperature(0.0).maxTokens(800).build();
    }
}
