package com.hirain.aiagent.rag.indexer.pipeline;

/** 只记录可恢复边界；发布完成前任何阶段失败都不得创建正式输出。 */
public enum BuildPhase { VALIDATED, PARSED, CHUNKED, EMBEDDED, INDEXED, STORE_WRITTEN, VERIFIED }
