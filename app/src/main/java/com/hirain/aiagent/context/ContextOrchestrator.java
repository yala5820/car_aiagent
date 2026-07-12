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
import com.hirain.aiagent.runtime.RequestSession;
import com.hirain.aiagent.runtime.RequestSessionFactory;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Context 构建总入口 — 实现 {@link ContextPreparer} 和 {@link ContextAssemblyGateway}。
 * <p>
 * Phase 2 新增 prepare() 和 assemble() 两条链路：
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
                long provStartMs = System.currentTimeMillis();
                try {
                    ContextProviderResult result = provider.provide(session, input);
                    long provDurationMs = System.currentTimeMillis() - provStartMs;
                    allContributions.addAll(result.contributions());
                    if (result.outcome() != null) {
                        outcomes.add(result.outcome());
                    }
                    // Provider span（替代旧 event 机制）
                    Span provSpan = traceRecorder != null
                            ? traceRecorder.startProviderSpan(provider.name(),
                                    io.opentelemetry.context.Context.current())
                            : null;
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
                    // Provider span for exception
                    Span errProvSpan = traceRecorder != null
                            ? traceRecorder.startProviderSpan(provider.name(),
                                    io.opentelemetry.context.Context.current())
                            : null;
                    if (errProvSpan != null) {
                        errProvSpan.setAttribute("provider.name", provider.name());
                        errProvSpan.setAttribute("provider.status", "FAILED");
                        errProvSpan.setAttribute("provider.error_reason", e.getMessage());
                        errProvSpan.setAttribute("provider.duration_ms", provDurationMs);
                        errProvSpan.end();
                    }
                    if (provider.required(session, input)) {
                        if (prepareSpan != null) prepareSpan.setAttribute("required_provider_failed", provider.name());
                        return ContextPrepareResult.failed(
                                ContextErrorCode.REQUIRED_PROVIDER_FAILED,
                                "Required provider threw exception: " + provider.name()
                                        + " - " + e.getMessage());
                    }
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
     * Phase 2 仅建立方法骨架，Phase 3 影子装配才完整调用。
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
                long provStartMs = System.currentTimeMillis();
                try {
                    ContextProviderResult result = provider.provide(reqSession, input);
                    long provDurationMs = System.currentTimeMillis() - provStartMs;
                    if (result.outcome() != null) {
                        outcomes.add(result.outcome());
                    }
                    dynamicContributions.addAll(result.contributions());
                    // Provider span（替代旧 event 机制）
                    Span provSpan = traceRecorder != null
                            ? traceRecorder.startProviderSpan(provider.name(),
                                    io.opentelemetry.context.Context.current())
                            : null;
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
                    // Provider span for exception
                    Span errProvSpan = traceRecorder != null
                            ? traceRecorder.startProviderSpan(provider.name(),
                                    io.opentelemetry.context.Context.current())
                            : null;
                    if (errProvSpan != null) {
                        errProvSpan.setAttribute("provider.name", provider.name());
                        errProvSpan.setAttribute("provider.status", "FAILED");
                        errProvSpan.setAttribute("provider.error_reason", e.getMessage());
                        errProvSpan.setAttribute("provider.duration_ms", provDurationMs);
                        errProvSpan.end();
                    }
                    // 异常时也检查 required
                    if (provider.required(reqSession, input)) {
                        return ContextAssemblyResult.failure(
                                ContextErrorCode.REQUIRED_PROVIDER_FAILED,
                                "Required provider exception: " + provider.name()
                                        + " - " + e.getMessage(),
                                new ContextAssemblyDebugInfo(outcomes, 0, 0, "required_provider_exception"));
                    }
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

            // 调用 ContextMessageAssembler 生成消息
            ContextFrame mergedFrame = ContextFrameBuilder.fromSession(reqSession)
                    .contributions(allContributions)
                    .build();
            ContextTokenEstimator estimator = input.tokenEstimator();
            ContextAssemblyResult assembleResult = ContextMessageAssembler.assemble(
                    mergedFrame, request.budgetPolicy(), estimator, request.iteration());

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

            // 记录装配消息内容到 span（兼容旧字段 — 主排障已迁移至下方 fragment/message span）
            // legacy recordAssembledMessages / recordAssembleMessagesAsEvents 已停止默认写入，
            // 如需恢复可在调试阶段取消下面注释。
            // traceRecorder.recordAssembledMessages(assembleSpan, assembleResult.messages(), assembleResult.toolSpecifications());
            // traceRecorder.recordAssembleMessagesAsEvents(assembleSpan, assembleResult.messages());
            if (assembleResult.success()) {
                // 记录模型输入片段（fragment / message / toolset），parent = Context.current() = assemble span
                // 遍历合并后的 allContributions，覆盖静态+动态 Provider
                if (traceRecorder != null) {
                    for (ContextContribution contrib : allContributions) {
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

}
