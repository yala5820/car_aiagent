package com.hirain.aiagent.trace;

import android.util.Log;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.util.concurrent.TimeUnit;

/**
 * Trace 管理器 — 业务代码与 OpenTelemetry SDK 之间的唯一交互点。
 * <p>
 * 职责：
 * <ul>
 *   <li>初始化/关闭 OpenTelemetry SDK</li>
 *   <li>提供 session 创建和生命周期管理</li>
 *   <li>trace 失败不抛异常，不阻塞业务</li>
 * </ul>
 * <p>
 * 当 {@link TraceConfig#enabled()} 为 false 时，所有操作变为 no-op。
 */
public class TraceManager {

    private static final String TAG = "TraceManager";

    private final TraceConfig config;
    private final OpenTelemetry openTelemetry;
    private final Tracer tracer;
    private final boolean enabled;

    public TraceManager(TraceConfig config) {
        this.config = config;
        this.enabled = config.enabled();

        if (enabled) {
            try {
                Resource resource = Resource.getDefault().merge(
                        Resource.create(Attributes.builder()
                                .put("service.name", config.serviceName())
                                .put("service.version", config.serviceVersion())
                                .build()));

                SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                        .addSpanProcessor(BatchSpanProcessor.builder(
                                        OtlpHttpSpanExporter.builder()
                                                .setEndpoint(config.otlpEndpoint())
                                                .setTimeout(config.batchTimeout().toMillis(), TimeUnit.MILLISECONDS)
                                                .build())
                                .setMaxExportBatchSize(config.batchSize())
                                .setScheduleDelay(config.batchTimeout().toMillis() / 2, TimeUnit.MILLISECONDS)
                                .build())
                        .setResource(resource)
                        .build();

                this.openTelemetry = OpenTelemetrySdk.builder()
                        .setTracerProvider(tracerProvider)
                        .build();
                this.tracer = openTelemetry.getTracer("com.hirain.aiagent");
                Log.d(TAG, "TraceManager initialized, endpoint=" + config.otlpEndpoint());
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize OpenTelemetry, falling back to no-op", e);
                this.openTelemetry = OpenTelemetry.noop();
                this.tracer = openTelemetry.getTracer("noop");
            }
        } else {
            this.openTelemetry = OpenTelemetry.noop();
            this.tracer = openTelemetry.getTracer("noop");
            Log.d(TAG, "TraceManager disabled");
        }
    }

    /**
     * 创建一个新的 Trace session（对应一次用户请求）。
     *
     * @param personaId 人格标识（chat / scene / vision_qa）
     * @param userId    用户标识
     * @param userInput 用户文本输入
     * @return TraceSession（可关闭）
     */
    public TraceSession startSession(String personaId, String userId, String userInput) {
        Span rootSpan = tracer.spanBuilder("agent." + personaId)
                .setSpanKind(io.opentelemetry.api.trace.SpanKind.SERVER)
                .startSpan();

        rootSpan.setAttribute("session.persona", personaId);
        rootSpan.setAttribute("user.id", userId);

        if (userInput != null && !userInput.isEmpty()) {
            String input = userInput.length() > 500
                    ? userInput.substring(0, 500) + "…"
                    : userInput;
            rootSpan.setAttribute("user.input", input);
        }

        TraceSession session = new TraceSession(rootSpan, tracer);
        Log.d(TAG, "Session started: traceId=" + session.traceId()
                + " persona=" + personaId);
        return session;
    }

    /** 关闭 SDK，刷新剩余 buffer 中的 span */
    public void shutdown() {
        if (enabled && openTelemetry instanceof OpenTelemetrySdk) {
            try {
                ((OpenTelemetrySdk) openTelemetry)
                        .getSdkTracerProvider()
                        .shutdown()
                        .join(5000);
                Log.d(TAG, "TraceManager shut down");
            } catch (Exception e) {
                Log.w(TAG, "TraceManager shutdown failed", e);
            }
        }
    }

    public TraceConfig config() { return config; }
    public boolean isEnabled() { return enabled; }
}
