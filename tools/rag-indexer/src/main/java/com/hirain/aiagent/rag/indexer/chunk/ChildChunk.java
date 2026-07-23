package com.hirain.aiagent.rag.indexer.chunk;

import com.hirain.aiagent.rag.indexer.model.SourceLocator;

/** Child 是可检索的自包含证据，保留 Parent 顺序与源定位，稳定 ID 在 G302 补入。 */
public record ChildChunk(int parentOrdinal, int ordinal, String text, SourceLocator locator, String evidenceType,
                         int tokenEstimate, String splitReason, int overlapTokenCount) {
    /** V1 兼容构造；V2 新路径必须提供可审计分块元数据。 */
    public ChildChunk(int parentOrdinal,int ordinal,String text,SourceLocator locator,String evidenceType){this(parentOrdinal,ordinal,text,locator,evidenceType,-1,"V1_LEGACY",0);}
}
