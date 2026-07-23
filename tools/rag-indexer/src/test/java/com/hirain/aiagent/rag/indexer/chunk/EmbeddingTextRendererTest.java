package com.hirain.aiagent.rag.indexer.chunk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class EmbeddingTextRendererTest {
    @Test
    void shouldRenderFrozenV1Template() {
        assertEquals("文档：车辆保养手册\n位置：保养 > 制动系统\n类型：PROCEDURE\n内容：每 24 个月检查制动液。",
                new EmbeddingTextRenderer().render("车辆保养手册", "保养 > 制动系统", "PROCEDURE", "每 24 个月检查制动液。"));
    }
}
