package com.hirain.aiagent.context;

/**
 * Context 数据的信任级别。
 * <p>
 * 区分受信系统内容、内部数据和不可信外部输入。
 * UNTRUSTED_DATA 不得进入 SystemMessage。
 */
public enum ContextTrustLevel {
    /** 仅项目控制的 Prompt 和系统规则。 */
    TRUSTED_SYSTEM,

    /** 车辆状态、系统时间等可信数据。 */
    TRUSTED_DATA,

    /** 用户长期记忆、caller extra、外部数据。 */
    UNTRUSTED_DATA
}
