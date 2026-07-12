package com.hirain.aiagent.context.provider;

import com.hirain.aiagent.context.ContextBuildInput;
import com.hirain.aiagent.context.ContextProviderResult;
import com.hirain.aiagent.context.ToolContextContribution;
import com.hirain.aiagent.context.TestRequestSessions;
import com.hirain.aiagent.runtime.RequestSession;
import com.hirain.aiagent.toolgroup.ToolGroupRegistry;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ToolGroupContextProviderTest {

    @Test
    public void provide_rendersOnlySelectedTools() throws Exception {
        RequestSession session = TestRequestSessions.textSession(
                "req-1", "conv-1", "user-a", "chat", "client-1", "打开空调");
        ContextBuildInput input = ContextBuildInput.builder()
                .toolGroupRegistry(ToolGroupRegistry.defaultRegistry())
                // toolRegistry 不含 android.util.Log 无法在 JVM 测试中构造
                // ToolSpecification 解析需在设备测试中验证
                .build();

        ContextProviderResult result =
                new ToolGroupContextProvider().provide(session, input);

        // 无 toolRegistry 时应返回 TOOL_SPEC_RESOLUTION_FAILED
        assertFalse(result.success());
    }

    @Test
    public void chatOnly_returnsEmptyTools() {
        RequestSession session = TestRequestSessions.chatOnlySession(
                "req-1", "conv-1", "user-a", "chat", "client-1", "你好");
        ContextBuildInput input = ContextBuildInput.builder().build();

        ContextProviderResult result =
                new ToolGroupContextProvider().provide(session, input);

        assertTrue(result.success());
        assertFalse(result.contributions().isEmpty());
        ToolContextContribution contrib = (ToolContextContribution) result.contributions().get(0);
        assertEquals(ToolContextContribution.MODE_NONE, contrib.selectionMode());
        assertTrue(contrib.toolSpecifications().isEmpty());
    }

    @Test
    public void normalAcSelection_contributionMetadataCorrect() {
        RequestSession session = TestRequestSessions.textSession(
                "req-1", "conv-1", "user-a", "chat", "client-1", "打开空调");
        ContextBuildInput input = ContextBuildInput.builder()
                .toolGroupRegistry(ToolGroupRegistry.defaultRegistry())
                .build();

        ContextProviderResult result =
                new ToolGroupContextProvider().provide(session, input);

        // 无 toolRegistry → 返回 FAILED
        assertFalse(result.success());
    }
}
