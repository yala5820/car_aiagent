package com.hirain.aiagent.rag.indexer.store;

import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;
import com.hirain.aiagent.rag.store.entity.KnowledgeDocumentEntity;
import com.hirain.aiagent.rag.store.entity.KnowledgeStoreMetadataEntity;
import com.hirain.aiagent.rag.store.entity.LexicalTermEntity;

import io.objectbox.BoxStore;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/** 固化 Document → Parent → Child → Term → Metadata 的写入顺序，并只在当前 Store 内生成 posting long ID。 */
public final class DeterministicEntityWriter {
    private final LexicalTermEntityMapper lexicalMapper = new LexicalTermEntityMapper();

    public void write(BoxStore store, StoreWriteModel model) {
        var documents = store.boxFor(KnowledgeDocumentEntity.class);
        var chunks = store.boxFor(KnowledgeChunkEntity.class);
        var terms = store.boxFor(LexicalTermEntity.class);
        var metadata = store.boxFor(KnowledgeStoreMetadataEntity.class);
        model.documents().stream().sorted(Comparator.comparing(document -> document.documentId)).forEach(document -> {
            requireNew(document.id, "Document"); documents.put(document);
        });
        model.chunks().stream().filter(chunk -> "PARENT".equals(chunk.chunkLevel)).sorted(Comparator.comparing(chunk -> chunk.chunkId)).forEach(parent -> {
            requireNew(parent.id, "Parent"); chunks.put(parent);
        });
        Map<String, Long> childEntityIds = new HashMap<>();
        model.chunks().stream().filter(chunk -> "CHILD".equals(chunk.chunkLevel)).sorted(Comparator.comparing(chunk -> chunk.chunkId)).forEach(child -> {
            requireNew(child.id, "Child"); childEntityIds.put(child.chunkId, chunks.put(child));
        });
        model.lexicalIndex().titlePostingsByTerm().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                terms.put(lexicalMapper.map("TITLE", entry.getKey(), entry.getValue(), childEntityIds)));
        model.lexicalIndex().bodyPostingsByTerm().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                terms.put(lexicalMapper.map("BODY", entry.getKey(), entry.getValue(), childEntityIds)));
        requireNew(model.metadata().id, "Metadata"); metadata.put(model.metadata());
    }

    private static void requireNew(long id, String type) {
        if (id != 0) throw new IllegalArgumentException(type + " 不能携带旧 Store ObjectBox ID");
    }
}
