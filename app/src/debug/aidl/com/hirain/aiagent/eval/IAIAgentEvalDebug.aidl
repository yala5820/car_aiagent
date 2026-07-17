package com.hirain.aiagent.eval;

/** Debug 专用环境控制接口；不得加入主业务 AIDL。 */
interface IAIAgentEvalDebug {
    String execute(String requestJson);
}
