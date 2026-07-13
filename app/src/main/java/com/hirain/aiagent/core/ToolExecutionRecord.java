package com.hirain.aiagent.core;

import com.hirain.aiagent.safety.SafetyDecision;

/**
 * 工具执行记录 — 单次工具调用的不可变快照。
 */
public class ToolExecutionRecord {

    private final String toolName;
    private final String arguments;       // JSON 参数字符串
    private final String result;          // 执行结果
    private final SafetyDecision safetyDecision;
    private final long timestampMs;

    public ToolExecutionRecord(String toolName, String arguments,
                               String result, SafetyDecision safetyDecision) {
        this.toolName = toolName;
        this.arguments = arguments;
        this.result = result;
        this.safetyDecision = safetyDecision;
        this.timestampMs = System.currentTimeMillis();
    }

    public String toolName() { return toolName; }
    public String arguments() { return arguments; }
    public String result() { return result; }
    public SafetyDecision safetyDecision() { return safetyDecision; }
    public long timestampMs() { return timestampMs; }
}
