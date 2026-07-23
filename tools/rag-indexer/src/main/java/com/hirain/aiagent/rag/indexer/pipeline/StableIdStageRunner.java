package com.hirain.aiagent.rag.indexer.pipeline;

import com.hirain.aiagent.rag.indexer.chunk.StableIdGenerator;
import java.util.LinkedHashMap;

/** 根据已完成 Chunk 的确定顺序只生成一次 ID；Child 通过 parentOrdinal 绑定其 Parent。 */
public final class StableIdStageRunner {
    public ChunkStableIds assign(DocumentBuildState state) {
        if (state.chunkResult() == null) throw new IllegalStateException("Chunk 阶段尚未完成");
        StableIdGenerator generator = new StableIdGenerator(); var parents = new LinkedHashMap<Integer, String>(); var children = new LinkedHashMap<com.hirain.aiagent.rag.indexer.chunk.ChildChunk, String>();
        for (var parent : state.chunkResult().parents()) parents.put(parent.ordinal(), generator.parentId(parent));
        for (var child : state.chunkResult().children()) { String parentId = parents.get(child.parentOrdinal()); if (parentId == null) throw new IllegalStateException("Child 引用未知 Parent"); children.put(child, generator.childId(child, parentId)); }
        return new ChunkStableIds(parents, children);
    }
}
