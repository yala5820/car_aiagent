package com.hirain.aiagent.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class RequestExecutionContextTest {

    @Test
    public void scope_restoresPreviousContextAndClearsThread() {
        RequestDeadline outerDeadline = new RequestDeadline(1_000L, 30_000L);
        RequestDeadline innerDeadline = new RequestDeadline(2_000L, 30_000L);

        try (RequestExecutionContext.Scope outer =
                     RequestExecutionContext.bind("outer", outerDeadline)) {
            assertEquals("outer", RequestExecutionContext.current().requestId());
            try (RequestExecutionContext.Scope inner =
                         RequestExecutionContext.bind("inner", innerDeadline)) {
                assertEquals("inner", RequestExecutionContext.current().requestId());
            }
            assertEquals("outer", RequestExecutionContext.current().requestId());
        }

        assertNull(RequestExecutionContext.current());
    }
}
