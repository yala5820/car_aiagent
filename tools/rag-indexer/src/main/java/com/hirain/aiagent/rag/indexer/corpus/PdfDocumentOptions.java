package com.hirain.aiagent.rag.indexer.corpus;
import java.util.List;
public record PdfDocumentOptions(String tableStrategy, List<PdfExcludedPage> excludedPages) { public PdfDocumentOptions { excludedPages=List.copyOf(excludedPages); } }
