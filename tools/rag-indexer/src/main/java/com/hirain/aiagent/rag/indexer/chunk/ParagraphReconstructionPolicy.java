package com.hirain.aiagent.rag.indexer.chunk;

/** 视觉行恢复段落的几何阈值；只限制合并条件，不把固定长度当作段落边界。 */
public record ParagraphReconstructionPolicy(float lineGapMultiplier, float maxLineGap,
                                             float horizontalTolerance, float overlapRatio) {
    public ParagraphReconstructionPolicy {
        if (lineGapMultiplier <= 0 || maxLineGap <= 0 || horizontalTolerance < 0
                || overlapRatio < 0 || overlapRatio > 0.2f) throw new IllegalArgumentException("PARAGRAPH_POLICY_INVALID");
    }

    public static ParagraphReconstructionPolicy defaults() {
        return new ParagraphReconstructionPolicy(1.8f, 18.0f, 24.0f, 0.10f);
    }
}
