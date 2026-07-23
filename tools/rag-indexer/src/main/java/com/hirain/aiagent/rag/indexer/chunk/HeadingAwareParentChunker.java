package com.hirain.aiagent.rag.indexer.chunk;

import com.hirain.aiagent.rag.indexer.model.BlockType;
import com.hirain.aiagent.rag.indexer.model.ParseResult;
import com.hirain.aiagent.rag.indexer.model.SourceDocument;
import com.hirain.aiagent.rag.indexer.model.SourceLocator;
import com.hirain.aiagent.rag.indexer.model.StructuredBlock;
import com.hirain.aiagent.rag.indexer.model.TableBlock;

import java.util.ArrayList;
import java.util.List;

/** 按 Heading Path 划分完整主题 Parent；无标题正文归入文档标题主题。 */
final class HeadingAwareParentChunker {
    List<ParentChunk> chunk(SourceDocument source, ParseResult parsed) {
        List<ParentChunk> output = new ArrayList<>();
        List<StructuredBlock> current = new ArrayList<>();
        String currentPath = "";
        int ordinal = 0;
        for (StructuredBlock block : parsed.blocks()) {
            if (block.type() == BlockType.HEADING && !current.isEmpty()) {
                output.add(parent(source, ++ordinal, currentPath, current, parsed.tables()));
                current = new ArrayList<>();
            }
            currentPath = block.locator().headingPath() == null ? currentPath : block.locator().headingPath();
            current.add(block);
        }
        if (!current.isEmpty()) {
            output.add(parent(source, ++ordinal, currentPath, current, parsed.tables()));
        }
        return List.copyOf(output);
    }

    private ParentChunk parent(SourceDocument source, int ordinal, String path, List<StructuredBlock> blocks, List<TableBlock> allTables) {
        List<TableBlock> tables = allTables.stream().filter(table -> java.util.Objects.equals(table.locator().headingPath(), path)).toList();
        String text = String.join("\n", blocks.stream().map(StructuredBlock::text).toList());
        SourceLocator locator = blocks.get(0).locator();
        return new ParentChunk(ordinal, source.metadata().documentId(), source.metadata().title(), path, text, locator, blocks, tables);
    }
}
