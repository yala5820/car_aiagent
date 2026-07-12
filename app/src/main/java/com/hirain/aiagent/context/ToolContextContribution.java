package com.hirain.aiagent.context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.langchain4j.agent.tool.ToolSpecification;

/**
 * 工具 Contribution — 承载不可变 LangChain4j ToolSpecification 列表。
 * <p>
 * selectionMode 标识工具来源：SELECTED（明确选中）、ALL_FALLBACK（全量兜底）、NONE（无工具）。
 */
public final class ToolContextContribution implements ContextContribution {

    public static final String MODE_SELECTED = "SELECTED";
    public static final String MODE_ALL_FALLBACK = "ALL_FALLBACK";
    public static final String MODE_NONE = "NONE";

    private final String sourceKey;
    private final ContextVisibility visibility;
    private final ContextTrustLevel trustLevel;
    private final ContextPriority priority;
    private final ContextLifecycle lifecycle;
    private final boolean required;
    private final String providerName;
    private final String selectionMode;
    private final List<ToolSpecification> toolSpecifications;
    private final Map<String, Object> metadata;

    public ToolContextContribution(String sourceKey,
                                    ContextVisibility visibility,
                                    ContextTrustLevel trustLevel,
                                    ContextPriority priority,
                                    ContextLifecycle lifecycle,
                                    boolean required,
                                    String providerName,
                                    String selectionMode,
                                    List<ToolSpecification> toolSpecifications,
                                    Map<String, Object> metadata) {
        this.sourceKey = sourceKey;
        this.visibility = visibility;
        this.trustLevel = trustLevel;
        this.priority = priority;
        this.lifecycle = lifecycle;
        this.required = required;
        this.providerName = providerName;
        this.selectionMode = selectionMode;
        this.toolSpecifications = toolSpecifications != null
                ? Collections.unmodifiableList(new ArrayList<>(toolSpecifications))
                : List.of();
        this.metadata = Collections.unmodifiableMap(new HashMap<>(
                metadata != null ? metadata : Map.of()));
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

    /** 选择模式：SELECTED / ALL_FALLBACK / NONE。 */
    public String selectionMode() { return selectionMode; }

    /** 不可变的 ToolSpecification 列表。 */
    public List<ToolSpecification> toolSpecifications() { return toolSpecifications; }
}
