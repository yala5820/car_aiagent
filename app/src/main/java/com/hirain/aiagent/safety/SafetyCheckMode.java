package com.hirain.aiagent.safety;

/**
 * Safety 规则的审核阶段。
 * <p>
 * 二次确认不能通过外部布尔值绕过规则；确认后必须再次进入同一个 Engine，
 * 由规则根据最新车辆状态决定是否放行。
 */
public enum SafetyCheckMode {
    INITIAL,
    CONFIRMED_RECHECK
}
