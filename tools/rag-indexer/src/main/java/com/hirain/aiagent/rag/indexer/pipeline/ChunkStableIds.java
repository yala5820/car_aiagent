package com.hirain.aiagent.rag.indexer.pipeline;

import java.util.Map;
import com.hirain.aiagent.rag.indexer.chunk.ChildChunk;

/** Parent/Child 的唯一稳定 ID 映射，供 Embedding、Lexical 和 Store 复用。 */
public record ChunkStableIds(Map<Integer, String> parentIds, Map<ChildChunk, String> childIds) {
    public ChunkStableIds { parentIds = Map.copyOf(parentIds); childIds = Map.copyOf(childIds); }
}
