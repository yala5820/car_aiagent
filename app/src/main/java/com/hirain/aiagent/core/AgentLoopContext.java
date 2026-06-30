package com.hirain.aiagent.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 单次 Agent 执行的上下文。可变，不跨线程共享。
 * <p>
 * 承载本次执行中的迭代状态、额外上下文数据（scene、image_bytes 等）、
 * 安全审查结果和工具执行历史。
 */
public class AgentLoopContext {

    private final String userInput;
    private final String personaId;
    private final Map<String, Object> contextData;
    private final long startTimeMs;
    private final List<ToolExecutionRecord> toolExecutionHistory = new ArrayList<>();

    private int iteration;
    private SafetyVerdict lastSafetyVeto;

    public AgentLoopContext(String userInput, String personaId, Map<String, Object> contextData) {
        this.userInput = userInput;
        this.personaId = personaId;
        this.contextData = contextData != null
                ? new HashMap<>(contextData) : new HashMap<>();
        this.startTimeMs = System.currentTimeMillis();
    }

    // ── 读取器 ──

    public String userInput() { return userInput; }
    public String personaId() { return personaId; }
    public long startTimeMs() { return startTimeMs; }
    public int iteration() { return iteration; }
    public SafetyVerdict lastSafetyVeto() { return lastSafetyVeto; }
    public List<ToolExecutionRecord> toolExecutionHistory() {
        return Collections.unmodifiableList(toolExecutionHistory);
    }

    public Object getContextData(String key) {
        return contextData.get(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T getContextData(String key, Class<T> type) {
        Object value = contextData.get(key);
        return type.isInstance(value) ? (T) value : null;
    }

    // ── 写入器（包内可见） ──

    /** 向上下文写入额外数据（供 PostProcessor / Collector 之间传递状态） */
    public void putContextData(String key, Object value) {
        contextData.put(key, value);
    }

    void setIteration(int iteration) { this.iteration = iteration; }
    void setLastSafetyVeto(SafetyVerdict veto) { this.lastSafetyVeto = veto; }

    void addToolResult(String toolName, String arguments, String result, SafetyVerdict verdict) {
        toolExecutionHistory.add(new ToolExecutionRecord(
                toolName, arguments, result, verdict));
    }
}
