package com.hirain.aiagent.trace;

import android.util.Log;

import java.io.IOException;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
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
                long durationMs = durationMsSince(startNs);
                recordRequestAttributes(currentSpan, request, durationMs);
                currentSpan.recordException(e);
                currentSpan.setStatus(StatusCode.ERROR, e.getMessage());
                currentSpan.setAttribute(TraceAttributeKeys.ERROR_TYPE, e.getClass().getName());
                currentSpan.setAttribute(TraceAttributeKeys.ERROR_MESSAGE, e.getMessage());
            }
            throw e;
        }

        long durationMs = durationMsSince(startNs);

        if (hasSpan) {
            recordRequestAttributes(currentSpan, request, durationMs);
            currentSpan.setAttribute(TraceAttributeKeys.HTTP_RESPONSE_STATUS_CODE, response.code());
            if (response.code() >= 400) {
                currentSpan.setStatus(StatusCode.ERROR, response.message());
                currentSpan.setAttribute(TraceAttributeKeys.ERROR_TYPE, String.valueOf(response.code()));
                currentSpan.setAttribute(TraceAttributeKeys.ERROR_MESSAGE, response.message());
            }
        }

        safeLogD(request.url().encodedPath() + " -> " + response.code()
                + " (" + durationMs + "ms)");
        return response;
    }

    private static void recordRequestAttributes(Span span, Request request, long durationMs) {
        span.setAttribute(TraceAttributeKeys.HTTP_DURATION_MS, durationMs);
        span.setAttribute(TraceAttributeKeys.URL_FULL, request.url().toString());
        span.setAttribute(TraceAttributeKeys.SERVER_ADDRESS, request.url().host());
        span.setAttribute(TraceAttributeKeys.HTTP_REQUEST_METHOD, request.method());
    }

    private static long durationMsSince(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000;
    }

    private static void safeLogD(String message) {
        try {
            Log.d(TAG, message);
        } catch (RuntimeException ignored) {
            // JVM 单测环境没有 Android Log 实现，日志失败不能影响 HTTP trace 主流程。
        }
    }
}
