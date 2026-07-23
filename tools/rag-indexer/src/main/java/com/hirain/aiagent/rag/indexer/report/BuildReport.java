package com.hirain.aiagent.rag.indexer.report;

import java.util.List;
import java.util.Map;

/** 审计报告只保存统计、原因码和受控相对路径；禁止包含完整知识正文、绝对路径或凭证。 */
public record BuildReport(String runId, boolean publishable, List<String> errorCodes, List<String> warningCodes,
                          Map<String, Long> sourceFormatCounts, Map<String, Long> storeCounts,
                          ParserBuildSummary parserSummary, ChunkBuildSummary chunkSummary,
                          EmbeddingBuildSummary embeddingSummary, StoreBuildSummary storeSummary,
                          List<DocumentBuildReport> documents) { }
