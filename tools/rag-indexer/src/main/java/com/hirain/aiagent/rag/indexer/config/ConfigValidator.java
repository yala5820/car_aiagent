package com.hirain.aiagent.rag.indexer.config;

/**
 * 配置语义校验的稳定扩展点。当前硬不变量已由 Loader 在构造快照前验证；后续新增跨域约束
 * 必须加入这里，不能散落到 Parser、Chunk 或 Store 阶段。
 */
public final class ConfigValidator {
    public void validate(RagBuildConfig config) {
        if (config == null || config.fingerprint() == null || !config.fingerprint().matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("构建配置指纹无效");
        }
        ChunkingConfig chunking = config.chunkingConfig();
        if (chunking == null || chunking.configVersion() == 2
                && (chunking.childMergeMaxTokens() < chunking.childTargetTokens()
                || chunking.parentSplitOverlapRatio() < 0.0d
                || chunking.parentSplitOverlapRatio() > 0.20d
                || chunking.paragraphCosineThreshold() <= 0.0d
                || chunking.paragraphCosineThreshold() > 1.0d
                || chunking.childForceMergeMaxTokens() < 1
                || chunking.childForceMergeMaxTokens() >= chunking.childDirectMergeMaxTokens()
                || chunking.childDirectMergeMaxTokens() >= chunking.childIdealMinTokens()
                || chunking.pdfTocReferenceVersion() == null
                || chunking.pdfTocReferenceVersion().isBlank())) {
            throw new IllegalArgumentException("分块策略配置无效");
        }
    }
}
