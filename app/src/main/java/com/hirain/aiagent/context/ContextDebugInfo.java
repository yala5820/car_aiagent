package com.hirain.aiagent.context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Context 构建调试信息 — 汇总 provider 状态、fallback、错误和构建耗时。
 * <p>
 * 设计原因：将 ContextOrchestrator 构建过程中的诊断信息集中管理，
 * 不包含构建后的 {@link ContextFrame} 数据。
 */
public final class ContextDebugInfo {

    private final List<String> providerNames;
    private final List<String> fallbackProviders;
    private final Map<String, String> providerErrors;
    private final int sectionCount;
    private final boolean fallbackUsed;
    private final long buildMs;

    public ContextDebugInfo(List<String> providerNames,
                            List<String> fallbackProviders,
                            Map<String, String> providerErrors,
                            int sectionCount,
                            boolean fallbackUsed,
                            long buildMs) {
        this.providerNames = Collections.unmodifiableList(new ArrayList<>(
                providerNames != null ? providerNames : List.of()));
        this.fallbackProviders = Collections.unmodifiableList(new ArrayList<>(
                fallbackProviders != null ? fallbackProviders : List.of()));
        this.providerErrors = Collections.unmodifiableMap(new HashMap<>(
                providerErrors != null ? providerErrors : Map.of()));
        this.sectionCount = sectionCount;
        this.fallbackUsed = fallbackUsed;
        this.buildMs = buildMs;
    }

    public List<String> providerNames() { return providerNames; }
    public List<String> fallbackProviders() { return fallbackProviders; }
    public Map<String, String> providerErrors() { return providerErrors; }
    public int sectionCount() { return sectionCount; }
    public boolean fallbackUsed() { return fallbackUsed; }
    public long buildMs() { return buildMs; }
}
