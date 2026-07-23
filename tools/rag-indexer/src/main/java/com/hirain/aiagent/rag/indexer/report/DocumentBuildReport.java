package com.hirain.aiagent.rag.indexer.report;

import java.util.List;

/** 单文档审计摘要；sourcePath 仅允许相对语料根路径，正文绝不进入报告。 */
public record DocumentBuildReport(String documentId, String sourcePath, String sourceSha256, String charset,
                                  String sourceFormat, boolean succeeded, long blockCount, long tableCount,
                                  long warningCount, long locatorCount, List<String> reasonCodes) { }
