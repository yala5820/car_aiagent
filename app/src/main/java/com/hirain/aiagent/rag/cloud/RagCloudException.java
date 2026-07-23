package com.hirain.aiagent.rag.cloud;

/** 云端失败仅暴露稳定原因码；异常正文、请求体和凭证不得传入 ToolResult 或日志。 */
public final class RagCloudException extends Exception {
    private final String reasonCode;
    public RagCloudException(String reasonCode) { super(reasonCode); this.reasonCode = reasonCode; }
    public RagCloudException(String reasonCode, Throwable cause) { super(reasonCode, cause); this.reasonCode = reasonCode; }
    public String reasonCode() { return reasonCode; }
}
