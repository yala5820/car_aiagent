package com.hirain.aiagent.rag.retrieval;

import com.hirain.aiagent.rag.model.VehicleProfile;
import com.hirain.aiagent.rag.profile.KnowledgeScopeResolution;
import com.hirain.aiagent.rag.profile.KnowledgeScopeResolver;
import com.hirain.aiagent.rag.store.KnowledgeStoreGateway;

/** 检索前先验证可信车辆 Scope 与当前 Active Store，错误 Scope 时禁止产生任何候选。 */
public final class StoreScopeEligibilityPolicy {
    private final KnowledgeScopeResolver resolver = new KnowledgeScopeResolver();
    public MetadataEligibilityResult validate(KnowledgeStoreGateway gateway, VehicleProfile profile) {
        if (gateway == null) return MetadataEligibilityResult.rejected("KNOWLEDGE_STORE_UNAVAILABLE");
        KnowledgeScopeResolution scope = resolver.resolve(profile);
        if (!scope.resolved()) return MetadataEligibilityResult.rejected(scope.failureReason().name());
        return scope.knowledgeScopeId().equals(gateway.metadata().knowledgeScopeId) ? MetadataEligibilityResult.accepted() : MetadataEligibilityResult.rejected("KNOWLEDGE_SCOPE_MISMATCH");
    }
}
