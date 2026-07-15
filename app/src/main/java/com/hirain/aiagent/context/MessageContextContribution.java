package com.hirain.aiagent.context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.langchain4j.data.message.ChatMessage;

/**
 * 消息 Contribution — 承载不可变 LangChain4j ChatMessage 列表。
 * <p>
 * 标记消息来源为 CURRENT_USER（当前用户输入）或 SESSION_MEMORY（会话历史）。
 */
public final class MessageContextContribution implements ContextContribution {

    public static final String SOURCE_CURRENT_USER = "CURRENT_USER";
    public static final String SOURCE_SESSION_MEMORY = "SESSION_MEMORY";

    private final String sourceKey;
    private final ContextVisibility visibility;
    private final ContextTrustLevel trustLevel;
    private final ContextPriority priority;
    private final ContextLifecycle lifecycle;
    private final boolean required;
    private final String providerName;
    private final String messageSource;
    private final List<ChatMessage> messages;
    private final Map<String, Object> metadata;

    public MessageContextContribution(String sourceKey,
                                       ContextVisibility visibility,
                                       ContextTrustLevel trustLevel,
                                       ContextPriority priority,
                                       ContextLifecycle lifecycle,
                                       boolean required,
                                       String providerName,
                                       String messageSource,
                                       List<ChatMessage> messages,
                                       Map<String, Object> metadata) {
        this.sourceKey = sourceKey;
        this.visibility = visibility;
        this.trustLevel = trustLevel;
        this.priority = priority;
        this.lifecycle = lifecycle;
        this.required = required;
        this.providerName = providerName;
        this.messageSource = messageSource;
        this.messages = messages != null
                ? Collections.unmodifiableList(new ArrayList<>(messages))
                : List.of();
        this.metadata = Collections.unmodifiableMap(new HashMap<>(
                metadata != null ? metadata : Map.of()));
    }

    public MessageContextContribution(ResolvedContextPolicy policy,
                                      String providerName, String messageSource,
                                      List<ChatMessage> messages, Map<String, Object> metadata) {
        this(policy.sourceKey(), policy.visibility(), policy.trustLevel(), policy.priority(),
                policy.lifecycle(), policy.required(), providerName, messageSource, messages, metadata);
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

    /** 消息来源：CURRENT_USER（当前用户输入）或 SESSION_MEMORY（会话历史）。 */
    public String messageSource() { return messageSource; }

    /** 不可变的 ChatMessage 列表。 */
    public List<ChatMessage> messages() { return messages; }
}
