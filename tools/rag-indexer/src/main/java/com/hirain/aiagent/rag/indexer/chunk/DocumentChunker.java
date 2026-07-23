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
        boolean v2Structure = parsed.blocks().stream().anyMatch(block -> block.structure().headingLevel() > 0);
        SectionParentChunker.ParentChunkingResult v2 = v2Structure ? new SectionParentChunker().chunk(source, parsed) : null;
        List<ParentChunk> parents = v2 != null ? v2.parents() : new HeadingAwareParentChunker().chunk(source, parsed);
        List<ChildChunk> children = new ArrayList<>(); List<ChunkDiagnostic> diagnostics=new ArrayList<>(v2 == null ? List.of() : v2.diagnostics());
        for (ParentChunk parent : parents) {
            if(v2Structure){SemanticChildSplitter.Result split=new SemanticChildSplitter().split(parent,policy);children.addAll(split.children());diagnostics.addAll(split.diagnostics());}
            else children.addAll(new ChildChunkSplitter().split(parent, policy));
        }
        return new ChunkResult(parents, children, diagnostics);
    }
}
