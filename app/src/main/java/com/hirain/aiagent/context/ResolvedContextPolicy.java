package com.hirain.aiagent.context;

/** 单次请求解析后的不可变 Context 策略。 */
public final class ResolvedContextPolicy {
    private final ContextSourcePolicy source;
    private final ContextVisibility visibility;
    private final boolean required;

    ResolvedContextPolicy(ContextSourcePolicy source, ContextVisibility visibility, boolean required) {
        this.source = source;
        this.visibility = visibility;
        this.required = required;
    }

    public String sourceKey() { return source.sourceKey(); }
    public ContextLifecycle lifecycle() { return source.lifecycle(); }
    public ContextVisibility visibility() { return visibility; }
    public ContextTrustLevel trustLevel() { return source.trustLevel(); }
    public ContextPriority priority() { return source.priority(); }
    public boolean required() { return required; }
    public boolean trimEligible() { return source.trimEligible() && !required; }
}
