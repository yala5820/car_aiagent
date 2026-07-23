package com.hirain.aiagent.rag.indexer.corpus;
/** 输入打开前执行的文件数量与单文件大小上限。 */
public record CorpusResourceBudget(int maxDocuments, long maxSingleFileBytes) { public CorpusResourceBudget { if(maxDocuments<1||maxSingleFileBytes<1)throw new IllegalArgumentException("资源预算必须为正数"); } }
