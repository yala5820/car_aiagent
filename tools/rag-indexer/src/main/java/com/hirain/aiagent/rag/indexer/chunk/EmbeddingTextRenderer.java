package com.hirain.aiagent.rag.indexer.chunk;

import com.hirain.aiagent.rag.store.HeadingTitleResolver;

/** 二级标题与 Child 正文组成的固定 Embedding V2 输入模板。 */
public final class EmbeddingTextRenderer {
    public static final int TEMPLATE_VERSION = 2;

    public String render(String documentTitle, String headingPath, String chunkType, String content) {
        ChunkCanonicalizer canonicalizer = new ChunkCanonicalizer();
        String parentTitle = HeadingTitleResolver.parentTitle(headingPath, documentTitle);
        return "二级标题：" + canonicalizer.canonicalize(parentTitle)
                + "\n内容：" + canonicalizer.canonicalize(content);
    }

    public String render(ParentChunk parent, ChildChunk child) {
        return render(parent.title(), parent.headingPath(), child.evidenceType(), child.text());
    }
}
