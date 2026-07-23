package com.hirain.aiagent.rag.indexer.pipeline;

import com.hirain.aiagent.rag.indexer.chunk.ChunkResult;
import com.hirain.aiagent.rag.indexer.corpus.CorpusDocumentDefinition;
import com.hirain.aiagent.rag.indexer.model.ParseResult;
import com.hirain.aiagent.rag.indexer.model.SourceDocument;

import java.util.Map;

/** 单份文档从已验证声明到 Chunk/Embedding 的不可变状态，禁止在下游通过正文重建 Corpus Metadata。 */
public record DocumentBuildState(CorpusDocumentDefinition corpusDocument, SourceDocument sourceDocument,
                                 ParseResult parseResult, ChunkResult chunkResult, Map<String, float[]> childEmbeddings) {
    public DocumentBuildState {
        if (corpusDocument == null || sourceDocument == null) throw new IllegalArgumentException("文档构建状态缺少已验证输入");
        childEmbeddings = childEmbeddings == null ? Map.of() : Map.copyOf(childEmbeddings);
    }
    public DocumentBuildState withParse(ParseResult value) { return new DocumentBuildState(corpusDocument, sourceDocument, value, chunkResult, childEmbeddings); }
    public DocumentBuildState withChunks(ChunkResult value) { return new DocumentBuildState(corpusDocument, sourceDocument, parseResult, value, childEmbeddings); }
    public DocumentBuildState withEmbeddings(Map<String, float[]> value) { return new DocumentBuildState(corpusDocument, sourceDocument, parseResult, chunkResult, value); }
}
