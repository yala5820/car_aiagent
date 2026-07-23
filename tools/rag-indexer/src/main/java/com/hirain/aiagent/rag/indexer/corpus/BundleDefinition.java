package com.hirain.aiagent.rag.indexer.corpus;

/** Bundle 版本必须由发布流程显式提供，不能根据内容自动猜测。 */
public record BundleDefinition(String bundleId, String bundleVersion, String knowledgeScopeId, KnowledgeScopeDefinition scope) {
    public BundleDefinition {
        if (bundleId == null || bundleId.isBlank() || bundleVersion == null || bundleVersion.isBlank()
                || knowledgeScopeId == null || knowledgeScopeId.isBlank()) {
            throw new IllegalArgumentException("Bundle 必填字段不能为空");
        }
    }
}
