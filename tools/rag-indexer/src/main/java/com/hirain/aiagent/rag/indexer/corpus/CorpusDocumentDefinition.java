package com.hirain.aiagent.rag.indexer.corpus;

/** 人工声明的单份语料元数据；文件真实性、Hash 和格式探测由 G103 负责。 */
public record CorpusDocumentDefinition(
        String documentId, String title, String documentType, String documentVersion, String language,
        String sourceFormat, String relativePath, String expectedSha256, String sourceLayout,
        KnowledgeScopeDefinition applicability
) {
    /**
     * 保持已有单文件 Corpus 的构造调用兼容；目录输入必须在 JSON 中显式声明，不能被该兼容构造器意外启用。
     */
    public CorpusDocumentDefinition(String documentId, String title, String documentType, String documentVersion,
                                    String language, String sourceFormat, String relativePath,
                                    String expectedSha256, KnowledgeScopeDefinition applicability) {
        this(documentId, title, documentType, documentVersion, language, sourceFormat, relativePath,
                expectedSha256, "SINGLE_FILE", applicability);
    }
}
