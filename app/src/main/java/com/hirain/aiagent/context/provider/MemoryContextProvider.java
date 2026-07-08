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
 * 长期记忆上下文 Provider — HYBRID 模式不渲染，标记 memory owner。
 * <p>
 * 一期不读取或拼接完整短期 ChatMemory 历史。
 * memorySummary 为空，仅通过 metadata 表示 memory owner。
 */
public class MemoryContextProvider implements ContextProvider {

    @Override
    public String name() {
        return "MemoryContextProvider";
    }

    @Override
    public ContextSectionType type() {
        return ContextSectionType.MEMORY;
    }

    @Override
    public ContextProviderResult provide(RequestSession session, ContextBuildInput input) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("memory_owner", "AgentLoopOrchestrator/MemoryOrchestrator");
        metadata.put("memory_injected_by_context", false);
        metadata.put("session_id", session.sessionId() != null ? session.sessionId() : "");

        // HYBRID 模式：不渲染长期记忆，避免与 MemoryPreProcessor 重复注入
        ContextSection section = new ContextSection(
                type(), name(), false, "", 0, false, metadata);
        return ContextProviderResult.success(name(), section);
    }
}
