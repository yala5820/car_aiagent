package com.hirain.aiagent.rag.indexer.store;

import com.hirain.aiagent.rag.indexer.lexical.LexicalPosting;
import com.hirain.aiagent.rag.store.entity.LexicalTermEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class LexicalTermEntityMapperTest {
    @Test
    void shouldMapOnlyCurrentStoreChildEntityIds() {
        LexicalTermEntity entity = new LexicalTermEntityMapper().map("acc", List.of(new LexicalPosting("child", 2)), Map.of("child", 7L));
        assertEquals(1, entity.documentFrequency); assertArrayEquals(new long[]{7L}, entity.chunkEntityIds); assertArrayEquals(new int[]{2}, entity.termFrequencies);
    }
}
