package com.hirain.aiagent.safety;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hirain.aiagent.VirtualStateMachine.VehicleStateMachine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

/**
 * Tool 执行前安全审核的唯一入口。
 * <p>
 * AgentLoop 每个 Tool 只调用一次本 Engine。Engine 负责选择规则和统一处理异常，
 * 具体业务判断保留在 rules 目录，未注册规则的低风险 Tool 默认放行。
 */
public final class ToolSafetyEngine {

    private static final String EXPLAIN_INSTRUCTION =
            "请向用户说明拒绝原因，不要再次调用该工具。";

    private final VehicleStateMachine vehicleStateMachine;
    private final Map<String, List<SafetyRule>> rulesByTool;

    public ToolSafetyEngine(VehicleStateMachine vehicleStateMachine,
                            Map<String, List<SafetyRule>> rulesByTool) {
        this.vehicleStateMachine = Objects.requireNonNull(
                vehicleStateMachine, "vehicleStateMachine");
        Objects.requireNonNull(rulesByTool, "rulesByTool");

        Map<String, List<SafetyRule>> copiedRules = new LinkedHashMap<>();
        for (Map.Entry<String, List<SafetyRule>> entry : rulesByTool.entrySet()) {
            String toolName = entry.getKey();
            if (toolName == null || toolName.trim().isEmpty()
                    || !toolName.equals(toolName.trim())) {
                throw new IllegalArgumentException("安全规则的工具名称不能为空或包含首尾空格");
            }

            List<SafetyRule> rules = entry.getValue();
            if (rules == null || rules.isEmpty()) {
                throw new IllegalArgumentException(
                        "已注册安全工具必须至少包含一条规则: " + toolName);
            }
            for (SafetyRule rule : rules) {
                if (rule == null) {
                    throw new IllegalArgumentException(
                            "安全规则列表不能包含 null: " + toolName);
                }
            }
            copiedRules.put(toolName, Collections.unmodifiableList(
                    new ArrayList<>(rules)));
        }
        this.rulesByTool = Collections.unmodifiableMap(copiedRules);
    }

    /** 审核单个 Tool 调用；没有注册规则的 Tool 默认放行。 */
    public SafetyDecision check(ToolExecutionRequest request) {
        return check(request, SafetyCheckMode.INITIAL);
    }

    /** 确认后使用保存的原始请求重新读取车辆状态并复核。 */
    public SafetyDecision recheckConfirmed(ToolExecutionRequest request) {
        return check(request, SafetyCheckMode.CONFIRMED_RECHECK);
    }

    private SafetyDecision check(ToolExecutionRequest request, SafetyCheckMode mode) {
        if (request == null || request.name() == null
                || request.name().trim().isEmpty()
                || !request.name().equals(request.name().trim())) {
            return SafetyDecision.deny(
                    SafetyDecision.ReasonCode.INVALID_ARGUMENT,
                    "工具调用信息不完整，无法完成安全审核。"
            );
        }

        List<SafetyRule> rules = rulesByTool.get(request.name());
        if (rules == null || rules.isEmpty()) {
            if (DefaultSafetyRules.requiresDedicatedRule(request.name())) {
                return SafetyDecision.deny(
                        SafetyDecision.ReasonCode.POLICY_NOT_CONFIGURED,
                        "高风险工具缺少专用安全规则，已拒绝执行。"
                );
            }
            return SafetyDecision.allow();
        }

        final JsonObject arguments;
        try {
            JsonElement parsed = JsonParser.parseString(request.arguments());
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("arguments is not a JSON object");
            }
            arguments = parsed.getAsJsonObject();
        } catch (Exception e) {
            return SafetyDecision.deny(
                    SafetyDecision.ReasonCode.INVALID_ARGUMENT,
                    "工具参数不是有效的 JSON 对象，无法完成安全审核。"
            );
        }

        SafetyCheckContext context = new SafetyCheckContext(request.name(), arguments, mode);
        for (SafetyRule rule : rules) {
            try {
                SafetyDecision decision = rule.check(context, vehicleStateMachine);
                if (decision == null) {
                    return ruleExecutionError();
                }
                if (decision.isDenied() || decision.requiresConfirmation()) {
                    return decision;
                }
            } catch (Exception e) {
                return ruleExecutionError();
            }
        }
        return SafetyDecision.allow();
    }

    /** 非 TEXT 路径遇到确认要求时使用确定性拒绝结果，绝不 dispatch。 */
    public String formatConfirmationUnavailableResult() {
        return "[SAFETY_DENY][CONFIRMATION_CHANNEL_UNAVAILABLE]\n"
                + "该高风险操作需要通过 TEXT 请求二次确认，本次未执行。";
    }

    /** 统一 DENY 回写格式，避免多个 AgentLoop 路径各自拼接不同文本。 */
    public String formatDenyResult(SafetyDecision decision) {
        if (decision == null || !decision.isDenied()) {
            throw new IllegalArgumentException("只能格式化 DENY 结果");
        }
        return "[SAFETY_DENY][" + decision.reasonCode().name() + "]\n"
                + decision.reason() + EXPLAIN_INSTRUCTION;
    }

    private SafetyDecision ruleExecutionError() {
        return SafetyDecision.deny(
                SafetyDecision.ReasonCode.RULE_EXECUTION_ERROR,
                "安全规则执行异常，无法确认本次操作是否安全，已拒绝执行。"
        );
    }
}
