package com.hirain.aiagent.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RequestDeadlineTest {

    @Test
    public void remainingAndExpired_shareOneAbsoluteDeadline() {
        RequestDeadline deadline = new RequestDeadline(1_000L, 30_000L);

        assertEquals(31_000L, deadline.deadlineAtMs());
        assertEquals(30_000L, deadline.remainingMs(1_000L));
        assertEquals(1L, deadline.remainingMs(30_999L));
        assertEquals(0L, deadline.remainingMs(31_000L));
        assertFalse(deadline.isExpired(30_999L));
        assertTrue(deadline.isExpired(31_000L));
    }
    @Test public void knowledgeDeadlineUsesSameSixtySecondAbsoluteSemanticsAsVision() { RequestDeadline deadline=RequestDeadline.knowledge(1_000L);assertEquals(61_000L,deadline.deadlineAtMs());assertEquals(RequestDeadline.VISION_TIMEOUT_MS,RequestDeadline.KNOWLEDGE_TIMEOUT_MS); }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPositiveTimeout() {
        new RequestDeadline(1_000L, 0L);
    }
}
