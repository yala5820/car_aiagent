package com.hirain.aiagent.rag.indexer.pipeline;

import java.util.List;

/** 固定调度顺序的编排器；具体 Parser/Embedding/Store 实现在已完成阶段提供，此处不允许跳过 Verify 直接发布。 */
public final class BuildPipeline {
    private final List<BuildPhase> order = List.of(BuildPhase.VALIDATED, BuildPhase.PARSED, BuildPhase.CHUNKED,
            BuildPhase.EMBEDDED, BuildPhase.INDEXED, BuildPhase.STORE_WRITTEN, BuildPhase.VERIFIED);

    public void execute(BuildExecutionContext context, PhaseExecutor executor, BuildProgressListener listener) {
        for (BuildPhase phase : order) {
            context.cancellationToken().throwIfCancelled(); listener.onPhase(phase); executor.execute(phase, context);
        }
    }
    @FunctionalInterface public interface PhaseExecutor { void execute(BuildPhase phase, BuildExecutionContext context); }
}
