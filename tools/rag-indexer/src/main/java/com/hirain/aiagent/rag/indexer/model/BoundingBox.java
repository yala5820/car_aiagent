package com.hirain.aiagent.rag.indexer.model;

/** PDF 坐标范围；未知坐标使用 null，禁止伪造几何信息。 */
public record BoundingBox(float left, float top, float right, float bottom) {
    public BoundingBox {
        if (right < left || bottom < top) {
            throw new IllegalArgumentException("BoundingBox 范围无效");
        }
    }
}
