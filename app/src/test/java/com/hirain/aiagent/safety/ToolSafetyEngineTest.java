package com.hirain.aiagent.safety;

import com.hirain.aiagent.VirtualStateMachine.VehicleStateMachine;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ToolSafetyEngineTest {

    @Test
    public void constructor_rejectsEmptyRuleList() {
        Map<String, List<SafetyRule>> rules = new LinkedHashMap<>();
        rules.put("high_risk_tool", List.of());

        assertConstructorRejected(rules);
    }

    @Test
    public void constructor_rejectsNullRule() {
        Map<String, List<SafetyRule>> rules = new LinkedHashMap<>();
        rules.put("high_risk_tool", Arrays.asList((SafetyRule) null));

        assertConstructorRejected(rules);
    }

    @Test
    public void constructor_rejectsBlankOrPaddedToolName() {
        Map<String, List<SafetyRule>> blankRules = new LinkedHashMap<>();
        blankRules.put(" ", List.of((context, state) -> SafetyDecision.allow()));
        assertConstructorRejected(blankRules);

        Map<String, List<SafetyRule>> paddedRules = new LinkedHashMap<>();
        paddedRules.put(" set_door_lock ",
                List.of((context, state) -> SafetyDecision.allow()));
        assertConstructorRejected(paddedRules);
    }

    @Test
    public void blankOrPaddedRequestName_deniesAsInvalidArgument() {
        ToolSafetyEngine engine = new ToolSafetyEngine(
                new VehicleStateMachine(), Map.of());

        SafetyDecision blankDecision = engine.check(request(" ", "{}"));
        SafetyDecision paddedDecision = engine.check(
                request(" set_door_lock ", "{\"arg0\":false}"));

        assertTrue(blankDecision.isDenied());
        assertTrue(paddedDecision.isDenied());
        assertEquals(SafetyDecision.ReasonCode.INVALID_ARGUMENT,
                blankDecision.reasonCode());
        assertEquals(SafetyDecision.ReasonCode.INVALID_ARGUMENT,
                paddedDecision.reasonCode());
    }

    @Test
    public void unregisteredTool_allowsWithoutParsingArguments() {
        ToolSafetyEngine engine = new ToolSafetyEngine(
                new VehicleStateMachine(), DefaultSafetyRules.create());

        SafetyDecision decision = engine.check(request("set_ac_status", "not-json"));

        assertTrue(decision.isAllowed());
        assertEquals(SafetyDecision.ReasonCode.ALLOW, decision.reasonCode());
    }

    @Test
    public void highRiskToolWithoutDedicatedPolicy_deniesClosed() {
        ToolSafetyEngine engine = new ToolSafetyEngine(
                new VehicleStateMachine(), Map.of());

        SafetyDecision decision = engine.check(
                request("set_door_lock", "{\"arg0\":false}"));

        assertTrue(decision.isDenied());
        assertEquals(SafetyDecision.ReasonCode.POLICY_NOT_CONFIGURED,
                decision.reasonCode());
    }

    @Test
    public void registeredTool_invalidJson_deniesWithStableCode() {
        ToolSafetyEngine engine = new ToolSafetyEngine(
                new VehicleStateMachine(), DefaultSafetyRules.create());

        SafetyDecision decision = engine.check(request("set_door_lock", "not-json"));

        assertTrue(decision.isDenied());
        assertEquals(SafetyDecision.ReasonCode.INVALID_ARGUMENT, decision.reasonCode());
    }

    @Test
    public void multipleRules_executeInOrderAndStopAtFirstDeny() {
        AtomicInteger firstCount = new AtomicInteger();
        AtomicInteger secondCount = new AtomicInteger();
        AtomicInteger thirdCount = new AtomicInteger();
        List<String> order = new ArrayList<>();

        SafetyRule first = (context, state) -> {
            firstCount.incrementAndGet();
            order.add("first");
            return SafetyDecision.allow();
        };
        SafetyRule second = (context, state) -> {
            secondCount.incrementAndGet();
            order.add("second");
            return SafetyDecision.deny(
                    SafetyDecision.ReasonCode.RULE_EXECUTION_ERROR, "测试拒绝");
        };
        SafetyRule third = (context, state) -> {
            thirdCount.incrementAndGet();
            order.add("third");
            return SafetyDecision.allow();
        };

        ToolSafetyEngine engine = new ToolSafetyEngine(new VehicleStateMachine(),
                Map.of("test_tool", List.of(first, second, third)));
        SafetyDecision decision = engine.check(request("test_tool", "{}"));

        assertTrue(decision.isDenied());
        assertEquals(List.of("first", "second"), order);
        assertEquals(1, firstCount.get());
        assertEquals(1, secondCount.get());
        assertEquals(0, thirdCount.get());
    }

    @Test
    public void allRulesAllow_returnsSharedAllowDecision() {
        SafetyRule first = (context, state) -> SafetyDecision.allow();
        SafetyRule second = (context, state) -> SafetyDecision.allow();
        ToolSafetyEngine engine = new ToolSafetyEngine(new VehicleStateMachine(),
                Map.of("test_tool", List.of(first, second)));

        SafetyDecision decision = engine.check(request("test_tool", "{}"));

        assertSame(SafetyDecision.allow(), decision);
    }

    @Test
    public void unexpectedRuleException_deniesInsteadOfAllowing() {
        SafetyRule broken = (context, state) -> {
            throw new IllegalStateException("boom");
        };
        ToolSafetyEngine engine = new ToolSafetyEngine(new VehicleStateMachine(),
                Map.of("test_tool", List.of(broken)));

        SafetyDecision decision = engine.check(request("test_tool", "{}"));

        assertTrue(decision.isDenied());
        assertEquals(SafetyDecision.ReasonCode.RULE_EXECUTION_ERROR,
                decision.reasonCode());
    }

    @Test
    public void nullRuleDecision_deniesInsteadOfAllowing() {
        SafetyRule broken = (context, state) -> null;
        ToolSafetyEngine engine = new ToolSafetyEngine(new VehicleStateMachine(),
                Map.of("test_tool", List.of(broken)));

        SafetyDecision decision = engine.check(request("test_tool", "{}"));

        assertTrue(decision.isDenied());
        assertEquals(SafetyDecision.ReasonCode.RULE_EXECUTION_ERROR,
                decision.reasonCode());
    }

    @Test
    public void constructor_copiesRuleMap() {
        SafetyRule deny = (context, state) -> SafetyDecision.deny(
                SafetyDecision.ReasonCode.RULE_EXECUTION_ERROR, "测试拒绝");
        Map<String, List<SafetyRule>> source = new LinkedHashMap<>();
        source.put("test_tool", List.of(deny));
        ToolSafetyEngine engine = new ToolSafetyEngine(new VehicleStateMachine(), source);

        source.clear();
        SafetyDecision decision = engine.check(request("test_tool", "{}"));

        assertTrue(decision.isDenied());
    }

    @Test
    public void formatDenyResult_containsCodeReasonAndInstruction() {
        ToolSafetyEngine engine = new ToolSafetyEngine(
                new VehicleStateMachine(), Map.of());
        SafetyDecision decision = SafetyDecision.deny(
                SafetyDecision.ReasonCode.DOOR_UNLOCK_REQUIRES_STOPPED,
                "车辆未静止");

        String result = engine.formatDenyResult(decision);

        assertTrue(result.contains("[SAFETY_DENY][DOOR_UNLOCK_REQUIRES_STOPPED]"));
        assertTrue(result.contains("车辆未静止"));
        assertTrue(result.contains("不要再次调用该工具"));
        assertFalse(result.contains("SAFETY VETO"));
    }

    private static ToolExecutionRequest request(String name, String arguments) {
        return ToolExecutionRequest.builder()
                .id("request-1")
                .name(name)
                .arguments(arguments)
                .build();
    }

    private static void assertConstructorRejected(
            Map<String, List<SafetyRule>> rules) {
        boolean thrown = false;
        try {
            new ToolSafetyEngine(new VehicleStateMachine(), rules);
        } catch (IllegalArgumentException expected) {
            thrown = true;
        }
        assertTrue("malformed safety configuration must fail fast", thrown);
    }
}
