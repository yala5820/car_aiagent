package com.hirain.aiagent.context.provider;

import com.hirain.aiagent.context.ContextBuildInput;
import com.hirain.aiagent.context.ContextLifecycle;
import com.hirain.aiagent.context.ContextPriority;
import com.hirain.aiagent.context.ContextProvider;
import com.hirain.aiagent.context.ContextProviderResult;
import com.hirain.aiagent.context.ContextTrustLevel;
import com.hirain.aiagent.context.ContextVisibility;
import com.hirain.aiagent.context.MessageContextContribution;
import com.hirain.aiagent.memory.SpeakerMessageFormatter;
import com.hirain.aiagent.runtime.RequestSession;

import java.util.List;
import java.util.Map;

import dev.langchain4j.data.message.UserMessage;

/**
 * 用户输入上下文 Provider — 生成唯一 CURRENT_USER MessageContextContribution。
 */
public class UserInputContextProvider implements ContextProvider {

    @Override
    public String name() { return "UserInputContextProvider"; }

    @Override public String sourceKey() { return com.hirain.aiagent.context.ContextPolicies.CURRENT_USER; }

    @Override
    public ContextLifecycle lifecycle() { return ContextLifecycle.REQUEST_STATIC; }

    @Override
    public boolean required(RequestSession session, ContextBuildInput input) {
        return com.hirain.aiagent.context.ContextPolicies.resolve(sourceKey(), session, input).required();
    }

    @Override
    public ContextProviderResult provide(RequestSession session, ContextBuildInput input) {
        String rawInput = session.userInput() != null ? session.userInput() : "";
        String userId = session.userId() != null ? session.userId() : "default_user";
        String normalizedInput = rawInput.trim();

        String formattedText = SpeakerMessageFormatter.formatUserMessage(userId, rawInput);
        UserMessage currentUserMessage = UserMessage.from(formattedText);

        MessageContextContribution contribution = new MessageContextContribution(
                com.hirain.aiagent.context.ContextPolicies.resolve(sourceKey(), session, input),
                name(), MessageContextContribution.SOURCE_CURRENT_USER,
                List.of(currentUserMessage),
                Map.of("raw_user_input", rawInput,
                        "normalized_user_input", normalizedInput,
                        "input_length", rawInput.length()));
        return ContextProviderResult.success(name(), List.of(contribution));
    }
}
