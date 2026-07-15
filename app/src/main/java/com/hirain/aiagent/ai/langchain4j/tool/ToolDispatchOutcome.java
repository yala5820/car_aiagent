package com.hirain.aiagent.ai.langchain4j.tool;

/**
 * 工具分发的不可变结构化结果。
 * <p>
 * 工具返回文本只负责传给模型；执行是否成功由这些字段表达，避免调用方通过中文错误文本猜测状态。
 */
public final class ToolDispatchOutcome {
    private final boolean registered;
    private final boolean argumentParseSuccess;
    private final boolean invokeSuccess;
    private final boolean dispatchSuccess;
    private final String resultText;
    private final String errorType;
    private final String errorDetail;
    private final String targetClass;
    private final String targetMethod;

    private ToolDispatchOutcome(boolean registered, boolean argumentParseSuccess,
                                boolean invokeSuccess, boolean dispatchSuccess,
                                String resultText, String errorType, String errorDetail,
                                String targetClass, String targetMethod) {
        this.registered = registered;
        this.argumentParseSuccess = argumentParseSuccess;
        this.invokeSuccess = invokeSuccess;
        this.dispatchSuccess = dispatchSuccess;
        this.resultText = resultText != null ? resultText : "";
        this.errorType = errorType;
        this.errorDetail = errorDetail;
        this.targetClass = targetClass;
        this.targetMethod = targetMethod;
    }

    public static ToolDispatchOutcome success(String resultText, String targetClass, String targetMethod) {
        return new ToolDispatchOutcome(true, true, true, true, resultText,
                null, null, targetClass, targetMethod);
    }

    public static ToolDispatchOutcome failure(boolean registered, boolean argumentParseSuccess,
                                              String resultText, String errorType, String errorDetail,
                                              String targetClass, String targetMethod) {
        return new ToolDispatchOutcome(registered, argumentParseSuccess, false, false,
                resultText, errorType, errorDetail, targetClass, targetMethod);
    }

    public boolean registered() { return registered; }
    public boolean argumentParseSuccess() { return argumentParseSuccess; }
    public boolean invokeSuccess() { return invokeSuccess; }
    public boolean dispatchSuccess() { return dispatchSuccess; }
    public String resultText() { return resultText; }
    public String errorType() { return errorType; }
    public String errorDetail() { return errorDetail; }
    public String targetClass() { return targetClass; }
    public String targetMethod() { return targetMethod; }
}
