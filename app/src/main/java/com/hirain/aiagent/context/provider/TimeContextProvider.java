package com.hirain.aiagent.context.provider;

import com.hirain.aiagent.context.ContextBuildInput;
import com.hirain.aiagent.context.ContextLifecycle;
import com.hirain.aiagent.context.ContextPriority;
import com.hirain.aiagent.context.ContextProvider;
import com.hirain.aiagent.context.ContextProviderResult;
import com.hirain.aiagent.context.ContextTrustLevel;
import com.hirain.aiagent.context.ContextVisibility;
import com.hirain.aiagent.context.TextContextContribution;
import com.hirain.aiagent.runtime.RequestSession;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 时间上下文 Provider — 提供当前时间，输出 CONTEXT_DATA 贡献。
 */
public class TimeContextProvider implements ContextProvider {

    @Override
    public String name() {
        return "TimeContextProvider";
    }

    @Override
    public ContextLifecycle lifecycle() {
        return ContextLifecycle.ITERATION_DYNAMIC;
    }

    @Override
    public boolean required(RequestSession session, ContextBuildInput input) {
        return false;
    }

    @Override
    public ContextProviderResult provide(RequestSession session, ContextBuildInput input) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        long nowMs = input.timeProvider().nowMillis();
        String formattedTime = fmt.format(new Date(nowMs));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("timestamp_ms", nowMs);
        metadata.put("formatted_time", formattedTime);

        TextContextContribution contribution = new TextContextContribution(
                "time", ContextVisibility.MODEL_VISIBLE, ContextTrustLevel.TRUSTED_DATA,
                ContextPriority.OPTIONAL, ContextLifecycle.ITERATION_DYNAMIC, false,
                name(), TextContextContribution.TARGET_CONTEXT_DATA,
                formattedTime, metadata);
        return ContextProviderResult.success(name(), List.of(contribution));
    }
}
