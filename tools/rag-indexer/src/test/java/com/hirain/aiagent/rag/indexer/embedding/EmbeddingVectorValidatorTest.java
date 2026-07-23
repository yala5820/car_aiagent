package com.hirain.aiagent.rag.indexer.embedding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class EmbeddingVectorValidatorTest {
    @Test
    void shouldRejectWrongDimensionAndNonFiniteValues() {
        EmbeddingVectorValidator validator = new EmbeddingVectorValidator();
        assertDoesNotThrow(() -> validator.validate(new float[1024]));
        assertThrows(EmbeddingException.class, () -> validator.validate(new float[3]));
        float[] invalid = new float[1024]; invalid[4] = Float.NaN;
        assertThrows(EmbeddingException.class, () -> validator.validate(invalid));
    }
}
