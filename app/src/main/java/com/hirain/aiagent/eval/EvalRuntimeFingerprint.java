package com.hirain.aiagent.eval;

/** 不含凭证的运行时版本事实，由 Debug 层序列化。 */
public final class EvalRuntimeFingerprint {
    private final String textModel;
    private final String traceContentMode;
    public EvalRuntimeFingerprint(String textModel, String traceContentMode) {
        this.textModel = textModel;
        this.traceContentMode = traceContentMode;
    }
    public String getTextModel() { return textModel; }
    public String getTraceContentMode() { return traceContentMode; }
}
