package com.hirain.aiagent.trace;

import android.util.Log;

import java.io.IOException;

import io.opentelemetry.api.trace.Span;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * OkHttp 拦截器 — 在 LLM HTTP 调用级别记录 span。
 * <p>
 * 使用方式：
 * 在 {@code OkHttpClient.builder().addInterceptor(new TracingOkHttpInterceptor())}
 * 中注册。无需额外配置，全局生效。
 * <p>
 * 由于 OpenTelemetry Java SDK 使用 {@code Context.current()} 传播 span，
 * 此拦截器会自动绑定到调用线程的活跃 span 上（如果有 TraceSession.startLlmSpan
 * 在当前线程活跃）。如果无活跃 span，则不记录。
 */
public class TracingOkHttpInterceptor implements Interceptor {

    private static final String TAG = "TracingInterceptor";

    @Override
    public Response intercept(Chain chain) throws IOException {
        Span currentSpan = Span.current();
        boolean hasSpan = currentSpan.isRecording();

        Request request = chain.request();
        long startNs = System.nanoTime();

        Response response;
        try {
            response = chain.proceed(request);
        } catch (IOException e) {
            if (hasSpan) {
                currentSpan.recordException(e);
                currentSpan.setAttribute("http.status_code", 0);
            }
            throw e;
        }

        long durationMs = (System.nanoTime() - startNs) / 1_000_000;

        if (hasSpan) {
            currentSpan.setAttribute("http.status_code", response.code());
            currentSpan.setAttribute("http.duration_ms", durationMs);
            currentSpan.setAttribute("http.url", request.url().toString());
            currentSpan.setAttribute("http.method", request.method());
        }

        Log.d(TAG, request.url().encodedPath() + " -> " + response.code()
                + " (" + durationMs + "ms)");
        return response;
    }
}
