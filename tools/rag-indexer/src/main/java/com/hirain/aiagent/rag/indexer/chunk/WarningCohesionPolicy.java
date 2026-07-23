package com.hirain.aiagent.rag.indexer.chunk;

import com.hirain.aiagent.rag.indexer.model.BlockType;
import com.hirain.aiagent.rag.indexer.model.StructuredBlock;

/** Warning 已由 Parser 聚合条件/禁止/后果时必须作为一个原子 Child，禁止被 overlap 或句子切分拆散。 */
final class WarningCohesionPolicy {
    boolean isAtomic(StructuredBlock block) {
        return block.type() == BlockType.WARNING;
    }
}
