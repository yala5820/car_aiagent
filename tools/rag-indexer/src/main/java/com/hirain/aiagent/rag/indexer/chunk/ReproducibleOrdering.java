package com.hirain.aiagent.rag.indexer.chunk;

import java.util.Comparator;
import java.util.List;

/** 下游写入前固定 Parent/Child 排序，避免并行或 Map 遍历顺序改变 Bundle。 */
public final class ReproducibleOrdering {
    public List<ParentChunk> parents(List<ParentChunk> values) {
        return values.stream().sorted(Comparator.comparing(ParentChunk::documentId).thenComparingInt(ParentChunk::ordinal)).toList();
    }

    public List<ChildChunk> children(List<ChildChunk> values) {
        return values.stream().sorted(Comparator.comparingInt(ChildChunk::parentOrdinal).thenComparingInt(ChildChunk::ordinal)).toList();
    }
}
