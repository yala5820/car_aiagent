package com.hirain.aiagent.rag.indexer.config;
import java.util.List;
public record MarkdownParserConfig(String syntax, List<String> extensions) { public MarkdownParserConfig { extensions=List.copyOf(extensions); } }
