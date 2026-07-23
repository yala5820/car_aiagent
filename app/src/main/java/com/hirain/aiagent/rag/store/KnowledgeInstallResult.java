package com.hirain.aiagent.rag.store;

/** 安装结果只使用稳定原因码，不将文件路径或底层异常暴露给 Agent。 */
public record KnowledgeInstallResult(boolean installed, boolean reused, String reasonCode, KnowledgeBundleManifest manifest) {
    public static KnowledgeInstallResult installed(KnowledgeBundleManifest manifest) { return new KnowledgeInstallResult(true, false, "OK", manifest); }
    public static KnowledgeInstallResult reused(KnowledgeBundleManifest manifest) { return new KnowledgeInstallResult(false, true, "OK", manifest); }
    public static KnowledgeInstallResult failed(String reasonCode) { return new KnowledgeInstallResult(false, false, reasonCode, null); }
}
