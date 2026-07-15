package com.hirain.aiagent.context;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 文本 Contribution — 表达 System Prompt 或 Context Data 文本及其目标消息区域。
 * <p>
 * 不直接创建 LangChain4j 消息，转换由 {@code ContextMessageAssembler} 负责。
 * targetArea 取值 "SYSTEM" 或 "CONTEXT_DATA"。
 */
public final class TextContextContribution implements ContextContribution {

    public static final String TARGET_SYSTEM = "SYSTEM";
    public static final String TARGET_CONTEXT_DATA = "CONTEXT_DATA";

    private final String sourceKey;
    private final ContextVisibility visibility;
    private final ContextTrustLevel trustLevel;
    private final ContextPriority priority;
    private final ContextLifecycle lifecycle;
    private final boolean required;
    private final String providerName;
    private final String targetArea;
    private final String content;
    private final Map<String, Object> metadata;

    public TextContextContribution(String sourceKey,
                                    ContextVisibility visibility,
                                    ContextTrustLevel trustLevel,
                                    ContextPriority priority,
                                    ContextLifecycle lifecycle,
                                    boolean required,
                                    String providerName,
                                    String targetArea,
                                    String content,
                                    Map<String, Object> metadata) {
        this.sourceKey = sourceKey;
        this.visibility = visibility;
        this.trustLevel = trustLevel;
        this.priority = priority;
        this.lifecycle = lifecycle;
        this.required = required;
        this.providerName = providerName;
        this.targetArea = targetArea;
        this.content = content != null ? content : "";
        this.metadata = Collections.unmodifiableMap(new HashMap<>(
                metadata != null ? metadata : Map.of()));
    }

    public TextContextContribution(ResolvedContextPolicy policy,
                                   String providerName, String targetArea,
                                   String content, Map<String, Object> metadata) {
        this(policy.sourceKey(), policy.visibility(), policy.trustLevel(), policy.priority(),
                policy.lifecycle(), policy.required(), providerName, targetArea, content, metadata);
    }

    @Override
    public String sourceKey() { return sourceKey; }

    @Override
    public ContextVisibility visibility() { return visibility; }

    @Override
    public ContextTrustLevel trustLevel() { return trustLevel; }

    @Override
    public ContextPriority priority() { return priority; }

    @Override
    public ContextLifecycle lifecycle() { return lifecycle; }

    @Override
    public boolean required() { return required; }

    @Override
    public String providerName() { return providerName; }

    @Override
    public Map<String, Object> metadata() { return metadata; }

    /** 目标消息区域：SYSTEM（进入 SystemMessage）或 CONTEXT_DATA（进入 Context Data UserMessage）。 */
    public String targetArea() { return targetArea; }

    /** 文本内容。 */
    public String content() { return content; }
}
