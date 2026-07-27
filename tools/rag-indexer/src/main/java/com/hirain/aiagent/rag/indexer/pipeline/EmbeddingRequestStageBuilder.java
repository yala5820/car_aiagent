package com.hirain.aiagent.rag.indexer.pipeline;

import com.hirain.aiagent.rag.indexer.chunk.EmbeddingTextRenderer;
import com.hirain.aiagent.rag.indexer.embedding.EmbeddingRequest;

import java.util.ArrayList;
import java.util.List;

/** 仅从已生成的稳定 ID 与 V2 标题模板构造请求，禁止由 API 返回顺序或对象地址定义 Child 身份。 */
public final class EmbeddingRequestStageBuilder {
    public List<EmbeddingRequest> build(DocumentBuildState state, ChunkStableIds ids) {
        if (state.chunkResult() == null) throw new IllegalStateException("Chunk 阶段尚未完成");
        var parents = new java.util.HashMap<Integer, com.hirain.aiagent.rag.indexer.chunk.ParentChunk>();
        state.chunkResult().parents().forEach(parent -> parents.put(parent.ordinal(), parent));
        List<EmbeddingRequest> requests = new ArrayList<>(); int index = 0;
        for (var child : state.chunkResult().children()) {
            String childId = ids.childIds().get(child); var parent = parents.get(child.parentOrdinal());
            if (childId == null || parent == null) throw new IllegalStateException("稳定 Child ID 或 Parent 缺失");
            requests.add(new EmbeddingRequest(index++, childId, new EmbeddingTextRenderer().render(parent, child)));
        }
        return List.copyOf(requests);
    }
}
