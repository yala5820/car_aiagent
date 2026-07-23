package com.hirain.aiagent.rag.indexer.store;

import com.hirain.aiagent.rag.indexer.lexical.LexicalIndex;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;
import com.hirain.aiagent.rag.store.entity.KnowledgeDocumentEntity;
import com.hirain.aiagent.rag.store.entity.KnowledgeStoreMetadataEntity;

import java.util.List;

/** G401 的写库前快照；G402 只接受已经通过本模型校验的实体与逻辑 postings。 */
public record StoreWriteModel(
        KnowledgeStoreMetadataEntity metadata,
        List<KnowledgeDocumentEntity> documents,
        List<KnowledgeChunkEntity> chunks,
        LexicalIndex lexicalIndex) {
    public StoreWriteModel { documents = List.copyOf(documents); chunks = List.copyOf(chunks); }
}
