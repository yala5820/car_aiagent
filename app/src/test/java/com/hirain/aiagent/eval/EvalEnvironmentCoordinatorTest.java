package com.hirain.aiagent.eval;

import com.hirain.aiagent.VirtualStateMachine.VehicleStateMachine;
import com.hirain.aiagent.runtime.TimeProvider;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 租约必须隔离调用方，并在执行中的 TTL 到期后等待 permit 收敛。 */
public class EvalEnvironmentCoordinatorTest {
    @Test public void leaseRejectsOtherUidAndDefersExpiryUntilPermitClose() {
        FakeTime clock = new FakeTime();
        EvalEnvironmentCoordinator coordinator = new EvalEnvironmentCoordinator(new VehicleStateMachine(), clock, () -> true);
        EvalEnvironmentCoordinator.OperationResult acquire = coordinator.acquire(100, "case-1", 60_000L);
        assertTrue(acquire.isSuccess());
        assertFalse(coordinator.checkAdmission(200).isAllowed());
        EvalRequestPermit permit = coordinator.beginRequest(100);
        assertNotNull(permit);
        // beginRequest 会按设计刷新为默认 120 秒 TTL。
        clock.now = 121_000L;
        assertFalse(coordinator.checkAdmission(200).isAllowed());
        permit.close();
        assertTrue(coordinator.checkAdmission(200).isAllowed());
        assertNull(coordinator.beginRequest(100));
    }

    @Test public void invalidTtlAndWrongTokenAreRejected() {
        FakeTime clock = new FakeTime();
        EvalEnvironmentCoordinator coordinator = new EvalEnvironmentCoordinator(new VehicleStateMachine(), clock, () -> true);
        assertEquals("INVALID_REQUEST", coordinator.acquire(1, "c", 1L).getErrorCode());
        String token = coordinator.acquire(1, "c", 60_000L).getLeaseToken();
        assertEquals("LEASE_TOKEN_INVALID", coordinator.read(1, "wrong").getErrorCode());
        assertTrue(coordinator.read(1, token).isSuccess());
    }

    private static final class FakeTime implements TimeProvider { long now; @Override public long nowMillis() { return now; } }
}
