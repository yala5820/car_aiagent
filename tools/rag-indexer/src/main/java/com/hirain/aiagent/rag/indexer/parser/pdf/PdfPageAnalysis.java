package com.hirain.aiagent.rag.indexer.parser.pdf;
/** PDF 物理页与恢复后的文本；页码保持 1-based，与 SourceLocator 协议一致。 */
public record PdfPageAnalysis(int physicalPage, String text) { }
