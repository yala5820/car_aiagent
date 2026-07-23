package com.hirain.aiagent.rag.indexer.report;

import com.hirain.aiagent.rag.indexer.model.SourceLocator;

/** 不含正文的可定位质量异常；用于让人工审核从统计数字回到具体来源。 */
public record ChunkQualityFinding(String reasonCode, String documentId, int parentOrdinal, int childOrdinal,
                                  int tokenEstimate, SourceLocator locator) { }
