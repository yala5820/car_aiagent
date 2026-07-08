package com.hirain.aiagent.context;

import com.hirain.aiagent.context.provider.IntentContextProvider;
import com.hirain.aiagent.context.provider.MemoryContextProvider;
import com.hirain.aiagent.context.provider.PersonaContextProvider;
import com.hirain.aiagent.context.provider.PromptContextProvider;
import com.hirain.aiagent.context.provider.RuntimeContextProvider;
import com.hirain.aiagent.context.provider.TimeContextProvider;
import com.hirain.aiagent.context.provider.ToolGroupContextProvider;
import com.hirain.aiagent.context.provider.UserInputContextProvider;
import com.hirain.aiagent.context.provider.VehicleStateContextProvider;
import com.hirain.aiagent.runtime.RequestSession;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Context 构建总入口 — 按 Provider 顺序构建 section、渲染 extraContext、收集诊断信息。
 * <p>
 * 构造函数接受可注入 Provider 列表，{@link #defaultForText(ContextBuildInput)} 提供标准 9-Provider 链。
 */
public class ContextOrchestrator {

    private final ContextBuildInput input;
    private final List<ContextProvider> providers;

    public ContextOrchestrator(ContextBuildInput input, List<ContextProvider> providers) {
        this.input = input;
        this.providers = providers;
    }

    /**
     * 标准 TEXT 请求 Provider 链 — 包含全部 9 个 Provider。
     */
    public static ContextOrchestrator defaultForText(ContextBuildInput input) {
        return new ContextOrchestrator(input, List.of(
                new RuntimeContextProvider(),
                new PersonaContextProvider(),
                new UserInputContextProvider(),
                new IntentContextProvider(),
                new ToolGroupContextProvider(),
                new MemoryContextProvider(),
                new VehicleStateContextProvider(),
                new TimeContextProvider(),
                new PromptContextProvider()
        ));
    }

    /**
     * 构建 Context：按序驱动 Provider、收集 section、渲染 extraContext。
     */
    public ContextBuildResult build(RequestSession session) {
        if (session == null) {
            ContextFrame emptyFrame = new ContextFrameBuilder()
                    .mode(input.mode()).effectivePersonaId("chat")
                    .renderedExtraContext("").build();
            return ContextBuildResult.fallback(emptyFrame, "session_is_null");
        }
        long startMs = System.currentTimeMillis();

        List<ContextSection> sections = new ArrayList<>();
        List<String> providerNames = new ArrayList<>();
        List<String> fallbackProviders = new ArrayList<>();
        Map<String, String> providerErrors = new LinkedHashMap<>();

        // ── 遍历 Provider 链 ──
        for (ContextProvider provider : providers) {
            try {
                ContextProviderResult result = provider.provide(session, input);
                providerNames.add(provider.name());

                if (result.section() != null) {
                    sections.add(result.section());
                }
                if (result.fallback()) {
                    fallbackProviders.add(provider.name());
                }
                if (!result.success()) {
                    providerErrors.put(provider.name(),
                            result.errorReason() != null ? result.errorReason() : "unknown_error");
                }
            } catch (Exception e) {
                providerNames.add(provider.name());
                fallbackProviders.add(provider.name());
                providerErrors.put(provider.name(),
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        }

        // ── 预算裁剪：对每个 renderable section 按 sectionCharLimit 裁剪 ──
        sections = trimSectionsByBudget(sections, input.budgetManager());

        // ── FULL_CONTEXT 降级标记 ──
        if (input.mode() == ContextMode.FULL_CONTEXT) {
            fallbackProviders.add("full_context_deferred");
        }

        // ── 拼接 renderedExtraContext（二次按 totalCharLimit 裁剪） ──
        String rendered = renderExtraContext(sections, input.mode(), input.budgetManager());
        int tokenEst = input.budgetManager().estimateTokens(rendered);

        // ── 构建 DebugInfo ──
        long elapsed = System.currentTimeMillis() - startMs;
        ContextDebugInfo debugInfo = new ContextDebugInfo(
                providerNames, fallbackProviders, providerErrors,
                sections.size(), !fallbackProviders.isEmpty(), elapsed);

        // ── 提取各类型 section content ──
        String memorySummary = findFirstContent(sections, ContextSectionType.MEMORY);
        String vehicleStateSnapshot = findFirstContent(sections, ContextSectionType.VEHICLE_STATE);
        String timeContext = findFirstContent(sections, ContextSectionType.TIME);
        String promptContext = findFirstContent(sections, ContextSectionType.PROMPT);

        // ── 构造 ContextFrame ──
        ContextFrame frame = ContextFrameBuilder.fromSession(session)
                .mode(input.mode())
                .effectivePersonaId(session.personaId())
                .renderedExtraContext(rendered)
                .tokenEstimate(tokenEst)
                .memorySummary(memorySummary)
                .vehicleStateSnapshot(vehicleStateSnapshot)
                .timeContext(timeContext)
                .promptContext(promptContext)
                .sections(sections)
                .debugInfo(debugInfo)
                .build();

        // ── 写入 Trace ──
        ContextTraceRecorder recorder = new ContextTraceRecorder(session.traceContext());
        String firstError = !providerErrors.isEmpty()
                ? providerErrors.values().iterator().next() : null;
        recorder.record(true, input.mode(),
                providers.size(), String.join(",", providerNames),
                frame.selectedToolNames().size(), String.join(",", frame.selectedToolNames()),
                sections.size(), tokenEst,
                !fallbackProviders.isEmpty(), elapsed, firstError);

        if (sections.isEmpty()) {
            return ContextBuildResult.fallback(frame, "all_context_providers_failed");
        }
        return ContextBuildResult.success(frame, !fallbackProviders.isEmpty());
    }

    // ── 渲染规则 ──

    private static String renderExtraContext(List<ContextSection> sections, ContextMode mode,
                                              ContextBudgetManager budgetManager) {
        if (mode == ContextMode.OBSERVE_ONLY) {
            return "";
        }
        // FULL_CONTEXT 一期降级为 HYBRID 行为
        String rendered = sections.stream()
                .filter(ContextSection::renderable)
                .map(ContextSection::content)
                .filter(text -> !text.isEmpty())
                .collect(Collectors.joining("\n\n"));
        // 汇总后按 totalCharLimit 二次裁剪
        String trimmed = budgetManager.trim(rendered, budgetManager.totalCharLimit()).text();
        return trimmed;
    }

    /**
     * 对每个 section 按 sectionCharLimit 裁剪其 content，并更新 charCount/truncated。
     * 不裁剪不可渲染的 section。
     */
    private static List<ContextSection> trimSectionsByBudget(
            List<ContextSection> sections, ContextBudgetManager budgetManager) {
        return sections.stream()
                .map(section -> {
                    if (!section.renderable()) return section;
                    ContextBudgetManager.TrimmedText result =
                            budgetManager.trim(section.content(), budgetManager.sectionCharLimit());
                    if (!result.truncated()) return section;
                    return new ContextSection(section.type(), section.providerName(),
                            section.renderable(), result.text(), result.text().length(),
                            true, section.metadata());
                })
                .collect(Collectors.toList());
    }

    private static String findFirstContent(List<ContextSection> sections, ContextSectionType type) {
        return sections.stream()
                .filter(s -> s.type() == type)
                .findFirst()
                .map(ContextSection::content)
                .orElse("");
    }
}
