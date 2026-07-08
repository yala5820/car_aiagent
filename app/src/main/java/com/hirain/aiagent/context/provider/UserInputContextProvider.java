package com.hirain.aiagent.context.provider;

import com.hirain.aiagent.context.ContextBuildInput;
import com.hirain.aiagent.context.ContextProvider;
import com.hirain.aiagent.context.ContextProviderResult;
import com.hirain.aiagent.context.ContextSection;
import com.hirain.aiagent.context.ContextSectionType;
import com.hirain.aiagent.runtime.RequestSession;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户输入上下文 Provider — 记录原始和规范化后的用户输入。
 * <p>
 * HYBRID 模式下 renderable=false，避免把用户输入重复注入给 LLM。
 */
public class UserInputContextProvider implements ContextProvider {

    @Override
    public String name() {
        return "UserInputContextProvider";
    }

    @Override
    public ContextSectionType type() {
        return ContextSectionType.USER_INPUT;
    }

    @Override
    public ContextProviderResult provide(RequestSession session, ContextBuildInput input) {
        String rawInput = session.userInput() != null ? session.userInput() : "";
        String normalizedInput = rawInput.trim();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("raw_user_input", rawInput);
        metadata.put("normalized_user_input", normalizedInput);
        metadata.put("input_length", rawInput.length());

        // HYBRID 模式：不渲染用户输入，避免重复注入
        ContextSection section = new ContextSection(
                type(), name(), false, "", 0, false, metadata);
        return ContextProviderResult.success(name(), section);
    }
}
