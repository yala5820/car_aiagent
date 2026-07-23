package com.hirain.aiagent.rag.indexer.config;
/** Parser 资源上限的 V1 协议值；实际文件预算由 G103 强制执行。 */
public record ParserLimitsConfig(long maxFileBytes, int maxDocuments) { }
