package com.hirain.aiagent.rag.model;

/** 由离线 Corpus 声明写入 Store 的文档事实；Android 只能读取，不能推断或覆盖。 */
public record DocumentMetadata(String documentId, String documentTitle, String documentType, String documentVersion,
                               String language, String vehicleModel, String modelYear, String region, String softwareVersion,
                               String configurationCode, SourceFormat sourceFormat, String sourceFileName, String sourceSha256,
                               String sourceCharset, int pageCount) {
    public DocumentMetadata {
        if (documentId == null || documentId.isBlank() || documentTitle == null || documentTitle.isBlank() || sourceFormat == null) throw new IllegalArgumentException("DocumentMetadata 必填字段不能为空");
        if (pageCount < 0 || (sourceFormat == SourceFormat.PDF && pageCount < 1) || (sourceFormat != SourceFormat.PDF && pageCount != 0)) throw new IllegalArgumentException("DocumentMetadata 页数与格式不一致");
    }
}
