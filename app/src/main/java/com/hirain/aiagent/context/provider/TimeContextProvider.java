package com.hirain.aiagent.context.provider;

import com.hirain.aiagent.context.ContextBuildInput;
import com.hirain.aiagent.context.ContextProvider;
import com.hirain.aiagent.context.ContextProviderResult;
import com.hirain.aiagent.context.ContextSection;
import com.hirain.aiagent.context.ContextSectionType;
import com.hirain.aiagent.runtime.RequestSession;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 时间上下文 Provider — HYBRID 模式不渲染，避免与 TimeContextPreProcessor 重复注入。
 * <p>
 * 使用 TimeProvider.nowMillis() 生成 yyyy-MM-dd HH:mm:ss 格式的调试值。
 * 每次调用在方法内局部创建 SimpleDateFormat，避免 static 实例的线程安全问题。
 */
public class TimeContextProvider implements ContextProvider {

    @Override
    public String name() {
        return "TimeContextProvider";
    }

    @Override
    public ContextSectionType type() {
        return ContextSectionType.TIME;
    }

    @Override
    public ContextProviderResult provide(RequestSession session, ContextBuildInput input) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        long nowMs = input.timeProvider().nowMillis();
        String formattedTime = fmt.format(new Date(nowMs));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("timestamp_ms", nowMs);
        metadata.put("formatted_time", formattedTime);

        // HYBRID 模式：不渲染时间上下文，避免与 TimeContextPreProcessor 重复注入
        ContextSection section = new ContextSection(
                type(), name(), false, formattedTime, formattedTime.length(), false, metadata);
        return ContextProviderResult.success(name(), section);
    }
}
