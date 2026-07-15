package com.hirain.aiagent.context;

/** 生产 sourceKey 的静态 Context 策略。 */
public final class ContextSourcePolicy {
    private final String sourceKey;
    private final ContextLifecycle lifecycle;
    private final ContextVisibility visibility;
    private final ContextTrustLevel trustLevel;
    private final ContextPriority priority;
    private final boolean required;
    private final boolean trimEligible;

    public ContextSourcePolicy(String sourceKey, ContextLifecycle lifecycle,
                               ContextVisibility visibility, ContextTrustLevel trustLevel,
                               ContextPriority priority, boolean required, boolean trimEligible) {
        this.sourceKey = sourceKey;
        this.lifecycle = lifecycle;
        this.visibility = visibility;
        this.trustLevel = trustLevel;
        this.priority = priority;
        this.required = required;
        this.trimEligible = trimEligible;
    }

    public String sourceKey() { return sourceKey; }
    public ContextLifecycle lifecycle() { return lifecycle; }
    public ContextVisibility visibility() { return visibility; }
    public ContextTrustLevel trustLevel() { return trustLevel; }
    public ContextPriority priority() { return priority; }
    public boolean required() { return required; }
    public boolean trimEligible() { return trimEligible; }
}
