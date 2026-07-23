package com.hirain.aiagent.rag.indexer.chunk;

import java.util.List;

/** 单文档 Chunk 输出，含 Parent、Child 与不可忽略的结构诊断。 */
public record ChunkResult(List<ParentChunk> parents, List<ChildChunk> children, List<ChunkDiagnostic> diagnostics) {
    public ChunkResult {
        parents = List.copyOf(parents);
        children = List.copyOf(children);
        diagnostics = List.copyOf(diagnostics);
    }
}
