package com.hirain.aiagent.rag.indexer.chunk;

import com.hirain.aiagent.rag.indexer.model.BoundingBox;
import com.hirain.aiagent.rag.indexer.model.SourceLocator;
import com.hirain.aiagent.rag.indexer.model.StructuredBlock;
import java.util.List;

/** Parent 内部的段落事实；Child 只消费该中间模型，不再直接把视觉行当作语义单元。 */
public record ReconstructedParagraph(String text, List<StructuredBlock> blocks, int columnIndex,
                                     int pageStart, int pageEnd, int tokenCount, BoundingBox boundingBox,
                                     boolean atomic, String structureKind, SourceLocator locator) {
    public ReconstructedParagraph {
        if (text == null || text.isBlank() || blocks == null || blocks.isEmpty() || columnIndex < -1
                || pageStart < 0 || pageEnd < pageStart || tokenCount < 0 || structureKind == null || locator == null) {
            throw new IllegalArgumentException("RECONSTRUCTED_PARAGRAPH_INVALID");
        }
        blocks = List.copyOf(blocks);
    }
}
