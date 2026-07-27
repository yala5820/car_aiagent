package com.hirain.aiagent.rag.indexer.model;

/** Parser 输出的结构事实；Chunker 只能消费这些事实，不得反向猜测 DOM 或 PDF 布局。 */
public record BlockStructure(int headingLevel, String sectionPath, String sequenceGroupId, SequenceType sequenceType,
                             int listDepth, boolean atomicSemanticGroup, int documentOrdinal,
                             int columnIndex, boolean fullWidth) {
    public BlockStructure(int headingLevel, String sectionPath, String sequenceGroupId, SequenceType sequenceType,
                          int listDepth, boolean atomicSemanticGroup, int documentOrdinal) {
        this(headingLevel, sectionPath, sequenceGroupId, sequenceType, listDepth, atomicSemanticGroup,
                documentOrdinal, -1, false);
    }
    public BlockStructure { if(headingLevel<0||listDepth<0||documentOrdinal<0||sequenceType==null) throw new IllegalArgumentException("BLOCK_STRUCTURE_INVALID"); }
    public static BlockStructure legacy(SourceLocator locator){return new BlockStructure(0,locator==null?null:locator.headingPath(),null,SequenceType.NONE,0,false,locator==null?0:locator.sectionOrdinal());}
}
