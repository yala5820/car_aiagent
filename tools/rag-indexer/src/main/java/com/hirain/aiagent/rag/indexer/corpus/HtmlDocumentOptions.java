package com.hirain.aiagent.rag.indexer.corpus;
import java.util.List;
public record HtmlDocumentOptions(String charset, String contentRootSelector, List<String> excludeSelectors) { public HtmlDocumentOptions { excludeSelectors=List.copyOf(excludeSelectors); } }
