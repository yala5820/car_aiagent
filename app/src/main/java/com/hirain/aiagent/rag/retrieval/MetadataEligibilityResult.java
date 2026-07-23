package com.hirain.aiagent.rag.retrieval;

/** Metadata 资格结果用于检索诊断；拒绝理由不含原始车辆 Profile。 */
public record MetadataEligibilityResult(boolean eligible, String reasonCode) {
    public static MetadataEligibilityResult accepted() { return new MetadataEligibilityResult(true, "OK"); }
    public static MetadataEligibilityResult rejected(String reason) { return new MetadataEligibilityResult(false, reason); }
}
