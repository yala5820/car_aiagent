package com.hirain.aiagent.rag.indexer.parser.pdf;
/** V1 PDF 分类结果；扫描和加密均为硬失败，不触发 OCR 或密码尝试。 */
public enum PdfClassification { TEXT, MIXED, SCANNED_OR_EMPTY, ENCRYPTED, INVALID }
