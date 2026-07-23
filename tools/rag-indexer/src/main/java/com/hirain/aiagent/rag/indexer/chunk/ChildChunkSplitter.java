package com.hirain.aiagent.rag.indexer.chunk;

import com.hirain.aiagent.rag.indexer.model.BlockType;
import com.hirain.aiagent.rag.indexer.model.StructuredBlock;
import com.hirain.aiagent.rag.indexer.model.TableBlock;

import java.util.ArrayList;
import java.util.List;

/** 普通文本按 Block 聚合到预算；Warning、标题和表格行保持自包含原子边界。 */
final class ChildChunkSplitter {
    private final TokenEstimator estimator = new TokenEstimator();

    List<ChildChunk> split(ParentChunk parent, ChunkBoundaryPolicy policy) {
        List<ChildChunk> output = new ArrayList<>();
        List<StructuredBlock> pending = new ArrayList<>();
        int tokens = 0;
        int ordinal = 0;
        for (StructuredBlock block : parent.blocks()) {
            if (!pending.isEmpty() && !isContinuous(pending.get(pending.size() - 1), block)) {
                // 引用位置不连续时必须先截断，不能让不可追溯的文本混入同一 Child，更不能令整份资料构建失败。
                ordinal = flush(parent, pending, output, ordinal);
                pending.clear();
                tokens = 0;
            }
            int blockTokens = estimator.estimate(block.text());
            boolean atomic = new WarningCohesionPolicy().isAtomic(block) || block.type() == BlockType.HEADING || blockTokens > policy.maxChildTokens();
            if (atomic) {
                ordinal = flush(parent, pending, output, ordinal);
                output.add(new ChildChunk(parent.ordinal(), ++ordinal, block.text(), block.locator(),
                        block.type() == BlockType.WARNING ? "WARNING" : "TEXT"));
                tokens = 0;
            } else if (tokens + blockTokens > policy.maxChildTokens() && !pending.isEmpty()) {
                ordinal = flush(parent, pending, output, ordinal);
                pending.clear();
                tokens = 0;
                pending.add(block);
                tokens = blockTokens;
            } else {
                pending.add(block);
                tokens += blockTokens;
            }
        }
        ordinal = flush(parent, pending, output, ordinal);
        for (TableBlock table : parent.tables()) {
            for (String text : new TableChunkSplitter().split(table, policy.tableRowsPerChild())) {
                output.add(new ChildChunk(parent.ordinal(), ++ordinal, text, table.locator(), "TABLE"));
            }
        }
        return List.copyOf(output);
    }

    private boolean isContinuous(StructuredBlock first, StructuredBlock second) {
        return new SourceLocatorMerger().merge(first.locator(), second.locator()) != null;
    }

    private int flush(ParentChunk parent, List<StructuredBlock> blocks, List<ChildChunk> output, int ordinal) {
        if (blocks.isEmpty()) {
            return ordinal;
        }
        com.hirain.aiagent.rag.indexer.model.SourceLocator locator = blocks.get(0).locator();
        output.add(new ChildChunk(parent.ordinal(), ++ordinal, String.join("\n", blocks.stream().map(StructuredBlock::text).toList()), locator, "TEXT"));
        return ordinal;
    }
}
