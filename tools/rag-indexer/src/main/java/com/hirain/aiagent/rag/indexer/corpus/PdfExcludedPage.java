package com.hirain.aiagent.rag.indexer.corpus;
/** 受人工审核的 PDF 排除页；未批准页面不能在后续 Parser 阶段静默跳过。 */
public record PdfExcludedPage(int physicalPage, String reason, String reviewStatus, String reviewedBy, String reviewedAt) { }
