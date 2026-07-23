package com.hirain.aiagent.core.policy;
/** Dispatch 前整批授权结果；拒绝不进入 Safety 或反射调用。 */
public record ToolAuthorizationDecision(boolean authorized, String reasonCode) {
    public static ToolAuthorizationDecision allow(){return new ToolAuthorizationDecision(true,"AUTHORIZED");}
    public static ToolAuthorizationDecision deny(String reason){return new ToolAuthorizationDecision(false,reason);}
}
