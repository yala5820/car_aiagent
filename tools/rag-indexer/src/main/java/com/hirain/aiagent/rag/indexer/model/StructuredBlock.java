package com.hirain.aiagent.rag.indexer.model;

/** 供三种 Parser 共同输出的正文块；不得在此对象中保存 Chunk 或 Embedding 状态。 */
public record StructuredBlock(
        BlockType type,
        String text,
        SourceLocator locator,
        BoundingBox boundingBox,
        ExtractionConfidence confidence,
        BlockStructure structure
) {
    public StructuredBlock {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("StructuredBlock 文本不能为空");
        }
    }
    /** 为尚未升级的 Parser/Fixture 保留构造兼容，结构默认来自既有 Locator。 */
    public StructuredBlock(BlockType type,String text,SourceLocator locator,BoundingBox boundingBox,ExtractionConfidence confidence){this(type,text,locator,boundingBox,confidence,BlockStructure.legacy(locator));}
}
