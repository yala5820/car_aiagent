package com.hirain.aiagent.trace;

import java.util.HashMap;
import java.util.Map;

/**
 * 轻量 Trace 上下文 — 在 AgentLoopContext.extraContext 中传递 trace 信息。
 * <p>
 * 仅包含 traceId 和 sessionSpanId 字符串，不做 span 管理。
 * 业务代码通过此对象判断 trace 是否活跃。
 */
public class TraceContext {

    public static final String TRACE_CONTEXT_KEY = "_trace_context";

    private final String traceId;
    private final String sessionSpanId;

    public TraceContext(String traceId, String sessionSpanId) {
        this.traceId = traceId;
        this.sessionSpanId = sessionSpanId;
    }

    public String traceId() { return traceId; }
    public String sessionSpanId() { return sessionSpanId; }

    /** trace 是否活跃（traceId 非空） */
    public boolean isActive() {
        return traceId != null && !traceId.isEmpty()
                && !"00000000000000000000000000000000".equals(traceId);
    }

    /** 注入到 extraContext Map */
    public Map<String, Object> toContextData() {
        Map<String, Object> data = new HashMap<>();
        data.put(TRACE_CONTEXT_KEY, this);
        return data;
    }
}
