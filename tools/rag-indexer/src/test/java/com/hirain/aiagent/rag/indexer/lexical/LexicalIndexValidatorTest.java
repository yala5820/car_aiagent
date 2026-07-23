package com.hirain.aiagent.rag.indexer.lexical;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

final class LexicalIndexValidatorTest {
    @Test
    void shouldRejectPostingThatDoesNotReferenceChild() {
        LexicalIndex invalid = new LexicalIndex("v1", Map.of("child-a", 1),
                Map.of("acc", List.of(new LexicalPosting("missing-child", 1))), 1D);
        assertThrows(IllegalArgumentException.class, () -> new LexicalIndexValidator().validate(invalid));
    }
}
