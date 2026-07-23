package com.hirain.aiagent.rag.indexer.store;

import com.hirain.aiagent.rag.store.KnowledgeStoreContract;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity_;
import com.hirain.aiagent.rag.store.entity.KnowledgeDocumentEntity;
import com.hirain.aiagent.rag.store.entity.KnowledgeStoreMetadataEntity;
import com.hirain.aiagent.rag.store.entity.KnowledgeStoreMetadataEntity_;
import com.hirain.aiagent.rag.store.entity.LexicalTermEntity;
import com.hirain.aiagent.rag.store.entity.LexicalTermEntity_;

import io.objectbox.BoxStore;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** 重新打开已关闭 Store 完成物理产物自检；失败时调用方不得进入 Hash 或发布步骤。 */
public final class ObjectBoxStoreVerifier {
    private final ObjectBoxStoreFactory factory = new ObjectBoxStoreFactory();

    public StoreStatistics verify(Path directory) {
        try (BoxStore store = factory.openExisting(directory)) {
            var documents = store.boxFor(KnowledgeDocumentEntity.class);
            var chunks = store.boxFor(KnowledgeChunkEntity.class);
            var terms = store.boxFor(LexicalTermEntity.class);
            var metadata = store.boxFor(KnowledgeStoreMetadataEntity.class);
            if (metadata.count() != 1) throw new IllegalStateException("Store 必须恰有一条 Metadata");
            KnowledgeStoreMetadataEntity current = metadata.getAll().get(0);
            if (documents.count() != current.documentCount || terms.count() != current.lexicalTermCount) throw new IllegalStateException("Store 计数与 Metadata 不一致");
            Map<String, KnowledgeChunkEntity> byStableId = new HashMap<>();
            Map<Long, KnowledgeChunkEntity> byEntityId = new HashMap<>();
            long parents = 0, children = 0;
            for (KnowledgeChunkEntity chunk : chunks.getAll()) {
                byStableId.put(chunk.chunkId, chunk); byEntityId.put(chunk.id, chunk);
                if ("PARENT".equals(chunk.chunkLevel)) { parents++; if (chunk.embedding != null) throw new IllegalStateException("Parent 包含向量"); }
                else if ("CHILD".equals(chunk.chunkLevel)) { children++; }
                else throw new IllegalStateException("未知 Chunk Level");
            }
            if (parents != current.parentChunkCount || children != current.childChunkCount) throw new IllegalStateException("Chunk 计数与 Metadata 不一致");
            byStableId.values().stream().filter(chunk -> "CHILD".equals(chunk.chunkLevel)).forEach(child -> verifyChild(child, byStableId));
            for (LexicalTermEntity term : terms.getAll()) verifyTerm(term, byEntityId);
            verifyMinimumQueries(chunks, terms, metadata, current, byStableId);
            return new StoreStatistics(documents.count(), parents, children, terms.count());
        }
    }

    private static void verifyChild(KnowledgeChunkEntity child, Map<String, KnowledgeChunkEntity> byStableId) {
        KnowledgeChunkEntity parent = byStableId.get(child.parentChunkId);
        if (parent == null || !"PARENT".equals(parent.chunkLevel) || !parent.documentId.equals(child.documentId)) throw new IllegalStateException("Child Parent 引用失效");
        if (child.embedding == null || child.embedding.length != KnowledgeStoreContract.EMBEDDING_DIMENSION) throw new IllegalStateException("Child 向量维度失效");
        for (float value : child.embedding) if (!Float.isFinite(value)) throw new IllegalStateException("Child 向量数值失效");
    }

    private static void verifyTerm(LexicalTermEntity term, Map<Long, KnowledgeChunkEntity> byEntityId) {
        if (term.documentFrequency <= 0 || term.chunkEntityIds == null || term.termFrequencies == null
                || term.documentFrequency != term.chunkEntityIds.length || term.documentFrequency != term.termFrequencies.length) throw new IllegalStateException("Term posting 长度非法");
        for (int index = 0; index < term.documentFrequency; index++) {
            KnowledgeChunkEntity child = byEntityId.get(term.chunkEntityIds[index]);
            if (child == null || !"CHILD".equals(child.chunkLevel) || term.termFrequencies[index] <= 0) throw new IllegalStateException("Term posting 引用非法");
        }
    }

    /** 至少执行一次向量、精确 term 和精确 Metadata 查询，避免只验证文件能被打开。 */
    private static void verifyMinimumQueries(io.objectbox.Box<KnowledgeChunkEntity> chunks, io.objectbox.Box<LexicalTermEntity> terms,
                                             io.objectbox.Box<KnowledgeStoreMetadataEntity> metadata, KnowledgeStoreMetadataEntity current,
                                             Map<String, KnowledgeChunkEntity> byStableId) {
        KnowledgeChunkEntity sampleChild = byStableId.values().stream().filter(chunk -> "CHILD".equals(chunk.chunkLevel)).findFirst().orElse(null);
        if (sampleChild != null && chunks.query(KnowledgeChunkEntity_.embedding.nearestNeighbors(sampleChild.embedding, 1)).build().findFirst() == null) {
            throw new IllegalStateException("Dense Query 自检失败");
        }
        LexicalTermEntity sampleTerm = terms.getAll().stream().findFirst().orElse(null);
        if (sampleTerm != null && terms.query(LexicalTermEntity_.term.equal(sampleTerm.term)).build().findFirst() == null) {
            throw new IllegalStateException("Term Query 自检失败");
        }
        if (metadata.query(KnowledgeStoreMetadataEntity_.bundleId.equal(current.bundleId)).build().findFirst() == null) {
            throw new IllegalStateException("Metadata Query 自检失败");
        }
    }
}
