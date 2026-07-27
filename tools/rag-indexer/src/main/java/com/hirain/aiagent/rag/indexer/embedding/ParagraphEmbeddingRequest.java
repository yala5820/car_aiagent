package com.hirain.aiagent.rag.indexer.embedding;

/** Parent 内短 Paragraph 的预处理向量请求；ID 只用于本次构建回填，不进入用户日志。 */
public record ParagraphEmbeddingRequest(String paragraphId, String sectionPath, String embeddingText) {
    public ParagraphEmbeddingRequest {
        if (paragraphId == null || paragraphId.isBlank() || embeddingText == null || embeddingText.isBlank()) {
            throw new IllegalArgumentException("PARAGRAPH_EMBEDDING_REQUEST_INVALID");
        }
        sectionPath = sectionPath == null ? "" : sectionPath;
    }
}
