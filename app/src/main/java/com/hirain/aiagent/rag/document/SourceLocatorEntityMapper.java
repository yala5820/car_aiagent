package com.hirain.aiagent.rag.document;

import com.hirain.aiagent.rag.model.SourceFormat;
import com.hirain.aiagent.rag.model.SourceLocator;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;

/** 将 ObjectBox 的 0/空字符串扁平存储语义还原成领域层的“不适用”。 */
public final class SourceLocatorEntityMapper {
    public SourceLocator fromChunk(KnowledgeChunkEntity chunk) {
        SourceFormat format = SourceFormat.valueOf(chunk.sourceFormat);
        return new SourceLocator(format, HeadingPathCodec.decode(chunk.headingPath), chunk.pdfPageStart, chunk.pdfPageEnd,
                chunk.printedPageStartLabel, chunk.printedPageEndLabel, chunk.htmlElementId,
                chunk.sourceLineStart, chunk.sourceLineEnd, chunk.sectionOrdinal);
    }
}
