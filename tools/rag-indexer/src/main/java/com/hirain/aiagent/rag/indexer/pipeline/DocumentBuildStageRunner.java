package com.hirain.aiagent.rag.indexer.pipeline;

import com.hirain.aiagent.rag.indexer.chunk.DocumentChunker;
import com.hirain.aiagent.rag.indexer.parser.DocumentParsingPipeline;

/** 将已验证输入实际送入 Parser 质量 Gate 和 Chunk；失败时不产生可继续写库的状态。 */
public final class DocumentBuildStageRunner {
    private final DocumentParsingPipeline parser;
    private final DocumentChunker chunker;
    public DocumentBuildStageRunner(DocumentParsingPipeline parser, DocumentChunker chunker) { this.parser = parser; this.chunker = chunker; }
    public DocumentBuildState parseAndChunk(DocumentBuildState state, BuildCancellationToken token) {
        token.throwIfCancelled();
        var parsed = parser.parse(state.sourceDocument());
        token.throwIfCancelled();
        return state.withParse(parsed).withChunks(chunker.chunk(state.sourceDocument(), parsed));
    }
}
