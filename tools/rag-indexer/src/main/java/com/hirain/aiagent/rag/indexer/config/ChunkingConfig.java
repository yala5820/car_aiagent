package com.hirain.aiagent.rag.indexer.config;

import com.hirain.aiagent.rag.contract.RagTokenEstimator;

/** V1 历史与 V2 Parent-Child 分块参数的不可变配置快照。 */
public final class ChunkingConfig {
    private final int configVersion;
    private final String tokenEstimatorVersion;
    private final int parentIdealMinTokens, parentSoftMaxTokens, parentHardMaxTokens;
    private final int childIdealMinTokens, childTargetTokens, childSoftMaxTokens, childHardMaxTokens;
    private final double fallbackOverlapMinRatio, fallbackOverlapMaxRatio;
    private final int tableRowsPerChild;
    private final String semanticStrategyVersion;

    /** 仅供现有 V1 配置与 Fixture 使用。 */
    public ChunkingConfig(int maxChildTokens, int overlapTokens, int tableRowsPerChild) {
        this(1, "rag-token-estimator-v1", 0, 0, 0, 0, maxChildTokens, maxChildTokens, maxChildTokens,
                (double) overlapTokens / maxChildTokens, (double) overlapTokens / maxChildTokens, tableRowsPerChild, "heading-aware-v1");
    }

    public ChunkingConfig(int configVersion, String tokenEstimatorVersion, int parentIdealMinTokens, int parentSoftMaxTokens,
                          int parentHardMaxTokens, int childIdealMinTokens, int childTargetTokens, int childSoftMaxTokens,
                          int childHardMaxTokens, double fallbackOverlapMinRatio, double fallbackOverlapMaxRatio,
                          int tableRowsPerChild, String semanticStrategyVersion) {
        if (configVersion == 1) {
            if (childTargetTokens < 32 || tableRowsPerChild < 1) throw new IllegalArgumentException("CHUNK_POLICY_INVALID");
        } else if (configVersion == 2) {
            if (!RagTokenEstimator.VERSION.equals(tokenEstimatorVersion) || parentIdealMinTokens < 0
                    || parentSoftMaxTokens < parentIdealMinTokens || parentHardMaxTokens < parentSoftMaxTokens
                    || childIdealMinTokens < 0 || childTargetTokens < childIdealMinTokens || childSoftMaxTokens < childTargetTokens
                    || childHardMaxTokens < childSoftMaxTokens || fallbackOverlapMinRatio < 0 || fallbackOverlapMaxRatio < fallbackOverlapMinRatio
                    || fallbackOverlapMaxRatio > 0.10d || tableRowsPerChild < 1 || semanticStrategyVersion == null || semanticStrategyVersion.isBlank()) {
                throw new IllegalArgumentException("CHUNK_POLICY_INVALID");
            }
        } else throw new IllegalArgumentException("CHUNK_CONFIG_VERSION_UNSUPPORTED");
        this.configVersion=configVersion; this.tokenEstimatorVersion=tokenEstimatorVersion; this.parentIdealMinTokens=parentIdealMinTokens;
        this.parentSoftMaxTokens=parentSoftMaxTokens; this.parentHardMaxTokens=parentHardMaxTokens; this.childIdealMinTokens=childIdealMinTokens;
        this.childTargetTokens=childTargetTokens; this.childSoftMaxTokens=childSoftMaxTokens; this.childHardMaxTokens=childHardMaxTokens;
        this.fallbackOverlapMinRatio=fallbackOverlapMinRatio; this.fallbackOverlapMaxRatio=fallbackOverlapMaxRatio;
        this.tableRowsPerChild=tableRowsPerChild; this.semanticStrategyVersion=semanticStrategyVersion;
    }
    public static ChunkingConfig v1Default(){return new ChunkingConfig(256,32,20);}
    public int configVersion(){return configVersion;} public String tokenEstimatorVersion(){return tokenEstimatorVersion;}
    public int parentIdealMinTokens(){return parentIdealMinTokens;} public int parentSoftMaxTokens(){return parentSoftMaxTokens;} public int parentHardMaxTokens(){return parentHardMaxTokens;}
    public int childIdealMinTokens(){return childIdealMinTokens;} public int childTargetTokens(){return childTargetTokens;} public int childSoftMaxTokens(){return childSoftMaxTokens;} public int childHardMaxTokens(){return childHardMaxTokens;}
    public double fallbackOverlapMinRatio(){return fallbackOverlapMinRatio;} public double fallbackOverlapMaxRatio(){return fallbackOverlapMaxRatio;}
    public int tableRowsPerChild(){return tableRowsPerChild;} public String semanticStrategyVersion(){return semanticStrategyVersion;}
    /** 兼容 V1 调用点；V2 返回 Child 目标长度。 */ public int maxChildTokens(){return childTargetTokens;}
    /** V2 只在长度兜底阶段计算 overlap，因此默认返回 0。 */ public int overlapTokens(){return configVersion == 1 ? (int)Math.round(childTargetTokens * fallbackOverlapMaxRatio) : 0;}
}
