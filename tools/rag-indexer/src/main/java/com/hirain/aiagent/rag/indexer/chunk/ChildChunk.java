package com.hirain.aiagent.rag.indexer.chunk;

import com.hirain.aiagent.rag.indexer.model.SourceLocator;

/** Child 是可检索的自包含证据，保留 Parent 顺序与源定位，稳定 ID 在 G302 补入。 */
public record ChildChunk(int parentOrdinal, int ordinal, String text, SourceLocator locator, String evidenceType) {
}
