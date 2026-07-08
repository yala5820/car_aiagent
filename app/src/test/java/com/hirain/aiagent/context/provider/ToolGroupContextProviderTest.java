package com.hirain.aiagent.context.provider;

import com.hirain.aiagent.context.ContextBuildInput;
import com.hirain.aiagent.context.ContextMode;
import com.hirain.aiagent.context.ContextProviderResult;
import com.hirain.aiagent.context.TestRequestSessions;
import com.hirain.aiagent.runtime.RequestSession;
import com.hirain.aiagent.toolgroup.ToolGroupRegistry;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ToolGroupContextProviderTest {

    @Test
    public void provide_rendersOnlySelectedTools() throws Exception {
        RequestSession session = TestRequestSessions.textSession(
                "req-1", "conv-1", "user-a", "chat", "client-1", "打开空调");
        ContextBuildInput input = ContextBuildInput.builder()
                .mode(ContextMode.HYBRID_EXTRA_CONTEXT)
                .toolGroupRegistry(ToolGroupRegistry.defaultRegistry())
                .build();

        ContextProviderResult result =
                new ToolGroupContextProvider().provide(session, input);

        String content = result.section().content();
        assertTrue(content.contains("AC_GROUP"));
        assertTrue(content.contains("set_ac_status"));
        assertFalse(content.contains("set_fl_window_status"));
        assertFalse(content.contains("front_camera_interaction"));
    }
}
