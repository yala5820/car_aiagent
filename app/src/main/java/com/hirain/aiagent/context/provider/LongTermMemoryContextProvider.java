package com.hirain.aiagent.context.provider;

import com.hirain.aiagent.context.ContextBuildInput;
import com.hirain.aiagent.context.ContextContribution;
import com.hirain.aiagent.context.ContextLifecycle;
import com.hirain.aiagent.context.ContextPriority;
import com.hirain.aiagent.context.ContextProvider;
import com.hirain.aiagent.context.ContextProviderResult;
import com.hirain.aiagent.context.ContextProviderStatus;
import com.hirain.aiagent.context.ContextTrustLevel;
import com.hirain.aiagent.context.ContextVisibility;
import com.hirain.aiagent.context.TextContextContribution;
import com.hirain.aiagent.memory.ContextMemoryGateway;
import com.hirain.aiagent.memory.MemoryEntry;
import com.hirain.aiagent.runtime.RequestSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆上下文 Provider — 读取用户长期记忆，输出 CONTEXT_DATA 贡献。
 * <p>
 * 设计原因：读取结构化条目而非已拼入 System Prompt 的字符串。
 * 信任级别为 UNTRUSTED_DATA（语义来源是用户输入），不得进入 SystemMessage。
 */
public class LongTermMemoryContextProvider implements ContextProvider {

    @Override
    public String name() {
        return "LongTermMemoryContextProvider";
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
        ContextMemoryGateway memory = input.memoryGateway();
        String userId = session.userId();
        String memoryText = "";
        ContextProviderStatus status = ContextProviderStatus.SUCCESS;
        String errorDetail = null;

        if (memory != null && userId != null) {
            try {
                com.hirain.aiagent.memory.LongTermMemorySnapshot snapshot =
                        memory.longTermMemorySnapshot(userId);
                if (!snapshot.isEmpty()) {
                    StringBuilder sb = new StringBuilder("【长期记忆】\n");
                    for (MemoryEntry entry : snapshot.entries()) {
                        sb.append("- ").append(entry.key()).append(": ")
                                .append(entry.value()).append("\n");
                    }
                    memoryText = sb.toString().trim();
                }
            } catch (Exception e) {
                status = ContextProviderStatus.FALLBACK;
                errorDetail = "long_term_memory_read_failed: " + e.getMessage();
            }
        } else {
            status = ContextProviderStatus.FALLBACK;
            errorDetail = memory == null ? "memory_not_configured" : "userId_is_null";
        }

        TextContextContribution contribution = new TextContextContribution(
                "long_term_memory", ContextVisibility.MODEL_VISIBLE, ContextTrustLevel.UNTRUSTED_DATA,
                ContextPriority.NORMAL, ContextLifecycle.REQUEST_STATIC, false,
                name(), TextContextContribution.TARGET_CONTEXT_DATA,
                memoryText, Map.of("memory_owner", "LongTermMemoryStore"));
        return ContextProviderResult.success(name(), List.of(contribution));


    }
}
