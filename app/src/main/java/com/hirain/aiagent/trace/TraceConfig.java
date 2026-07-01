package com.hirain.aiagent.trace;

import java.time.Duration;

/**
 * Trace 集中配置 — 所有 trace 参数在此定义。
 * <p>
 * 通过 {@link #development()} 或 {@link #production()} 预设构造，
 * 在 {@code AIAgentService.onCreate()} 中初始化。
 */
public final class TraceConfig {

    private final boolean enabled;
    private final String serviceName;
    private final String serviceVersion;
    private final String otlpEndpoint;
    private final int batchSize;
    private final Duration batchTimeout;
    private final boolean redactSensitive;

    private TraceConfig(Builder b) {
        this.enabled = b.enabled;
        this.serviceName = b.serviceName;
        this.serviceVersion = b.serviceVersion;
        this.otlpEndpoint = b.otlpEndpoint;
        this.batchSize = b.batchSize;
        this.batchTimeout = b.batchTimeout;
        this.redactSensitive = b.redactSensitive;
    }

    // ── 读取器 ──

    public boolean enabled() { return enabled; }
    public String serviceName() { return serviceName; }
    public String serviceVersion() { return serviceVersion; }
    public String otlpEndpoint() { return otlpEndpoint; }
    public int batchSize() { return batchSize; }
    public Duration batchTimeout() { return batchTimeout; }
    public boolean redactSensitive() { return redactSensitive; }

    // ── 预设 ──

    public static TraceConfig development(String version) {
        return builder()
                .enabled(true)
                .serviceName("aiagent-android")
                .serviceVersion(version)
                .otlpEndpoint("http://localhost:6006/v1/traces")
                .batchSize(64)
                .batchTimeout(Duration.ofSeconds(2))
                .redactSensitive(true)
                .build();
    }

    public static TraceConfig production() {
        return builder().enabled(false).build();
    }

    // ── Builder ──

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private boolean enabled = false;
        private String serviceName = "aiagent-android";
        private String serviceVersion = "";
        private String otlpEndpoint = "http://10.0.2.2:6006/v1/traces";
        private int batchSize = 256;
        private Duration batchTimeout = Duration.ofSeconds(5);
        private boolean redactSensitive = true;

        public Builder enabled(boolean v) { this.enabled = v; return this; }
        public Builder serviceName(String v) { this.serviceName = v; return this; }
        public Builder serviceVersion(String v) { this.serviceVersion = v; return this; }
        public Builder otlpEndpoint(String v) { this.otlpEndpoint = v; return this; }
        public Builder batchSize(int v) { this.batchSize = v; return this; }
        public Builder batchTimeout(Duration v) { this.batchTimeout = v; return this; }
        public Builder redactSensitive(boolean v) { this.redactSensitive = v; return this; }
        public TraceConfig build() { return new TraceConfig(this); }
    }
}
