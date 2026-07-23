package com.hirain.aiagent.rag.indexer.embedding;

/** 所有 Child 必须恰好有一个 1024 维有限浮点向量，否则正式构建失败。 */
public final class EmbeddingVectorValidator {
    public static final int DIMENSION = 1024;

    public void validate(float[] vector) throws EmbeddingException {
        if (vector == null || vector.length != DIMENSION) {
            throw new EmbeddingException("EMBEDDING_DIMENSION_INVALID", false);
        }
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new EmbeddingException("EMBEDDING_VECTOR_NON_FINITE", false);
            }
        }
    }
}
