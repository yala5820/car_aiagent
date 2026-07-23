package com.hirain.aiagent.rag.indexer.pipeline;

/** Validate 仅执行配置/Corpus/输入安全前置检查，禁止调用网络、Parser 或写 Store。 */
public final class ValidatePipeline {
    public void execute(BuildExecutionContext context, Runnable validation) { context.cancellationToken().throwIfCancelled(); validation.run(); }
}
