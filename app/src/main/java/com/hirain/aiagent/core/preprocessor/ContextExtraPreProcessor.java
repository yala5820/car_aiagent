package com.hirain.aiagent.core.preprocessor;

import com.hirain.aiagent.core.AgentLoopContext;
import com.hirain.aiagent.core.component.PreProcessor;

import java.util.List;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

/**
 * 将 Context 模块生成的轻量上下文注入首轮模型请求。
 * <p>
 * 设计原因：AgentLoopOrchestrator 只会把 PreProcessor 返回值加入 ChatRequest.messages()；
 * 单纯把 context_rendered_extra 放进 extraContext Map 不会进入 LLM 输入。
 */
public class ContextExtraPreProcessor implements PreProcessor {
    public static final String KEY_CONTEXT_RENDERED_EXTRA = "context_rendered_extra";
    public static final String KEY_CONTEXT_MODE = "context_mode";

    @Override
    public List<ChatMessage> prepare(AgentLoopContext ctx) {
        if (ctx.iteration() != 0) return List.of();
        String mode = ctx.getContextData(KEY_CONTEXT_MODE, String.class);
        if (!isContextInjectionEnabled(mode)) return List.of();
        String text = ctx.getContextData(KEY_CONTEXT_RENDERED_EXTRA, String.class);
        if (text == null || text.trim().isEmpty()) return List.of();
        return List.of(UserMessage.from(text));
    }

    /**
     * 判断当前 context_mode 是否启用 Context 注入。
     * <p>
     * HYBRID_EXTRA_CONTEXT：一期默认模式，正常注入。
     * FULL_CONTEXT：一期降级同 HYBRID 行为，仍会注入。
     * OBSERVE_ONLY：不注入。
     */
    private static boolean isContextInjectionEnabled(String mode) {
        return "HYBRID_EXTRA_CONTEXT".equals(mode) || "FULL_CONTEXT".equals(mode);
    }
}
