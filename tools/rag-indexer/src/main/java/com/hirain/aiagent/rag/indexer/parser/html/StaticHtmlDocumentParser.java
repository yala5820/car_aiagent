package com.hirain.aiagent.rag.indexer.parser.html;

import com.hirain.aiagent.rag.indexer.model.DiagnosticSeverity;
import com.hirain.aiagent.rag.indexer.config.HtmlParserConfig;
import com.hirain.aiagent.rag.indexer.model.ParseDiagnostic;
import com.hirain.aiagent.rag.indexer.model.ParseResult;
import com.hirain.aiagent.rag.indexer.model.SourceDocument;
import com.hirain.aiagent.rag.indexer.model.SourceFormat;
import com.hirain.aiagent.rag.indexer.model.SourceLocator;
import com.hirain.aiagent.rag.indexer.parser.DocumentParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** 严格静态 HTML Parser：本地文本到 DOM，禁止 URL 加载、脚本执行和动态渲染回退。 */
public final class StaticHtmlDocumentParser implements DocumentParser {
    private final String contentRootSelector;
    private final java.util.List<String> excludeSelectors;

    public StaticHtmlDocumentParser() {
        this((String) null);
    }

    public StaticHtmlDocumentParser(String contentRootSelector) {
        this.contentRootSelector = contentRootSelector;
        this.excludeSelectors = java.util.List.of();
    }

    public StaticHtmlDocumentParser(HtmlParserConfig config) {
        this.contentRootSelector = config.contentRootSelector();
        this.excludeSelectors = config.excludeSelectors();
        this.embeddedDataExtraction = config.embeddedDataExtraction();
    }

    private String embeddedDataExtraction = "NONE";

    @Override
    public SourceFormat sourceFormat() {
        return SourceFormat.STATIC_HTML;
    }

    @Override
    public ParseResult parse(SourceDocument source) {
        try {
            String html = Files.readString(source.path(), StandardCharsets.UTF_8);
            Document document = Jsoup.parse(html);
            Element extracted = "TESLA_SERVICE_CENTERS_V1".equals(embeddedDataExtraction)
                    ? new TeslaServiceCentersStaticDataExtractor().extract(document) : null;
            Document sanitized = new HtmlNoiseFilter().removeStructuralNoise(
                    new HtmlDomSanitizer(excludeSelectors).sanitize(document));
            Element root = extracted != null ? extracted : new HtmlContentRootSelector().select(sanitized, contentRootSelector);
            new HtmlResourceGuard().validate(root);
            if (new HtmlDynamicContentDetector().isUnsupported(root)) {
                return failure(source, "DYNAMIC_HTML_UNSUPPORTED", "静态清洗后没有足够正文，禁止动态渲染兜底");
            }
            HtmlTableExtraction tables = new HtmlTableExtractor().extract(root, source.metadata().documentId(),
                    new HtmlSourceLocatorFactory(), "", 0);
            return new ParseResult(new HtmlStructureWalker().walk(root), tables.tables(), tables.diagnostics());
        } catch (IllegalArgumentException exception) {
            return failure(source, exception.getMessage(), "HTML 内容根配置无效");
        } catch (Exception exception) {
            return failure(source, "HTML_PARSE_FAILED", "静态 HTML 解析失败");
        }
    }

    private ParseResult failure(SourceDocument source, String reason, String summary) {
        return new ParseResult(List.of(), List.of(new ParseDiagnostic(reason, DiagnosticSeverity.ERROR,
                source.metadata().documentId(), new SourceLocator(SourceFormat.STATIC_HTML, 0, 0, null, 0, 0, null, 0), summary)));
    }
}
