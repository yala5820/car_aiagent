package com.hirain.aiagent.rag.store;

/** 仅在候选 Store 已完整验证且切换成功后才可持久化的活动版本指针。 */
public record ActiveBundlePointer(String bundleVersion, String dataSha256, String schemaFingerprint) {
    public ActiveBundlePointer {
        KnowledgeStorageLayout.safeKey(bundleVersion);
        if (dataSha256 == null || !dataSha256.matches("[0-9a-f]{64}") || schemaFingerprint == null || !schemaFingerprint.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException("ACTIVE_POINTER_INVALID");
    }
}
