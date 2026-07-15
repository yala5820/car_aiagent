package com.hirain.aiagent.context;

/**
 * Context 预算资格规则。
 * <p>
 * Token 估算和逐项重装配由 {@link ContextMessageAssembler} 管理；本类只负责根据
 * 集中 Policy 判断 Contribution 是否允许被预算裁剪，避免重新引入消息索引裁剪。
 */
public final class ContextBudgetManager {

    private ContextBudgetManager() {}

    /** 集中判断一次预算尝试是否允许删除该 Contribution。 */
    public static boolean isTrimEligible(ContextContribution contribution) {
        if (!(contribution instanceof TextContextContribution text)) return false;
        boolean policyAllowsTrim = ContextPolicies.isRegistered(contribution.sourceKey())
                ? ContextPolicies.source(contribution.sourceKey()).trimEligible()
                : !contribution.required();
        return TextContextContribution.TARGET_CONTEXT_DATA.equals(text.targetArea())
                && policyAllowsTrim
                && !contribution.required()
                && contribution.visibility() == ContextVisibility.MODEL_VISIBLE
                && contribution.priority() != ContextPriority.CRITICAL
                && !ContextPolicies.SESSION_MEMORY_SUMMARY.equals(contribution.sourceKey());
    }
}
