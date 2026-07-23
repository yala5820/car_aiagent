package com.hirain.aiagent.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import com.hirain.aiagent.rag.policy.*;

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
    @Test public void bindsSameKnowledgeStateOnlyForScope() { KnowledgeRequestState state=new KnowledgeRequestState();KnowledgeIntentDecision decision=new KnowledgeIntentDecision(KnowledgeRequirement.REQUIRED,"test","v1",false);try(RequestExecutionContext.Scope ignored=RequestExecutionContext.bind("request",RequestDeadline.knowledge(1L),"q",null,decision,state)){assertSame(state,RequestExecutionContext.current().knowledgeRequestState());assertSame(decision,RequestExecutionContext.current().knowledgeIntentDecision());}assertNull(RequestExecutionContext.current()); }
}
