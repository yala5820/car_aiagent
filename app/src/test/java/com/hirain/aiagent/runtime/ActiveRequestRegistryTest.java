package com.hirain.aiagent.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.hirain.aiagent.CancelRequestResult;

import org.junit.Test;

public class ActiveRequestRegistryTest {

    @Test
    public void tryAcquire_acceptsFirstAndRejectsSecondAsBusy() {
        ActiveRequestRegistry registry = new ActiveRequestRegistry();

        RequestAdmissionResult first = registry.tryAcquire(admission("req-1"), 1_000L);
        RequestAdmissionResult second = registry.tryAcquire(admission("req-2"), 1_001L);

        assertTrue(first.isAccepted());
        assertEquals(RequestAdmissionResult.Status.BUSY, second.status());
        assertSame(first.activeRequest(), registry.current());
    }

    @Test
    public void tryAcquire_rejectsDuplicateActiveWithoutOverwriting() {
        ActiveRequestRegistry registry = new ActiveRequestRegistry();
        RequestAdmissionResult first = registry.tryAcquire(admission("req-1"), 1_000L);

        RequestAdmissionResult duplicate = registry.tryAcquire(admission("req-1"), 1_001L);

        assertEquals(RequestAdmissionResult.Status.DUPLICATE_ACTIVE_REQUEST_ID,
                duplicate.status());
        assertSame(first.activeRequest(), registry.get("req-1"));
    }

    @Test
    public void release_movesRequestToFinishedCacheAndBlocksReplay() {
        ActiveRequestRegistry registry = new ActiveRequestRegistry();
        registry.tryAcquire(admission("req-1"), 1_000L);

        assertTrue(registry.release("req-1", 2_000L));
        assertNull(registry.current());

        RequestAdmissionResult replay = registry.tryAcquire(admission("req-1"), 2_001L);
        assertEquals(RequestAdmissionResult.Status.DUPLICATE_FINISHED_REQUEST_ID,
                replay.status());
    }

    @Test
    public void cancel_marksTerminalButDoesNotReleaseSlot() {
        ActiveRequestRegistry registry = new ActiveRequestRegistry();
        registry.tryAcquire(admission("req-1"), 1_000L);

        CancelRequestResult result = registry.cancel("req-1", "user_stop", 1_100L);
        RequestAdmissionResult second = registry.tryAcquire(admission("req-2"), 1_101L);

        assertTrue(result.isSuccess());
        assertEquals(CancelRequestResult.STATUS_ACCEPTED, result.getStatus());
        assertTrue(registry.get("req-1").isCancelled());
        assertEquals(RequestAdmissionResult.Status.BUSY, second.status());
    }

    @Test
    public void cancel_returnsAlreadyFinishedAfterWorkerRelease() {
        ActiveRequestRegistry registry = new ActiveRequestRegistry();
        registry.tryAcquire(admission("req-1"), 1_000L);
        registry.tryComplete("req-1", ActiveRequest.TerminalState.COMPLETED);
        registry.release("req-1", 2_000L);

        CancelRequestResult result = registry.cancel("req-1", "user_stop", 2_001L);

        assertFalse(result.isSuccess());
        assertEquals(CancelRequestResult.STATUS_ALREADY_FINISHED, result.getStatus());
    }

    @Test
    public void cancel_returnsNotFoundForUnknownRequest() {
        ActiveRequestRegistry registry = new ActiveRequestRegistry();

        CancelRequestResult result = registry.cancel("non-existent", "reason", 1_000L);

        assertFalse(result.isSuccess());
        assertEquals(CancelRequestResult.STATUS_NOT_FOUND, result.getStatus());
        assertNull(registry.get("non-existent"));
    }

    @Test
    public void tryComplete_raceOnlyFirstWins() {
        ActiveRequestRegistry registry = new ActiveRequestRegistry();
        registry.tryAcquire(admission("req-1"), 1_000L);

        assertTrue(registry.tryComplete("req-1", ActiveRequest.TerminalState.COMPLETED));
        assertFalse(registry.tryComplete("req-1", ActiveRequest.TerminalState.TIMEOUT));
        assertFalse(registry.tryComplete("req-1", ActiveRequest.TerminalState.FAILED));
    }

    @Test
    public void release_doesNotRemoveDifferentActiveRequest() {
        ActiveRequestRegistry registry = new ActiveRequestRegistry();
        registry.tryAcquire(admission("req-1"), 1_000L);

        assertFalse(registry.release("req-other", 2_000L));
        assertEquals("req-1", registry.current().requestId());
    }

    private static RequestAdmission admission(String requestId) {
        return new RequestAdmission(requestId, "session-1", "user-1", "chat",
                "client-1", new RequestDeadline(1_000L, 30_000L));
    }
}
