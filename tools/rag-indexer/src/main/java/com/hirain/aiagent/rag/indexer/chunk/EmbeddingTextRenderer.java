package com.hirain.aiagent.rag.indexer.chunk;

/** 与 rag-schema/test-vectors/embedding-input-v1.json 一致的固定 V1 文档 Embedding 输入模板。 */
public final class EmbeddingTextRenderer {
    public static final int TEMPLATE_VERSION = 1;

    public String render(String documentTitle, String headingPath, String chunkType, String content) {
        ChunkCanonicalizer canonicalizer = new ChunkCanonicalizer();
        return "文档：" + canonicalizer.canonicalize(documentTitle)
                + "\n位置：" + canonicalizer.canonicalize(headingPath)
                + "\n类型：" + canonicalizer.canonicalize(chunkType)
                + "\n内容：" + canonicalizer.canonicalize(content);
    }

    public String render(ParentChunk parent, ChildChunk child) {
        return render(parent.title(), parent.headingPath(), child.evidenceType(), child.text());
    }
}
