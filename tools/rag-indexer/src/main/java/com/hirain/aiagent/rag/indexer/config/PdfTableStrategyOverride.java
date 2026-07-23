package com.hirain.aiagent.rag.indexer.config;

/** 经过配置校验的文档/物理页范围策略覆盖；页码必须采用与 SourceLocator 一致的 1-based 编号。 */
public record PdfTableStrategyOverride(String documentId, int pageStart, int pageEnd, String strategy) {
    public PdfTableStrategyOverride {
        if (documentId == null || documentId.isBlank() || pageStart < 1 || pageEnd < pageStart) {
            throw new IllegalArgumentException("PDF 表格策略覆盖范围无效");
        }
    }

    public boolean appliesTo(String candidateDocumentId, int page) {
        return documentId.equals(candidateDocumentId) && page >= pageStart && page <= pageEnd;
    }
}
