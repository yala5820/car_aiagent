package com.hirain.aiagent.rag.indexer.pipeline;

import com.hirain.aiagent.rag.indexer.chunk.ChunkBoundaryPolicy;
import com.hirain.aiagent.rag.indexer.chunk.DocumentChunker;
import com.hirain.aiagent.rag.indexer.config.RagBuildConfig;
import com.hirain.aiagent.rag.indexer.parser.DocumentParserRegistry;
import com.hirain.aiagent.rag.indexer.parser.DocumentParsingPipeline;
import com.hirain.aiagent.rag.indexer.parser.ParseQualityValidator;
import com.hirain.aiagent.rag.indexer.parser.html.StaticHtmlDocumentParser;
import com.hirain.aiagent.rag.indexer.parser.markdown.MarkdownDocumentParser;
import com.hirain.aiagent.rag.indexer.parser.pdf.PdfDocumentParser;

import java.util.List;

/**
 * 集中冻结 Build 使用的 Parser 与 Chunk 组件组合。
 *
 * <p>配置文件只决定已定义的协议字段；V1 Chunk 边界是代码与 Golden 共同冻结的安全基线，
 * 不允许由 CLI Flag 临时放大，避免同一 Corpus 产生不可审计的不同 Chunk 身份。</p>
 */
public final class BuildComponentFactory {
    public DocumentParsingPipeline createParsingPipeline(RagBuildConfig config) {
        return new DocumentParsingPipeline(new DocumentParserRegistry(List.of(
                new PdfDocumentParser(config.pdfParserConfig()),
                new StaticHtmlDocumentParser(config.htmlParserConfig()),
                new MarkdownDocumentParser(config.markdownParserConfig()))), new ParseQualityValidator());
    }

    public DocumentChunker createChunker() {
        var policy = com.hirain.aiagent.rag.indexer.config.ChunkingConfig.v1Default();
        return new DocumentChunker(new ChunkBoundaryPolicy(policy.maxChildTokens(), policy.overlapTokens(), policy.tableRowsPerChild()));
    }

    public DocumentChunker createChunker(RagBuildConfig config) {
        var policy = config.chunkingConfig();
        return new DocumentChunker(new ChunkBoundaryPolicy(policy.childTargetTokens(), policy.overlapTokens(),
                policy.tableRowsPerChild(), policy.childSoftMaxTokens(), policy.childHardMaxTokens(), policy.childIdealMinTokens()));
    }
}
