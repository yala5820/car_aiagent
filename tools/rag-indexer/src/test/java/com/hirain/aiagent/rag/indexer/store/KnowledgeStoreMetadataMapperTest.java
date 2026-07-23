package com.hirain.aiagent.rag.indexer.store;

import com.hirain.aiagent.rag.store.entity.KnowledgeStoreMetadataEntity;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class KnowledgeStoreMetadataMapperTest {
    @Test
    void shouldWriteSortedCollectionJson() {
        KnowledgeStoreMetadataEntity entity = new KnowledgeStoreMetadataMapper().map(input());
        assertEquals("[\"MARKDOWN\",\"PDF\"]", entity.supportedSourceFormats);
        assertEquals("{\"MARKDOWN\":1,\"PDF\":2}", entity.sourceFormatCounts);
        assertEquals(1024, entity.embeddingDimension);
    }

    static StoreMetadataInput input() {
        return new StoreMetadataInput("bundle", "1", "scope", "schema", "builder", "objectbox", "hnsw", "parser", "chunk", "corpus",
                Set.of("PDF", "MARKDOWN"), Map.of("PDF", 2L, "MARKDOWN", 1L), 1, 1, 1, 1, 2D, 1L);
    }
}
