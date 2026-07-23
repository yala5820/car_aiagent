package com.hirain.aiagent.rag.indexer.store;

/** 关闭 Store 后重新读取得到的统计，不以写入阶段的内存计数充当验证证据。 */
public record StoreStatistics(long documentCount, long parentChunkCount, long childChunkCount, long lexicalTermCount) { }
