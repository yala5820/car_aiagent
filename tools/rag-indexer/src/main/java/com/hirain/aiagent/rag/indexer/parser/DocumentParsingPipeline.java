package com.hirain.aiagent.rag.indexer.parser;

import com.hirain.aiagent.rag.indexer.cli.CliCommandException;
import com.hirain.aiagent.rag.indexer.cli.CliExitCode;
import com.hirain.aiagent.rag.indexer.model.ParseResult;
import com.hirain.aiagent.rag.indexer.model.SourceDocument;
import com.hirain.aiagent.rag.indexer.model.SourceFormat;

/** Parser Registry 的唯一解析出口；质量 Gate 不通过时禁止向 Chunk、Embedding 或 Store 继续传递。 */
public final class DocumentParsingPipeline {
    private final DocumentParserRegistry registry;
    private final ParseQualityValidator qualityValidator;

    public DocumentParsingPipeline(DocumentParserRegistry registry, ParseQualityValidator qualityValidator) {
        this.registry = registry;
        this.qualityValidator = qualityValidator;
    }

    public ParseResult parse(SourceDocument document) {
        ParseResult parsed = registry.require(document.sourceFormat()).parse(document);
        // PDF 已经由坐标阅读器输出视觉行；句子级 Normalizer 会破坏列和行的可追溯性，因此只对 HTML/Markdown 使用。
        ParseResult result = document.sourceFormat() == SourceFormat.PDF ? parsed : new SemanticBlockNormalizer().normalize(parsed);
        ParseQualityReport report = qualityValidator.validate(document.sourceFormat(), result);
        if (!report.isAccepted()) {
            // 文档 ID 与 failures 都来自受控元数据/枚举原因码，可用于定位资料兼容性，绝不输出底层消息或正文。
            throw new CliCommandException(CliExitCode.INPUT_OR_PARSE_ERROR,
                    "PARSE_QUALITY_GATE_FAILED_" + document.metadata().documentId() + "_" + String.join("_", report.failures()));
        }
        return result;
    }
}
