package com.hirain.aiagent.rag.indexer.chunk;

import com.hirain.aiagent.rag.indexer.model.StructuredBlock;
import java.util.ArrayList;
import java.util.List;

/** 文档结构树节点；大章节可只有子节点而无直接正文，因此不必产生 Parent。 */
final class DocumentSection {
    final String path; final int depth; final List<StructuredBlock> directBlocks=new ArrayList<>(); final List<DocumentSection> children=new ArrayList<>();
    DocumentSection(String path,int depth){this.path=path;this.depth=depth;}
}
