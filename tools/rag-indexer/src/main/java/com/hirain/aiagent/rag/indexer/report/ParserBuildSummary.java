package com.hirain.aiagent.rag.indexer.report;

import java.util.Map;

/** 各输入格式的 Parser 版本、成功/失败和安全/结构诊断统计。 */
public record ParserBuildSummary(Map<String, Long> documentCounts, Map<String, Long> succeededCounts,
                                 Map<String, Long> failedCounts, Map<String, String> parserVersions) { }
