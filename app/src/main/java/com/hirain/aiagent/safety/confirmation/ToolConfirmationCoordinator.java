package com.hirain.aiagent.safety.confirmation;

import com.hirain.aiagent.core.component.ToolExecutor;
import com.hirain.aiagent.runtime.TimeProvider;
import com.hirain.aiagent.safety.SafetyDecision;
import com.hirain.aiagent.safety.ToolSafetyEngine;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

/**
 * 高风险 Tool 文本确认协调器。
 * <p>
 * 首轮只保存原始请求；下一条严格确认文本原子领取后，通过同一个 Safety Engine
 * 读取最新车辆状态并复核，最后最多 dispatch 一次。
 */
public final class ToolConfirmationCoordinator {

    public static final long PENDING_TTL_MS = 30_000L;

    private final ToolSafetyEngine safetyEngine;
    private final ToolExecutor toolExecutor;
    private final TimeProvider timeProvider;
    private final PendingToolActionStore store;
    private final ConfirmationTextParser parser;

    public ToolConfirmationCoordinator(ToolSafetyEngine safetyEngine,
                                       ToolExecutor toolExecutor,
                                       TimeProvider timeProvider) {
        this(safetyEngine, toolExecutor, timeProvider,
                new PendingToolActionStore(), new ConfirmationTextParser());
    }

    public ToolConfirmationCoordinator(ToolSafetyEngine safetyEngine,
                                       ToolExecutor toolExecutor,
                                       TimeProvider timeProvider,
                                       PendingToolActionStore store,
                                       ConfirmationTextParser parser) {
        this.safetyEngine = Objects.requireNonNull(safetyEngine, "safetyEngine");
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.store = Objects.requireNonNull(store, "store");
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    public ConfirmationTextParser.Command parse(String text) {
        return parser.parse(text);
    }

    /** 保存单个原始动作并生成确定性确认提示。 */
    public String createPending(String sessionId, String originalRequestId,
                                ToolExecutionRequest request, String actionSummary) {
        long nowMs = timeProvider.nowMillis();
        PendingToolAction action = new PendingToolAction(
                UUID.randomUUID().toString(), sessionId, originalRequestId,
                request, actionSummary, nowMs, nowMs + PENDING_TTL_MS);
        store.put(action);
        return "即将执行：" + actionSummary + "。\n"
                + "该操作需要二次确认，请在 30 秒内明确回复“确认执行”；"
                + "如不执行，请回复“取消执行”。";
    }

    /** 新的普通请求会使旧待确认动作失效，避免旧授权跨请求残留。 */
    public void cancelPendingForOrdinaryRequest() {
        store.cancelAny();
    }

    /** 取消/超时只清理本轮刚创建的动作，不影响其他请求。 */
    public void cancelPendingForOriginalRequest(String originalRequestId) {
        store.cancelByOriginalRequestId(originalRequestId);
    }

    public ConfirmationExecutionResult handleText(String text, String sessionId,
                                                  BooleanSupplier stopped) {
        ConfirmationTextParser.Command command = parser.parse(text);
        if (command == ConfirmationTextParser.Command.NONE) {
            return ConfirmationExecutionResult.notHandled();
        }
        if (command == ConfirmationTextParser.Command.CANCEL) {
            PendingToolAction cancelled = store.cancel(sessionId);
            return ConfirmationExecutionResult.handled(true,
                    cancelled != null ? "已取消待确认操作。" : "当前没有可取消的待确认操作。",
                    SafetyDecision.ReasonCode.CONFIRMATION_CANCELLED,
                    cancelled != null ? cancelled.confirmationId() : null);
        }

        PendingToolActionStore.TakeResult take =
                store.takeForConfirmation(sessionId, timeProvider.nowMillis());
        if (take.status() != PendingToolActionStore.TakeStatus.TAKEN) {
            SafetyDecision.ReasonCode code = take.status() == PendingToolActionStore.TakeStatus.EXPIRED
                    ? SafetyDecision.ReasonCode.CONFIRMATION_EXPIRED
                    : SafetyDecision.ReasonCode.CONFIRMATION_ALREADY_CONSUMED;
            String message = take.status() == PendingToolActionStore.TakeStatus.EXPIRED
                    ? "待确认操作已超过 30 秒，本次未执行。"
                    : "当前没有可确认的待执行操作。";
            return ConfirmationExecutionResult.handled(false, message, code,
                    take.action() != null ? take.action().confirmationId() : null);
        }

        PendingToolAction action = take.action();
        if (stopped != null && stopped.getAsBoolean()) {
            return ConfirmationExecutionResult.handled(false,
                    "确认请求已取消或超时，本次未执行。",
                    SafetyDecision.ReasonCode.CONFIRMATION_CANCELLED,
                    action.confirmationId());
        }

        ToolExecutionRequest originalRequest = action.toOriginalRequest();
        SafetyDecision recheck = safetyEngine.recheckConfirmed(originalRequest);
        if (!recheck.isAllowed()) {
            return ConfirmationExecutionResult.handled(false,
                    "确认前车辆状态已变化或无法安全复核，本次未执行。" +
                            (recheck.reason() != null ? " " + recheck.reason() : ""),
                    recheck.reasonCode(), action.confirmationId());
        }
        if (stopped != null && stopped.getAsBoolean()) {
            return ConfirmationExecutionResult.handled(false,
                    "确认请求已取消或超时，本次未执行。",
                    SafetyDecision.ReasonCode.CONFIRMATION_CANCELLED,
                    action.confirmationId());
        }

        try {
            String result = toolExecutor.execute(originalRequest);
            return ConfirmationExecutionResult.handled(true,
                    "已在虚拟车辆状态机中执行：" + action.actionSummary() + "。\n执行结果："
                            + (result != null ? result : "{}"),
                    SafetyDecision.ReasonCode.ALLOW, action.confirmationId());
        } catch (Exception e) {
            return ConfirmationExecutionResult.handled(false,
                    "待确认操作执行失败，本次不会重试：" + e.getMessage(),
                    SafetyDecision.ReasonCode.RULE_EXECUTION_ERROR,
                    action.confirmationId());
        }
    }

    public PendingToolAction pendingAction() {
        return store.peek();
    }
}
