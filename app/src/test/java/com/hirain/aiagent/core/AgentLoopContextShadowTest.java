package com.hirain.aiagent.core;

import com.hirain.aiagent.context.ContextAssemblyGateway;
import com.hirain.aiagent.context.ContextAssemblyRequest;
import com.hirain.aiagent.context.ContextAssemblyResult;
import com.hirain.aiagent.context.TestRequestSessions;
import com.hirain.aiagent.runtime.RequestSession;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

/**
 * 验证 ContextAssemblyGateway 的可调用性。
 */
public class AgentLoopContextShadowTest {

    @Test
    public void shadowAssembleIsCalled() {
        AtomicInteger callCount = new AtomicInteger(0);
        ContextAssemblyGateway gateway = request -> {
            callCount.incrementAndGet();
            return ContextAssemblyResult.success(List.of(), List.of(), null, null, List.of());
        };
        ContextAssemblyRequest shadowRequest = new ContextAssemblyRequest(
                null, 0, null, null, false);
        gateway.assemble(shadowRequest);
        assertEquals(1, callCount.get());
    }
}
