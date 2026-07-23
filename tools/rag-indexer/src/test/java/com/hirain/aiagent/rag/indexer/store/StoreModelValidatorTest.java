package com.hirain.aiagent.rag.indexer.store;

import com.hirain.aiagent.rag.indexer.chunk.ChildChunk;
import com.hirain.aiagent.rag.indexer.lexical.LexicalIndex;
import com.hirain.aiagent.rag.indexer.lexical.LexicalPosting;
import com.hirain.aiagent.rag.indexer.model.SourceFormat;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class StoreModelValidatorTest {
    @Test
    void shouldValidateFullCrossReferencesEvenWhenChildPrecedesParent() {
        StoreWriteModel model = model();
        assertDoesNotThrow(() -> new StoreModelValidator().validate(model));
    }

    @Test
    void shouldRejectLexicalPostingToUnknownChild() {
        StoreWriteModel valid = model();
        LexicalIndex index = new LexicalIndex("v1", Map.of("child", 1),
                Map.of("acc", List.of(new LexicalPosting("missing", 1))), 1D);
        StoreWriteModel invalid = new StoreWriteModel(valid.metadata(), valid.documents(), valid.chunks(), index);
        assertThrows(IllegalArgumentException.class, () -> new StoreModelValidator().validate(invalid));
    }

    static StoreWriteModel model() {
        var locator = StoreTestFixtures.locator(SourceFormat.PDF); var parent = StoreTestFixtures.parent(locator);
        KnowledgeChunkEntityMapper mapper = new KnowledgeChunkEntityMapper();
        KnowledgeChunkEntity parentEntity = mapper.mapParent("parent", parent, StoreTestFixtures.document("PDF"));
        KnowledgeChunkEntity childEntity = mapper.mapChild("child", "parent", parent,
                new ChildChunk(0, 0, "ACC", locator, "PROCEDURE"), StoreTestFixtures.document("PDF"), 1, new float[1024]);
        var metadata = new KnowledgeStoreMetadataMapper().map(KnowledgeStoreMetadataMapperTest.input());
        LexicalIndex index = new LexicalIndex("v1", Map.of("child", 1), Map.of("acc", List.of(new LexicalPosting("child", 1))), 1D);
        return new StoreWriteModel(metadata, List.of(new KnowledgeDocumentEntityMapper().map(StoreTestFixtures.document("PDF"), "UTF-8", 1)),
                List.of(childEntity, parentEntity), index);
    }
}
