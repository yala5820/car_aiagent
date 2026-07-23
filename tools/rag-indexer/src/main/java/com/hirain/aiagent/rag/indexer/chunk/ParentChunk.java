package com.hirain.aiagent.rag.indexer.chunk;

import com.hirain.aiagent.rag.indexer.model.SourceLocator;
import com.hirain.aiagent.rag.indexer.model.StructuredBlock;
import com.hirain.aiagent.rag.indexer.model.TableBlock;

import java.util.List;

/** Parent 表示完整主题；稳定 ID 在 G302 生成，G301 仅保留确定顺序与可追溯范围。 */
public record ParentChunk(int ordinal, String documentId, String title, String headingPath, String text, SourceLocator locator,
                          List<StructuredBlock> blocks, List<TableBlock> tables) {
    public ParentChunk {
        blocks = List.copyOf(blocks);
        tables = List.copyOf(tables);
    }
}
