package com.hirain.aiagent.context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;

/**
 * Context 装配结果 — AgentLoop 最终用于构造 ChatRequest 的不可变输出。
 * <p>
 * 成功结果同时返回 messages、toolSpecifications、budgetReport 和 debugInfo。
 * 失败结果不得带可发送给模型的半成品列表（messages/toolSpecifications 为空列表）。
 */
public final class ContextAssemblyResult {

    private final boolean success;
    private final ContextErrorCode errorCode;
    private final String errorDetail;
    private final List<ChatMessage> messages;
    private final List<ToolSpecification> toolSpecifications;
    private final ContextBudgetReport budgetReport;
    private final ContextAssemblyDebugInfo debugInfo;
    private final boolean memoryCompacted;
    private final boolean chatMemoryReloadRequired;
    private final boolean compressionAttempted;
    private final List<ContextProviderOutcome> providerOutcomes;

    private ContextAssemblyResult(boolean success,
                                   ContextErrorCode errorCode,
                                   String errorDetail,
                                   List<ChatMessage> messages,
                                   List<ToolSpecification> toolSpecifications,
                                   ContextBudgetReport budgetReport,
                                   ContextAssemblyDebugInfo debugInfo,
                                   boolean memoryCompacted,
                                   boolean chatMemoryReloadRequired,
                                   List<ContextProviderOutcome> providerOutcomes) {
        this.success = success;
        this.errorCode = errorCode;
        this.errorDetail = errorDetail;
        this.messages = messages != null
                ? Collections.unmodifiableList(new ArrayList<>(messages))
                : List.of();
        this.toolSpecifications = toolSpecifications != null
                ? Collections.unmodifiableList(new ArrayList<>(toolSpecifications))
                : List.of();
        this.budgetReport = budgetReport;
        this.debugInfo = debugInfo;
        this.memoryCompacted = memoryCompacted;
        this.chatMemoryReloadRequired = chatMemoryReloadRequired;
        this.compressionAttempted = memoryCompacted || chatMemoryReloadRequired;
        this.providerOutcomes = providerOutcomes != null
                ? Collections.unmodifiableList(new ArrayList<>(providerOutcomes))
                : List.of();
    }

    public static ContextAssemblyResult success(List<ChatMessage> messages,
                                                  List<ToolSpecification> toolSpecifications,
                                                  ContextBudgetReport budgetReport,
                                                  ContextAssemblyDebugInfo debugInfo,
                                                  List<ContextProviderOutcome> providerOutcomes) {
        return new ContextAssemblyResult(true, null, null, messages, toolSpecifications,
                budgetReport, debugInfo, false, false, providerOutcomes);
    }

    public static ContextAssemblyResult successWithCompression(
                                                  List<ChatMessage> messages,
                                                  List<ToolSpecification> toolSpecifications,
                                                  ContextBudgetReport budgetReport,
                                                  ContextAssemblyDebugInfo debugInfo,
                                                  List<ContextProviderOutcome> providerOutcomes,
                                                  boolean memoryCompacted,
                                                  boolean chatMemoryReloadRequired) {
        return new ContextAssemblyResult(true, null, null, messages, toolSpecifications,
                budgetReport, debugInfo, memoryCompacted, chatMemoryReloadRequired, providerOutcomes);
    }

    public static ContextAssemblyResult failure(ContextErrorCode errorCode,
                                                  String errorDetail,
                                                  ContextAssemblyDebugInfo debugInfo) {
        return new ContextAssemblyResult(false, errorCode, errorDetail,
                List.of(), List.of(), null, debugInfo, false, false, List.of());
    }

    public boolean success() { return success; }
    public ContextErrorCode errorCode() { return errorCode; }
    public String errorDetail() { return errorDetail; }
    public List<ChatMessage> messages() { return messages; }
    public List<ToolSpecification> toolSpecifications() { return toolSpecifications; }
    public ContextBudgetReport budgetReport() { return budgetReport; }
    public ContextAssemblyDebugInfo debugInfo() { return debugInfo; }
    public boolean memoryCompacted() { return memoryCompacted; }
    public boolean chatMemoryReloadRequired() { return chatMemoryReloadRequired; }
    public boolean compressionAttempted() { return compressionAttempted; }
    public List<ContextProviderOutcome> providerOutcomes() { return providerOutcomes; }
}
