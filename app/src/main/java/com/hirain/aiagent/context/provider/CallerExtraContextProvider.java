package com.hirain.aiagent.context.provider;

import com.hirain.aiagent.context.ContextBuildInput;
import com.hirain.aiagent.context.ContextContribution;
import com.hirain.aiagent.context.ContextLifecycle;
import com.hirain.aiagent.context.ContextPriority;
import com.hirain.aiagent.context.ContextProvider;
import com.hirain.aiagent.context.ContextProviderResult;
import com.hirain.aiagent.context.ContextTrustLevel;
import com.hirain.aiagent.context.ContextVisibility;
import com.hirain.aiagent.context.TextContextContribution;
import com.hirain.aiagent.runtime.RequestSession;

import java.util.List;
import java.util.Map;

/**
 * 调用方附加信息 Provider — 读取 caller extra，标记为 UNTRUSTED_DATA。
 * <p>
 * 设计原因：caller extra 语义来源是外部调用方，可能包含“忽略系统指令”等文本，
 * 必须标记为 UNTRUSTED_DATA、OPTIONAL，不得进入 SystemMessage。
 */
public class CallerExtraContextProvider implements ContextProvider {

    @Override
    public String name() {
        return "CallerExtraContextProvider";
    }

    @Override
    public ContextLifecycle lifecycle() {
        return ContextLifecycle.REQUEST_STATIC;
    }

    @Override
    public boolean required(RequestSession session, ContextBuildInput input) {
        return false;
    }

    public ContextProviderResult provide(RequestSession session, ContextBuildInput input) {
        // 从 orchestratorContext 中读取 "extra_context" 键的值
        Map<String, Object> context = session.orchestratorContext();
        String extraText = "";
        if (context != null && context.containsKey("extra_context")) {
            Object val = context.get("extra_context");
            extraText = val != null ? String.valueOf(val) : "";
        }

        TextContextContribution contribution = new TextContextContribution(
                "caller_extra", ContextVisibility.MODEL_VISIBLE, ContextTrustLevel.UNTRUSTED_DATA,
                ContextPriority.OPTIONAL, ContextLifecycle.REQUEST_STATIC, false,
                name(), TextContextContribution.TARGET_CONTEXT_DATA,
                extraText, Map.of());

        return ContextProviderResult.success(name(), List.of(contribution));
    }
}
