package com.hirain.aiagent.rag.indexer.store;

import com.hirain.aiagent.rag.indexer.lexical.LexicalPosting;
import com.hirain.aiagent.rag.store.KnowledgeStoreContract;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 写入 ObjectBox 前的全量逻辑交叉引用校验，避免把错误交给运行时查询才暴露。 */
public final class StoreModelValidator {
    public void validate(StoreWriteModel model) {
        if (model.metadata() == null || model.metadata().documentCount != model.documents().size()) throw new IllegalArgumentException("Store Metadata/Document 计数不一致");
        Set<String> documentIds = new HashSet<>();
        model.documents().forEach(document -> { if (!documentIds.add(document.documentId)) throw new IllegalArgumentException("重复 Document ID"); });
        Map<String, KnowledgeChunkEntity> chunks = new HashMap<>();
        int parentCount = 0, childCount = 0;
        for (KnowledgeChunkEntity chunk : model.chunks()) {
            if (!chunks.containsKey(chunk.chunkId) && !documentIds.contains(chunk.documentId)) throw new IllegalArgumentException("Chunk 引用未知 Document");
            if (chunks.put(chunk.chunkId, chunk) != null) throw new IllegalArgumentException("重复 Chunk ID");
            if ("PARENT".equals(chunk.chunkLevel)) { parentCount++; if (chunk.embedding != null || !chunk.parentChunkId.isEmpty()) throw new IllegalArgumentException("Parent 字段非法"); }
            else if ("CHILD".equals(chunk.chunkLevel)) { childCount++; }
            else throw new IllegalArgumentException("未知 Chunk Level");
        }
        if (parentCount != model.metadata().parentChunkCount || childCount != model.metadata().childChunkCount) throw new IllegalArgumentException("Chunk 计数不一致");
        chunks.values().stream().filter(chunk -> "CHILD".equals(chunk.chunkLevel)).forEach(child -> validateChild(child, chunks));
        model.lexicalIndex().postingsByTerm().forEach((term, postings) -> {
            for (LexicalPosting posting : postings) {
                KnowledgeChunkEntity child = chunks.get(posting.childId());
                if (child == null || !"CHILD".equals(child.chunkLevel)) throw new IllegalArgumentException("词法 posting 引用非 Child");
            }
        });
    }

    private static void validateChild(KnowledgeChunkEntity child, Map<String, KnowledgeChunkEntity> chunks) {
        KnowledgeChunkEntity parent = chunks.get(child.parentChunkId);
        if (parent == null || !"PARENT".equals(parent.chunkLevel) || !parent.documentId.equals(child.documentId)) throw new IllegalArgumentException("Child Parent 引用非法");
        if (child.embedding == null || child.embedding.length != KnowledgeStoreContract.EMBEDDING_DIMENSION) throw new IllegalArgumentException("Child 向量维度非法");
        for (float value : child.embedding) if (!Float.isFinite(value)) throw new IllegalArgumentException("Child 向量数值非法");
    }
}
