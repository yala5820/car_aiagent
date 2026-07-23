package com.hirain.aiagent.rag.store;

import java.nio.file.Path;

/** 候选目录在提交前的最终验证边界；Android 实现使用目标 Context 打开 ObjectBox。 */
public interface KnowledgeStoreCandidateVerifier {
    KnowledgeStoreValidationResult verify(Path directory, KnowledgeBundleManifest manifest);
}
