package com.hirain.aiagent.rag.indexer.pipeline;

import com.hirain.aiagent.rag.indexer.lexical.LexicalIndex;
import com.hirain.aiagent.rag.indexer.store.KnowledgeChunkEntityMapper;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;

import java.util.ArrayList;
import java.util.List;

/** 将已验证的运行状态转换为 Entity 前的严格映射；不在此阶段写 ObjectBox。 */
public final class StoreModelStageAssembler {
    private final KnowledgeChunkEntityMapper mapper = new KnowledgeChunkEntityMapper();
    public List<KnowledgeChunkEntity> mapChunks(DocumentBuildState state, ChunkStableIds ids, LexicalIndex lexical) {
        if (state.chunkResult() == null || state.childEmbeddings().isEmpty()) throw new IllegalStateException("Chunk 或 Embedding 阶段未完成");
        List<KnowledgeChunkEntity> result = new ArrayList<>();
        var parents = new java.util.HashMap<Integer, com.hirain.aiagent.rag.indexer.chunk.ParentChunk>();
        for (var parent : state.chunkResult().parents()) { parents.put(parent.ordinal(), parent); result.add(mapper.mapParent(ids.parentIds().get(parent.ordinal()), parent, state.corpusDocument())); }
        for (var child : state.chunkResult().children()) {
            var parent = parents.get(child.parentOrdinal()); String childId = ids.childIds().get(child); String parentId = ids.parentIds().get(child.parentOrdinal());
            if (parent == null || childId == null || parentId == null) throw new IllegalStateException("Chunk 稳定 ID 映射缺失");
            int titleLength = lexical.titleLengths().getOrDefault(childId, -1);
            int bodyLength = lexical.bodyLengths().getOrDefault(childId, -1);
            float[] vector = state.childEmbeddings().get(childId);
            result.add(mapper.mapChild(childId, parentId, parent, child, state.corpusDocument(), titleLength, bodyLength, vector));
        }
        return List.copyOf(result);
    }
}
