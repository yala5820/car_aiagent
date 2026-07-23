package com.hirain.aiagent.rag.indexer.store;

import com.hirain.aiagent.rag.indexer.chunk.ChildChunk;
import com.hirain.aiagent.rag.indexer.chunk.ParentChunk;
import com.hirain.aiagent.rag.indexer.chunk.TokenEstimator;
import com.hirain.aiagent.rag.indexer.corpus.CorpusDocumentDefinition;
import com.hirain.aiagent.rag.indexer.model.SourceFormat;
import com.hirain.aiagent.rag.indexer.model.SourceLocator;
import com.hirain.aiagent.rag.store.KnowledgeStoreContract;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;

/** Parent/Child 的受控映射；Child Metadata 仅从 Corpus applicability 冗余复制。 */
public final class KnowledgeChunkEntityMapper {
    public KnowledgeChunkEntity mapParent(String chunkId, ParentChunk parent, CorpusDocumentDefinition document) {
        KnowledgeChunkEntity entity = base(chunkId, "PARENT", "", parent.documentId(), parent.title(), parent.headingPath(),
                parent.text(), "SECTION", parent.locator(), parent.ordinal(), document);
        entity.embedding = null;
        entity.lexicalDocumentLength = 0;
        return entity;
    }

    public KnowledgeChunkEntity mapChild(String chunkId, String parentChunkId, ParentChunk parent, ChildChunk child,
                                         CorpusDocumentDefinition document, int lexicalDocumentLength, float[] embedding) {
        if (lexicalDocumentLength < 0) throw new IllegalArgumentException("Child lexicalDocumentLength 非法");
        validateEmbedding(embedding);
        KnowledgeChunkEntity entity = base(chunkId, "CHILD", parentChunkId, parent.documentId(), parent.title(), parent.headingPath(),
                child.text(), child.evidenceType(), child.locator(), child.ordinal(), document);
        entity.embedding = embedding.clone();
        entity.lexicalDocumentLength = lexicalDocumentLength;
        return entity;
    }

    private KnowledgeChunkEntity base(String chunkId, String level, String parentChunkId, String documentId, String title,
                                      String headingPath, String content, String chunkType, SourceLocator locator, int ordinal,
                                      CorpusDocumentDefinition document) {
        if (!document.documentId().equals(documentId)) throw new IllegalArgumentException("Chunk 与 Corpus Document 不一致");
        KnowledgeChunkEntity entity = new KnowledgeChunkEntity();
        entity.chunkId = chunkId; entity.chunkLevel = level; entity.parentChunkId = parentChunkId; entity.documentId = documentId;
        entity.documentTitle = title; entity.documentVersion = document.documentVersion(); entity.documentType = document.documentType();
        entity.language = document.language(); entity.vehicleModel = document.applicability().vehicleModel();
        entity.modelYear = document.applicability().modelYear(); entity.region = document.applicability().region();
        entity.softwareVersion = document.applicability().softwareVersion(); entity.configurationCode = document.applicability().configurationCode();
        entity.chunkType = chunkType; entity.headingPath = HeadingPathCodec.encode(headingPath); entity.content = content;
        entity.ordinal = ordinal; entity.tokenEstimate = new TokenEstimator().estimate(content);
        applyLocator(entity, locator);
        return entity;
    }

    private static void applyLocator(KnowledgeChunkEntity entity, SourceLocator locator) {
        entity.sourceFormat = locator.sourceFormat().name(); entity.sectionOrdinal = locator.sectionOrdinal();
        entity.pdfPageStart = 0; entity.pdfPageEnd = 0; entity.printedPageStartLabel = ""; entity.printedPageEndLabel = "";
        entity.htmlElementId = ""; entity.sourceLineStart = 0; entity.sourceLineEnd = 0;
        if (locator.sourceFormat() == SourceFormat.PDF) { entity.pdfPageStart = locator.pdfPageStart(); entity.pdfPageEnd = locator.pdfPageEnd(); }
        else if (locator.sourceFormat() == SourceFormat.STATIC_HTML) entity.htmlElementId = locator.htmlElementId() == null ? "" : locator.htmlElementId();
        else if (locator.sourceFormat() == SourceFormat.MARKDOWN) { entity.sourceLineStart = locator.sourceLineStart(); entity.sourceLineEnd = locator.sourceLineEnd(); }
    }

    private static void validateEmbedding(float[] embedding) {
        if (embedding == null || embedding.length != KnowledgeStoreContract.EMBEDDING_DIMENSION) throw new IllegalArgumentException("Child 向量维度非法");
        for (float value : embedding) if (!Float.isFinite(value)) throw new IllegalArgumentException("Child 向量包含非有限数值");
    }
}
