package com.hirain.aiagent.context;

import com.hirain.aiagent.context.provider.CallerExtraContextProvider;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import com.hirain.aiagent.context.provider.IntentContextProvider;
import com.hirain.aiagent.context.provider.LongTermMemoryContextProvider;
import com.hirain.aiagent.context.provider.PersonaContextProvider;
import com.hirain.aiagent.context.provider.PromptContextProvider;
import com.hirain.aiagent.context.provider.RuntimeContextProvider;
import com.hirain.aiagent.context.provider.SessionMemoryContextProvider;
import com.hirain.aiagent.context.provider.TimeContextProvider;
import com.hirain.aiagent.context.provider.ToolGroupContextProvider;
import com.hirain.aiagent.context.provider.UserInputContextProvider;
import com.hirain.aiagent.context.provider.VehicleStateContextProvider;
import com.hirain.aiagent.memory.ContextMemoryGateway;
import com.hirain.aiagent.memory.MemoryCompactionPlan;
import com.hirain.aiagent.memory.MemoryCompactionResult;
import com.hirain.aiagent.runtime.RequestSession;
import com.hirain.aiagent.runtime.RequestSessionFactory;
import com.hirain.aiagent.trace.AgentTraceRecorder;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Context 构建总入口 — 实现 {@link ContextPreparer} 和 {@link ContextAssemblyGateway}。
 * <p>
 * TEXT Context 分为 prepare() 和 assemble() 两条链路：
 * <ul>
 *   <li>prepare() — 只运行请求级静态 Provider，每个 RequestSession 只执行一次</li>
 *   <li>assemble() — 每次迭代运行动态 Provider</li>
 * </ul>
 * 旧 build() 保留兼容，内部调用 prepare() 后生成旧格式结果。
 */
public class ContextOrchestrator implements ContextPreparer, ContextAssemblyGateway {

    private final ContextBuildInput input;
    private final List<ContextProvider> requestStaticProviders;
    private final List<ContextProvider> iterationDynamicProviders;

    /**
     * 全参数构造函数 — 分别指定请求级和迭代级 Provider。
     */
    public ContextOrchestrator(ContextBuildInput input,
                                List<ContextProvider> requestStaticProviders,
                                List<ContextProvider> iterationDynamicProviders) {
        this.input = input;
        this.requestStaticProviders = requestStaticProviders;
        this.iterationDynamicProviders = iterationDynamicProviders;
    }

    /**
     * 旧二参构造函数（兼容现有测试）— 所有 Provider 视为 request-static。
     */
    public ContextOrchestrator(ContextBuildInput input,
                                List<ContextProvider> providers) {
        this(input, providers, List.of());
    }

    /**
     * 标准 TEXT 请求 Provider 链 — request-static + iteration-dynamic 分拆。
     */
    public static ContextOrchestrator defaultForText(ContextBuildInput input) {
        return new ContextOrchestrator(input,
                // request-static
                List.of(
                        new RuntimeContextProvider(),
                        new PersonaContextProvider(),
                        new PromptContextProvider(),
                        new UserInputContextProvider(),
                        new IntentContextProvider(),
                        new ToolGroupContextProvider(),
                        new LongTermMemoryContextProvider(),
                        new CallerExtraContextProvider()),
                // iteration-dynamic
                List.of(
                        new SessionMemoryContextProvider(),
                        new VehicleStateContextProvider(),
                        new TimeContextProvider()));
    }

    // ── prepare（请求级静态 Provider） ──

    /**
     * 请求级准备 — 创建 context.prepare span，运行 request-static Provider。
     */
    public ContextPrepareResult prepare(RequestSession session,
                                         ContextCancelChecker cancelChecker) {
        ContextTraceRecorder traceRecorder = session != null
                ? new ContextTraceRecorder(session.traceContext()) : null;
        Span prepareSpan = traceRecorder != null
                ? traceRecorder.startPrepareSpan(io.opentelemetry.context.Context.current())
                : null;
        io.opentelemetry.context.Scope scope = prepareSpan != null
                ? prepareSpan.makeCurrent() : null;

        try {
            if (session == null) {
                return ContextPrepareResult.failed(
                        ContextErrorCode.CONTEXT_INTERNAL_ERROR, "session is null");
            }
            if (cancelChecker != null && cancelChecker.isCancelled()) {
                return ContextPrepareResult.cancelled("cancelled_before_prepare");
            }

            long startMs = System.currentTimeMillis();
            List<ContextProviderOutcome> outcomes = new ArrayList<>();
            List<ContextContribution> allContributions = new ArrayList<>();

            for (ContextProvider provider : requestStaticProviders) {
                // 进入每个 Provider 前先创建 provSpan 并 makeCurrent，span 包裹真实执行生命周期
                Span provSpan = traceRecorder != null
                        ? traceRecorder.startProviderSpan(provider.name(),
                                io.opentelemetry.context.Context.current())
                        : null;
                io.opentelemetry.context.Scope provScope = provSpan != null ? provSpan.makeCurrent() : null;
                long provStartMs = System.currentTimeMillis();
                try {
                    ContextProviderResult result = provider.provide(session, input);
                    validateProductionContributions(provider, result, session);
                    long provDurationMs = System.currentTimeMillis() - provStartMs;
                    allContributions.addAll(result.contributions());
                    if (result.outcome() != null) {
                        outcomes.add(result.outcome());
                    }
                    traceRecorder.finishProviderSpan(provSpan, result,
                            provider.lifecycle().name(), provider.required(session, input), provDurationMs);
                    // required Provider 失败 → 中断 prep
                    if (result.status() != ContextProviderStatus.SUCCESS && provider.required(session, input)) {
                        if (prepareSpan != null) prepareSpan.setAttribute("required_provider_failed", provider.name());
                        return ContextPrepareResult.failed(
                                result.errorCode() != null ? result.errorCode()
                                        : ContextErrorCode.REQUIRED_PROVIDER_FAILED,
                                "Required provider failed: " + provider.name()
                                        + " - " + (result.errorReason() != null
                                        ? result.errorReason() : "unknown"));
                    }
                } catch (Exception e) {
                    long provDurationMs = System.currentTimeMillis() - provStartMs;
                    outcomes.add(new ContextProviderOutcome(
                            provider.name(), ContextProviderStatus.FAILED,
                            ContextErrorCode.REQUIRED_PROVIDER_FAILED,
                            e.getMessage(), provDurationMs));
                    // 异常直接写入同一条 provSpan（不再新建 errProvSpan）
                    if (provSpan != null) {
                        provSpan.setAttribute("provider.name", provider.name());
                        provSpan.setAttribute("provider.status", "FAILED");
                        provSpan.setAttribute("provider.error_reason", e.getMessage());
                        provSpan.setAttribute("provider.duration_ms", provDurationMs);
                        provSpan.end();
                    }
                    if (provider.required(session, input)) {
                        if (prepareSpan != null) prepareSpan.setAttribute("required_provider_failed", provider.name());
                        return ContextPrepareResult.failed(
                                ContextErrorCode.REQUIRED_PROVIDER_FAILED,
                                "Required provider threw exception: " + provider.name()
                                        + " - " + e.getMessage());
                    }
                } finally {
                    if (provScope != null) provScope.close();
                }
                // 每 Provider 之间检查取消
                if (cancelChecker != null && cancelChecker.isCancelled()) {
                    return ContextPrepareResult.cancelled("cancelled_during_prepare");
                }
            }

            // 记录 Provider 指标到 span
            long successCount = outcomes.stream().filter(
                    o -> o.status() == ContextProviderStatus.SUCCESS).count();
            long fallbackCount = outcomes.stream().filter(
                    o -> o.status() == ContextProviderStatus.FALLBACK).count();
            long failedCount = outcomes.stream().filter(
                    o -> o.status() == ContextProviderStatus.FAILED).count();
            if (prepareSpan != null) {
                prepareSpan.setAttribute("provider.count", requestStaticProviders.size());
                prepareSpan.setAttribute("provider.success", (int) successCount);
                prepareSpan.setAttribute("provider.fallback", (int) fallbackCount);
                prepareSpan.setAttribute("provider.failed", (int) failedCount);
                prepareSpan.setAttribute("contribution.count", allContributions.size());
                prepareSpan.setAttribute("duration.ms", System.currentTimeMillis() - startMs);
            }

            // 构造 ContextFrame
            ContextFrame frame = ContextFrameBuilder.fromSession(session)
                    .effectivePersonaId(session.personaId())
                    .contributions(allContributions)
                    .build();

            if (cancelChecker != null && cancelChecker.isCancelled()) {
                return ContextPrepareResult.cancelled("cancelled_after_prepare");
            }

            // 从 Contribution 中提取 CURRENT_USER 的 UserMessage
            UserMessage currentUserMsg = null;
            for (ContextContribution c : allContributions) {
                if (c instanceof MessageContextContribution
                        && MessageContextContribution.SOURCE_CURRENT_USER
                                .equals(((MessageContextContribution) c).messageSource())) {
                    List<dev.langchain4j.data.message.ChatMessage> msgs =
                            ((MessageContextContribution) c).messages();
                    if (msgs != null && !msgs.isEmpty()
                            && msgs.get(0) instanceof dev.langchain4j.data.message.UserMessage) {
                        currentUserMsg = (dev.langchain4j.data.message.UserMessage) msgs.get(0);
                    }
                    break;
                }
            }
            return ContextPrepareResult.success(frame, currentUserMsg, cancelChecker, outcomes);
        } finally {
            if (scope != null) scope.close();
            if (prepareSpan != null) prepareSpan.end();
        }
    }

    // ── assemble（迭代级动态 Provider + 消息装配） ──

    /**
     * 每轮迭代装配 — 运行 iteration-dynamic Provider 并调用 ContextMessageAssembler。
     * 每轮动态读取并完成预算、必要时压缩恢复，返回唯一可发送给模型的结果。
     */
    public ContextAssemblyResult assemble(ContextAssemblyRequest request) {
        ContextTraceRecorder traceRecorder = request != null && request.session() != null
                ? new ContextTraceRecorder(request.session().traceContext()) : null;
        Span assembleSpan = traceRecorder != null
                ? traceRecorder.startAssembleSpan(
                        request != null ? request.iteration() : -1,
                        io.opentelemetry.context.Context.current())
                : null;
        io.opentelemetry.context.Scope assembleScope = assembleSpan != null
                ? assembleSpan.makeCurrent() : null;
        try {
            int iteration = request != null ? request.iteration() : -1;
            if (assembleSpan != null) assembleSpan.setAttribute("iteration", iteration);

            // 检查取消
            if (request != null && request.cancelChecker() != null
                    && request.cancelChecker().isCancelled()) {
                return ContextAssemblyResult.failure(
                        ContextErrorCode.CONTEXT_CANCELLED,
                        "cancelled_before_assemble",
                        new ContextAssemblyDebugInfo(List.of(), 0, 0, "cancelled"));
            }

            // 运行动态提供者（传入真实 RequestSession）
            List<ContextProviderOutcome> outcomes = new ArrayList<>();
            List<ContextContribution> dynamicContributions = new ArrayList<>();
            RequestSession reqSession = request != null ? request.session() : null;
            if (reqSession == null) {
                return ContextAssemblyResult.failure(
                        ContextErrorCode.CONTEXT_INTERNAL_ERROR,
                        "ContextAssemblyRequest session is null",
                        new ContextAssemblyDebugInfo(List.of(), 0, 0, "null_session"));
            }

            for (ContextProvider provider : iterationDynamicProviders) {
                // 进入每个 Provider 前先创建 provSpan 并 makeCurrent，span 包裹真实执行生命周期
                Span provSpan = traceRecorder != null
                        ? traceRecorder.startProviderSpan(provider.name(),
                                io.opentelemetry.context.Context.current())
                        : null;
                io.opentelemetry.context.Scope provScope = provSpan != null ? provSpan.makeCurrent() : null;
                long provStartMs = System.currentTimeMillis();
                try {
                    ContextProviderResult result = provider.provide(reqSession, input);
                    validateProductionContributions(provider, result, reqSession);
                    long provDurationMs = System.currentTimeMillis() - provStartMs;
                    if (result.outcome() != null) {
                        outcomes.add(result.outcome());
                    }
                    dynamicContributions.addAll(result.contributions());
                    traceRecorder.finishProviderSpan(provSpan, result,
                            provider.lifecycle().name(), provider.required(reqSession, input), provDurationMs);
                    // required Provider 失败 → 中断 assemble
                    if (result.status() != ContextProviderStatus.SUCCESS && provider.required(reqSession, input)) {
                        return ContextAssemblyResult.failure(
                                result.errorCode() != null ? result.errorCode()
                                        : ContextErrorCode.REQUIRED_PROVIDER_FAILED,
                                "Required provider failed: " + provider.name()
                                        + " - " + (result.errorReason() != null
                                        ? result.errorReason() : "unknown"),
                                new ContextAssemblyDebugInfo(outcomes, 0, 0, "required_provider_failed"));
                    }
                } catch (Exception e) {
                    long provDurationMs = System.currentTimeMillis() - provStartMs;
                    outcomes.add(new ContextProviderOutcome(
                            provider.name(), ContextProviderStatus.FAILED, null,
                            e.getMessage(), provDurationMs));
                    // 异常直接写入同一条 provSpan（不再新建 errProvSpan）
                    if (provSpan != null) {
                        provSpan.setAttribute("provider.name", provider.name());
                        provSpan.setAttribute("provider.status", "FAILED");
                        provSpan.setAttribute("provider.error_reason", e.getMessage());
                        provSpan.setAttribute("provider.duration_ms", provDurationMs);
                        provSpan.end();
                    }
                    // 异常时也检查 required
                    if (provider.required(reqSession, input)) {
                        return ContextAssemblyResult.failure(
                                ContextErrorCode.REQUIRED_PROVIDER_FAILED,
                                "Required provider exception: " + provider.name()
                                        + " - " + e.getMessage(),
                                new ContextAssemblyDebugInfo(outcomes, 0, 0, "required_provider_exception"));
                    }
                } finally {
                    if (provScope != null) provScope.close();
                }
            }

            // 每个动态 Provider 间及 Assembler 前检查取消
            if (request.cancelChecker() != null && request.cancelChecker().isCancelled()) {
                return ContextAssemblyResult.failure(
                        ContextErrorCode.CONTEXT_CANCELLED,
                        "cancelled_during_dynamic_providers",
                        new ContextAssemblyDebugInfo(outcomes, 0, 0, "cancelled"));
            }

            // 合并静态 + 动态贡献
            List<ContextContribution> allContributions = new ArrayList<>();
            if (request.frame() != null) {
                allContributions.addAll(request.frame().contributions());
            }
            allContributions.addAll(dynamicContributions);

            // 先执行一次纯装配预算评估；只有可选贡献裁剪后仍超限，才允许 Memory 恢复一次。
            ContextFrame mergedFrame = ContextFrameBuilder.fromSession(reqSession)
                    .contributions(allContributions)
                    .build();
            ContextTokenEstimator estimator = input.tokenEstimator();
            ContextAssemblyAttempt attempt = ContextMessageAssembler.attempt(
                    mergedFrame, request.budgetPolicy(), estimator, request.iteration(),
                    request.compressionAlreadyAttempted() ? 2 : 1);
            ContextAssemblyResult assembleResult = attempt.candidate();

            if (attempt.compressionRecommended() && !request.compressionAlreadyAttempted()) {
                assembleResult = compactAndRetry(request, reqSession, attempt, outcomes, assembleSpan);
            } else if (assembleResult != null && !assembleResult.success()
                    && assembleResult.errorCode() == ContextErrorCode.CONTEXT_BUDGET_EXCEEDED
                    && request.compressionAlreadyAttempted()) {
                assembleResult = ContextAssemblyResult.failure(
                        ContextErrorCode.CONTEXT_BUDGET_EXCEEDED,
                        "Context budget exceeded after one memory compaction attempt",
                        assembleResult.budgetReport(), assembleResult.debugInfo(),
                        true, outcomes);
            }

            // 合并 outcomes：Assembler 内部不创建 Provider outcome，将 assemble 阶段的 outcomes 附加到结果
            if (assembleResult.success()
                    && (assembleResult.providerOutcomes() == null
                        || assembleResult.providerOutcomes().isEmpty())) {
                assembleResult = ContextAssemblyResult.success(
                        assembleResult.messages(), assembleResult.toolSpecifications(),
                        assembleResult.budgetReport(), assembleResult.debugInfo(), outcomes);
            }

            if (assembleSpan != null) {
                assembleSpan.setAttribute("message.count",
                        assembleResult.messages() != null ? assembleResult.messages().size() : 0);
                assembleSpan.setAttribute("tool.count",
                        assembleResult.toolSpecifications() != null ? assembleResult.toolSpecifications().size() : 0);
                assembleSpan.setAttribute("provider.outcome.count", outcomes.size());
                if (assembleResult.budgetReport() != null) {
                    assembleSpan.setAttribute("tokens.estimated",
                            assembleResult.budgetReport().estimatedInputTokens());
                    assembleSpan.setAttribute("tokens.max",
                            assembleResult.budgetReport().maxInputTokens());
                    assembleSpan.setAttribute("budget.within",
                            assembleResult.budgetReport().withinBudget());
                }
                if (!assembleResult.success() && assembleResult.errorCode() != null) {
                    assembleSpan.setAttribute("error.code", assembleResult.errorCode().name());
                }
            }

            if (assembleResult.success() && !assembleResult.memoryCompacted()) {
                // 记录模型输入片段（fragment / message / toolset），parent = Context.current() = assemble span
                // 遍历合并后的 allContributions，覆盖静态+动态 Provider
                if (traceRecorder != null) {
                    for (ContextContribution contrib : allContributions) {
                        if (!includedInFinalAttempt(contrib, assembleResult.debugInfo())) {
                            continue;
                        }
                        if (contrib instanceof TextContextContribution tc
                                && tc.visibility() == ContextVisibility.MODEL_VISIBLE) {
                            traceRecorder.recordFragment(tc);
                        } else if (contrib instanceof MessageContextContribution mc
                                && mc.visibility() == ContextVisibility.MODEL_VISIBLE) {
                            traceRecorder.recordMessage(mc);
                        } else if (contrib instanceof ToolContextContribution tc2
                                && tc2.visibility() == ContextVisibility.MODEL_VISIBLE) {
                            traceRecorder.recordToolset(tc2);
                        }
                    }
                }
            }

            return assembleResult;
        } finally {
            if (assembleScope != null) assembleScope.close();
            if (assembleSpan != null) assembleSpan.end();
        }
    }

    /**
     * 执行唯一一次 Memory 压缩恢复并重新装配。
     * Context 只决定目标预算和重试次数，摘要生成与原子写回仍由 Memory 模块负责。
     */
    private ContextAssemblyResult compactAndRetry(ContextAssemblyRequest request,
                                                   RequestSession session,
                                                   ContextAssemblyAttempt attempt,
                                                   List<ContextProviderOutcome> outcomes,
                                                   Span assembleSpan) {
        if (request.cancelChecker() != null && request.cancelChecker().isCancelled()) {
            return ContextAssemblyResult.failure(ContextErrorCode.CONTEXT_CANCELLED,
                    "cancelled_before_memory_compaction", attempt.candidate().budgetReport(),
                    attempt.candidate().debugInfo(), false, outcomes);
        }
        ContextMemoryGateway memoryGateway = input.memoryGateway();
        if (memoryGateway == null) {
            return compactionFailure("Memory gateway is unavailable", attempt, outcomes, false);
        }

        MemoryCompactionPlan plan = memoryGateway.planSessionCompaction(
                session.sessionId(), attempt.targetSessionMemoryTokens());
        if (assembleSpan != null) {
            assembleSpan.setAttribute("context.compression.recommended", true);
            assembleSpan.setAttribute("context.compression.target_session_tokens",
                    attempt.targetSessionMemoryTokens());
            assembleSpan.setAttribute("context.compression.tokens_before",
                    plan != null ? plan.currentTokens() : 0);
        }
        if (plan == null || !plan.hasCompactableHistory()) {
            return compactionFailure("No complete historical turns can be compacted",
                    attempt, outcomes, false);
        }

        AgentTraceRecorder trace = session.traceContext() != null
                && session.traceContext().session() != null
                ? new AgentTraceRecorder(session.traceContext().session()) : null;
        MemoryCompactionResult result = memoryGateway.executeCompactionPlan(plan, trace,
                () -> request.cancelChecker() != null
                        && request.cancelChecker().isCancelled());
        if (assembleSpan != null && result != null) {
            assembleSpan.setAttribute("context.compression.executed", result.executed());
            assembleSpan.setAttribute("context.compression.success", result.success());
            assembleSpan.setAttribute("context.compression.tokens_after", result.tokensAfter());
            assembleSpan.setAttribute("context.compression.snapshot_match", result.snapshotMatch());
            assembleSpan.setAttribute("context.compression.reload_required", result.reloadRequired());
        }
        if (result == null || !result.success()) {
            String detail = result != null && result.errorDetail() != null
                    ? result.errorDetail() : "unknown compaction failure";
            if (detail.startsWith("CANCELLED_")) {
                return ContextAssemblyResult.failure(ContextErrorCode.CONTEXT_CANCELLED,
                        detail, attempt.candidate().budgetReport(),
                        attempt.candidate().debugInfo(), result.executed(), outcomes);
            }
            return compactionFailure(detail, attempt, outcomes,
                    result != null && result.executed());
        }
        if (request.cancelChecker() != null && request.cancelChecker().isCancelled()) {
            return ContextAssemblyResult.failure(ContextErrorCode.CONTEXT_CANCELLED,
                    "cancelled_after_memory_compaction", attempt.candidate().budgetReport(),
                    attempt.candidate().debugInfo(), true, outcomes);
        }

        // 重新运行动态 Provider，确保第二次装配读取压缩后的持久化快照，而不是复用旧 Contribution。
        ContextAssemblyResult retry = assemble(new ContextAssemblyRequest(
                request.frame(), request.iteration(), request.budgetPolicy(),
                request.cancelChecker(), true, session));
        return retry != null ? retry.withCompressionState(true,
                result.reloadRequired(), true)
                : compactionFailure("Retry assembly returned null", attempt, outcomes, true);
    }

    private static ContextAssemblyResult compactionFailure(
            String detail, ContextAssemblyAttempt attempt,
            List<ContextProviderOutcome> outcomes,
            boolean compressionAttempted) {
        return ContextAssemblyResult.failure(ContextErrorCode.MEMORY_COMPACTION_FAILED,
                "Memory compaction failed: " + detail,
                attempt.candidate() != null ? attempt.candidate().budgetReport() : null,
                attempt.candidate() != null ? attempt.candidate().debugInfo() : null,
                compressionAttempted, outcomes);
    }

    /** 生产 Provider 的 Contribution 资格必须与集中策略完全一致，防止局部常量再次漂移。 */
    private void validateProductionContributions(ContextProvider provider,
                                                  ContextProviderResult result,
                                                  RequestSession session) {
        if (!ContextPolicies.isRegistered(provider.sourceKey()) || result == null) return;
        ResolvedContextPolicy providerPolicy = ContextPolicies.resolve(
                provider.sourceKey(), session, input);
        if (provider.lifecycle() != providerPolicy.lifecycle()
                || provider.required(session, input) != providerPolicy.required()) {
            throw new IllegalStateException("Provider policy mismatch: " + provider.name());
        }
        for (ContextContribution contribution : result.contributions()) {
            if (!ContextPolicies.isRegistered(contribution.sourceKey())) {
                throw new IllegalStateException("Unknown production sourceKey: "
                        + contribution.sourceKey());
            }
            ResolvedContextPolicy policy = ContextPolicies.resolve(
                    contribution.sourceKey(), session, input);
            if (contribution.lifecycle() != policy.lifecycle()
                    || contribution.visibility() != policy.visibility()
                    || contribution.trustLevel() != policy.trustLevel()
                    || contribution.priority() != policy.priority()
                    || contribution.required() != policy.required()) {
                throw new IllegalStateException("Contribution policy mismatch: "
                        + contribution.sourceKey());
            }
        }
    }

    private static boolean includedInFinalAttempt(ContextContribution contribution,
                                                   ContextAssemblyDebugInfo debugInfo) {
        if (debugInfo == null || debugInfo.contributionDecisions().isEmpty()) return true;
        for (ContextContributionDecision decision : debugInfo.contributionDecisions()) {
            if (contribution.sourceKey().equals(decision.sourceKey())) {
                return decision.included();
            }
        }
        return false;
    }

}
