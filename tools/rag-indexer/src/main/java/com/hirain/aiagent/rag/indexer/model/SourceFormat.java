package com.hirain.aiagent.rag.indexer.model;
/** 离线端唯一允许的输入格式；动态网页、OCR 和未声明格式不得进入 Registry。 */
public enum SourceFormat { PDF, STATIC_HTML, MARKDOWN }
