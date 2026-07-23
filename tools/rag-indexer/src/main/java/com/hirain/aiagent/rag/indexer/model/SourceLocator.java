package com.hirain.aiagent.rag.indexer.model;
/** 可追溯定位；数值 0 表示该格式不适用，映射到用户可见引用前必须恢复为空。 */
public record SourceLocator(SourceFormat sourceFormat,int pdfPageStart,int pdfPageEnd,String htmlElementId,int sourceLineStart,int sourceLineEnd,String headingPath,int sectionOrdinal) { }
