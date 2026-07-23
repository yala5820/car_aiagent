package com.hirain.aiagent.rag.indexer.config;

import com.fasterxml.jackson.databind.JsonNode;

/** 经严格校验的构建配置快照；后续各 Pipeline 只读取此对象，CLI 不提供协议覆盖入口。 */
public record RagBuildConfig(JsonNode canonicalJson, String fingerprint, PdfParserConfig pdfParserConfig,
                             HtmlParserConfig htmlParserConfig, MarkdownParserConfig markdownParserConfig,
                             ChunkingConfig chunkingConfig) { }
