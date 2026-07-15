package com.hirain.aiagent.context;

import com.hirain.aiagent.runtime.RequestSession;
import com.hirain.aiagent.toolgroup.ToolGroupSelectionResult;
import com.hirain.aiagent.toolgroup.ToolGroupSelectionStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Demo TEXT Context 的集中式 source policy 注册表。
 * <p>
 * 这里只解析上下文资格，不读取 Memory、车辆、Prompt 或 ToolRegistry 业务数据。
 */
public final class ContextPolicies {
    public static final String RUNTIME = "runtime";
    public static final String PERSONA = "persona";
    public static final String PROMPT = "prompt";
    public static final String CURRENT_USER = "current_user";
    public static final String INTENT = "intent";
    public static final String TOOL_GROUP = "tool_group";
    public static final String LONG_TERM_MEMORY = "long_term_memory";
    public static final String CALLER_EXTRA = "caller_extra";
    public static final String SESSION_MEMORY_SUMMARY = "session_memory_summary";
    public static final String SESSION_MEMORY = "session_memory";
    public static final String VEHICLE_STATE = "vehicle_state";
    public static final String TIME = "time";

    private static final Map<String, ContextSourcePolicy> POLICIES = buildPolicies();

    private ContextPolicies() {}

    public static ResolvedContextPolicy resolve(String sourceKey, RequestSession session,
                                                ContextBuildInput input) {
        ContextSourcePolicy policy = POLICIES.get(sourceKey);
        if (policy == null) {
            throw new IllegalArgumentException("Unknown production context sourceKey: " + sourceKey);
        }
        boolean required = policy.required();
        ContextVisibility visibility = policy.visibility();
        if (TOOL_GROUP.equals(sourceKey)) {
            ToolGroupSelectionResult selection = session != null
                    ? session.toolGroupSelectionResult() : null;
            required = selection != null
                    && selection.status() == ToolGroupSelectionStatus.SELECTED;
        } else if (VEHICLE_STATE.equals(sourceKey)) {
            ToolGroupSelectionResult selection = session != null
                    ? session.toolGroupSelectionResult() : null;
            required = selection != null
                    && selection.requiredContextKeys().contains("vehicle_status");
            visibility = required ? ContextVisibility.MODEL_VISIBLE : ContextVisibility.POLICY_ONLY;
        }
        return new ResolvedContextPolicy(policy, visibility, required);
    }

    public static ContextSourcePolicy source(String sourceKey) {
        ContextSourcePolicy policy = POLICIES.get(sourceKey);
        if (policy == null) throw new IllegalArgumentException("Unknown context sourceKey: " + sourceKey);
        return policy;
    }

    public static boolean isRegistered(String sourceKey) {
        return POLICIES.containsKey(sourceKey);
    }

    private static Map<String, ContextSourcePolicy> buildPolicies() {
        Map<String, ContextSourcePolicy> map = new LinkedHashMap<>();
        register(map, RUNTIME, ContextLifecycle.REQUEST_STATIC, ContextVisibility.POLICY_ONLY,
                ContextTrustLevel.TRUSTED_DATA, ContextPriority.CRITICAL, true, false);
        register(map, PERSONA, ContextLifecycle.REQUEST_STATIC, ContextVisibility.POLICY_ONLY,
                ContextTrustLevel.TRUSTED_DATA, ContextPriority.NORMAL, false, false);
        register(map, PROMPT, ContextLifecycle.REQUEST_STATIC, ContextVisibility.MODEL_VISIBLE,
                ContextTrustLevel.TRUSTED_SYSTEM, ContextPriority.CRITICAL, true, false);
        register(map, CURRENT_USER, ContextLifecycle.REQUEST_STATIC, ContextVisibility.MODEL_VISIBLE,
                ContextTrustLevel.TRUSTED_DATA, ContextPriority.CRITICAL, true, false);
        register(map, INTENT, ContextLifecycle.REQUEST_STATIC, ContextVisibility.POLICY_ONLY,
                ContextTrustLevel.TRUSTED_DATA, ContextPriority.OPTIONAL, false, false);
        register(map, TOOL_GROUP, ContextLifecycle.REQUEST_STATIC, ContextVisibility.MODEL_VISIBLE,
                ContextTrustLevel.TRUSTED_SYSTEM, ContextPriority.CRITICAL, true, false);
        register(map, LONG_TERM_MEMORY, ContextLifecycle.REQUEST_STATIC, ContextVisibility.MODEL_VISIBLE,
                ContextTrustLevel.UNTRUSTED_DATA, ContextPriority.NORMAL, false, true);
        register(map, CALLER_EXTRA, ContextLifecycle.REQUEST_STATIC, ContextVisibility.MODEL_VISIBLE,
                ContextTrustLevel.UNTRUSTED_DATA, ContextPriority.OPTIONAL, false, true);
        register(map, SESSION_MEMORY_SUMMARY, ContextLifecycle.ITERATION_DYNAMIC,
                ContextVisibility.MODEL_VISIBLE, ContextTrustLevel.UNTRUSTED_DATA,
                ContextPriority.HIGH, true, false);
        register(map, SESSION_MEMORY, ContextLifecycle.ITERATION_DYNAMIC,
                ContextVisibility.MODEL_VISIBLE, ContextTrustLevel.TRUSTED_DATA,
                ContextPriority.HIGH, true, false);
        register(map, VEHICLE_STATE, ContextLifecycle.ITERATION_DYNAMIC,
                ContextVisibility.POLICY_ONLY, ContextTrustLevel.TRUSTED_DATA,
                ContextPriority.NORMAL, false, false);
        register(map, TIME, ContextLifecycle.ITERATION_DYNAMIC, ContextVisibility.MODEL_VISIBLE,
                ContextTrustLevel.TRUSTED_DATA, ContextPriority.OPTIONAL, false, true);
        return Map.copyOf(map);
    }

    private static void register(Map<String, ContextSourcePolicy> map, String key,
                                 ContextLifecycle lifecycle, ContextVisibility visibility,
                                 ContextTrustLevel trust, ContextPriority priority,
                                 boolean required, boolean trimEligible) {
        map.put(key, new ContextSourcePolicy(key, lifecycle, visibility, trust,
                priority, required, trimEligible));
    }
}
