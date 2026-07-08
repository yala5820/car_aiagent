package com.hirain.aiagent.context;

/**
 * Context 构建过程中不可恢复的异常。
 * <p>
 * 携带 providerName 和 reason，便于 ContextOrchestrator 记录诊断信息。
 * 继承 RuntimeException，不符合 ContextProvider 声明时需要被自然传播。
 */
public class ContextBuildException extends RuntimeException {

    private final String providerName;
    private final String reason;

    public ContextBuildException(String providerName, String reason) {
        super(providerName + ": " + reason);
        this.providerName = providerName;
        this.reason = reason;
    }

    public String providerName() { return providerName; }
    public String reason() { return reason; }
}
