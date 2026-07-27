package com.hirain.aiagent.rag.retrieval;

import com.hirain.aiagent.rag.store.KnowledgeStoreGateway;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;
import java.util.List;

/** Parent 仅在 Rerank 后按已选 Child 的 parentChunkId 补全，不参与默认召回。 */
public final class ParentContextResolver {
    public List<KnowledgeChunkEntity> resolve(KnowledgeStoreGateway gateway, List<KnowledgeChunkEntity> selectedChildren) {
        if (gateway == null || selectedChildren == null || selectedChildren.isEmpty()) return List.of();
        java.util.ArrayList<String> parentIds = new java.util.ArrayList<>(); for (KnowledgeChunkEntity child : selectedChildren) if (child != null && child.parentChunkId != null && !child.parentChunkId.isBlank()) parentIds.add(child.parentChunkId);
        return gateway.parentChunksByIds(parentIds);
    }
}
