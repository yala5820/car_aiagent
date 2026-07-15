package com.hirain.aiagent.safety.confirmation;

import com.hirain.aiagent.VirtualStateMachine.VehicleStateMachine;
import com.hirain.aiagent.safety.DefaultSafetyRules;
import com.hirain.aiagent.safety.SafetyDecision;
import com.hirain.aiagent.safety.ToolSafetyEngine;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ToolConfirmationCoordinatorTest {

    @Test
    public void parser_onlyAcceptsTwoStrictCommands() {
        ConfirmationTextParser parser = new ConfirmationTextParser();

        assertEquals(ConfirmationTextParser.Command.CONFIRM, parser.parse(" 确认执行 "));
        assertEquals(ConfirmationTextParser.Command.CANCEL, parser.parse("取消执行"));
        assertEquals(ConfirmationTextParser.Command.NONE, parser.parse("好的"));
        assertEquals(ConfirmationTextParser.Command.NONE, parser.parse("确认"));
    }

    @Test
    public void confirm_rechecksAndExecutesOriginalRequestOnlyOnce() {
        VehicleStateMachine state = new VehicleStateMachine();
        AtomicLong now = new AtomicLong(1_000L);
        AtomicInteger dispatches = new AtomicInteger();
        AtomicReference<ToolExecutionRequest> executed = new AtomicReference<>();
        ToolConfirmationCoordinator coordinator = coordinator(state, now, request -> {
            dispatches.incrementAndGet();
            executed.set(request);
            return "ok";
        });
        ToolExecutionRequest original = request("tool-1", "set_door_lock", "{\"arg0\":false}");

        String prompt = coordinator.createPending("session-1", "request-1", original,
                "车辆静止状态下解锁车门");
        ConfirmationExecutionResult first = coordinator.handleText(
                "确认执行", "session-1", () -> false);
        ConfirmationExecutionResult second = coordinator.handleText(
                "确认执行", "session-1", () -> false);

        assertTrue(prompt.contains("30 秒"));
        assertTrue(first.success());
        assertTrue(first.text().contains("虚拟车辆状态机"));
        assertFalse(second.success());
        assertEquals(1, dispatches.get());
        assertEquals(original.name(), executed.get().name());
        assertEquals(original.arguments(), executed.get().arguments());
        assertNull(coordinator.pendingAction());
    }

    @Test
    public void cancelAndOrdinaryTextNeverExecutePendingAction() {
        AtomicInteger dispatches = new AtomicInteger();
        ToolConfirmationCoordinator coordinator = coordinator(
                new VehicleStateMachine(), new AtomicLong(1_000L), request -> {
                    dispatches.incrementAndGet();
                    return "unexpected";
                });
        coordinator.createPending("session-1", "request-1",
                request("tool-1", "set_chassis_mode", "{\"arg0\":\"越野模式\"}"),
                "车辆静止状态下切换底盘模式");

        ConfirmationExecutionResult cancel = coordinator.handleText(
                "取消执行", "session-1", () -> false);
        ConfirmationExecutionResult ambiguous = coordinator.handleText(
                "好的", "session-1", () -> false);

        assertTrue(cancel.success());
        assertFalse(ambiguous.handled());
        assertEquals(0, dispatches.get());
        assertNull(coordinator.pendingAction());
    }

    @Test
    public void expiredOrStateChangedConfirmationDoesNotDispatch() {
        VehicleStateMachine state = new VehicleStateMachine();
        AtomicLong now = new AtomicLong(1_000L);
        AtomicInteger dispatches = new AtomicInteger();
        ToolConfirmationCoordinator coordinator = coordinator(state, now, request -> {
            dispatches.incrementAndGet();
            return "unexpected";
        });

        coordinator.createPending("session-1", "request-1",
                request("tool-1", "set_door_lock", "{\"arg0\":false}"), "解锁车门");
        now.set(31_000L);
        ConfirmationExecutionResult expired = coordinator.handleText(
                "确认执行", "session-1", () -> false);
        assertEquals(SafetyDecision.ReasonCode.CONFIRMATION_EXPIRED, expired.reasonCode());

        now.set(40_000L);
        coordinator.createPending("session-1", "request-2",
                request("tool-2", "set_door_lock", "{\"arg0\":false}"), "解锁车门");
        state.setVehicleSpd(10);
        ConfirmationExecutionResult changed = coordinator.handleText(
                "确认执行", "session-1", () -> false);

        assertEquals(SafetyDecision.ReasonCode.CONFIRMATION_STATE_CHANGED,
                changed.reasonCode());
        assertEquals(0, dispatches.get());
    }

    @Test
    public void concurrentConfirmation_consumesActionAtMostOnce() throws Exception {
        AtomicInteger dispatches = new AtomicInteger();
        ToolConfirmationCoordinator coordinator = coordinator(
                new VehicleStateMachine(), new AtomicLong(1_000L), request -> {
                    dispatches.incrementAndGet();
                    return "ok";
                });
        coordinator.createPending("session-1", "request-1",
                request("tool-1", "set_door_lock", "{\"arg0\":false}"), "解锁车门");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<ConfirmationExecutionResult> results = java.util.Collections.synchronizedList(
                new ArrayList<>());
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                start.await();
                results.add(coordinator.handleText("确认执行", "session-1", () -> false));
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS));

        assertEquals(2, results.size());
        assertEquals(1, results.stream().filter(ConfirmationExecutionResult::success).count());
        assertEquals(1, dispatches.get());
    }

    @Test
    public void differentSessionCannotConsumePendingAction() {
        ToolConfirmationCoordinator coordinator = coordinator(
                new VehicleStateMachine(), new AtomicLong(1_000L), request -> "ok");
        coordinator.createPending("session-1", "request-1",
                request("tool-1", "set_door_lock", "{\"arg0\":false}"), "解锁车门");

        ConfirmationExecutionResult result = coordinator.handleText(
                "确认执行", "session-2", () -> false);

        assertFalse(result.success());
        assertNotNull(coordinator.pendingAction());
    }

    @Test
    public void cancelledOriginalRequestRemovesJustCreatedPendingAction() {
        AtomicInteger dispatches = new AtomicInteger();
        ToolConfirmationCoordinator coordinator = coordinator(
                new VehicleStateMachine(), new AtomicLong(1_000L), request -> {
                    dispatches.incrementAndGet();
                    return "unexpected";
                });
        coordinator.createPending("session-1", "request-original",
                request("tool-1", "set_door_lock", "{\"arg0\":false}"), "解锁车门");

        coordinator.cancelPendingForOriginalRequest("request-original");
        ConfirmationExecutionResult result = coordinator.handleText(
                "确认执行", "session-1", () -> false);

        assertFalse(result.success());
        assertNull(coordinator.pendingAction());
        assertEquals(0, dispatches.get());
    }

    private static ToolConfirmationCoordinator coordinator(
            VehicleStateMachine state, AtomicLong now,
            com.hirain.aiagent.core.component.ToolExecutor executor) {
        return new ToolConfirmationCoordinator(
                new ToolSafetyEngine(state, DefaultSafetyRules.create()),
                executor, now::get);
    }

    private static ToolExecutionRequest request(String id, String name, String arguments) {
        return ToolExecutionRequest.builder()
                .id(id).name(name).arguments(arguments).build();
    }
}
