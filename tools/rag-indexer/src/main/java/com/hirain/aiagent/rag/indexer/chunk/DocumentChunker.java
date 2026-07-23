package com.hirain.aiagent.rag.indexer.chunk;

import com.hirain.aiagent.rag.indexer.model.ParseResult;
import com.hirain.aiagent.rag.indexer.model.SourceDocument;

import java.util.ArrayList;
import java.util.List;

/** G301 文档切分总入口；质量 Gate 必须已在上游通过。 */
public final class DocumentChunker {
    private final ChunkBoundaryPolicy policy;

    public DocumentChunker(ChunkBoundaryPolicy policy) {
        this.policy = policy;
    }

    public ChunkResult chunk(SourceDocument source, ParseResult parsed) {
        List<ParentChunk> parents = new HeadingAwareParentChunker().chunk(source, parsed);
        List<ChildChunk> children = new ArrayList<>();
        for (ParentChunk parent : parents) {
            children.addAll(new ChildChunkSplitter().split(parent, policy));
        }
        return new ChunkResult(parents, children, List.of());
    }
}
