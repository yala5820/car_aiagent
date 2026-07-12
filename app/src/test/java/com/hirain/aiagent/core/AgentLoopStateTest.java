package com.hirain.aiagent.core;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public class AgentLoopStateTest {

    @Test
    public void tryStart_casAtomicTwoThreads() throws InterruptedException {
        int rounds = 100;
        for (int r = 0; r < rounds; r++) {
            AgentLoopState state = new AgentLoopState();
            CountDownLatch latch = new CountDownLatch(1);
            AtomicInteger successCount = new AtomicInteger(0);

            Thread t1 = new Thread(() -> {
                try { latch.await(); } catch (InterruptedException e) { return; }
                if (state.tryStart()) successCount.incrementAndGet();
            });
            Thread t2 = new Thread(() -> {
                try { latch.await(); } catch (InterruptedException e) { return; }
                if (state.tryStart()) successCount.incrementAndGet();
            });

            t1.start();
            t2.start();
            latch.countDown(); // start both threads simultaneously
            t1.join(1000);
            t2.join(1000);

            assertEquals("Round " + r + ": expected exactly one success", 1, successCount.get());
        }
    }

    @Test
    public void tryStart_afterCompleted_allowsRestart() {
        AgentLoopState state = new AgentLoopState();
        state.tryStart();
        state.markCompleted();

        boolean restarted = state.tryStart();
        assertEquals(true, restarted);
    }

    @Test
    public void tryStart_afterError_allowsRestart() {
        AgentLoopState state = new AgentLoopState();
        state.tryStart();
        state.markError();

        boolean restarted = state.tryStart();
        assertEquals(true, restarted);
    }

    @Test
    public void tryStart_afterTimeout_allowsRestart() {
        AgentLoopState state = new AgentLoopState();
        state.tryStart();
        state.markTimeout();

        boolean restarted = state.tryStart();
        assertEquals(true, restarted);
    }
}
