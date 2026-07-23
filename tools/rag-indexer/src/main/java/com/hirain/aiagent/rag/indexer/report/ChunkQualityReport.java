package com.hirain.aiagent.rag.indexer.report;

import java.util.List;
import java.util.Map;

/** 可提交的 V2 Chunk 质量统计；只含计数、Hash 与原因码，不含正文。 */
public record ChunkQualityReport(long parentCount,long childCount,long parentUnderIdeal,long parentOverSoft,long parentOverHard,
                                 long childUnderIdeal,long childOverSoft,long childOverHard,long parentAsChildCount,
                                 long fallbackOverlapCount,long atomicOverHardCount,Map<String,Long> childrenPerParent,
                                 Map<String,Long> duplicateContentHashes,Map<String,Long> childOverHardBySplitReason,
                                 List<String> diagnostics,List<ChunkQualityFinding> findings) { }
