package com.hirain.aiagent.rag.indexer.report;

import com.hirain.aiagent.rag.indexer.corpus.CorpusDocumentDefinition;
import com.hirain.aiagent.rag.indexer.model.BoundingBox;
import com.hirain.aiagent.rag.indexer.model.BlockType;
import com.hirain.aiagent.rag.indexer.model.ExtractionConfidence;
import com.hirain.aiagent.rag.indexer.model.ParseResult;
import com.hirain.aiagent.rag.indexer.model.SourceDocument;
import com.hirain.aiagent.rag.indexer.model.SourceFormat;
import com.hirain.aiagent.rag.indexer.model.SourceLocator;
import com.hirain.aiagent.rag.indexer.model.StructuredBlock;
import com.hirain.aiagent.rag.indexer.pipeline.DocumentBuildState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** HTML 预览仅供本地审核，应完整展示 Parser 保留内容并进行 HTML 转义。 */
final class ParserReviewHtmlWriterTest {
    @TempDir Path root;

    @Test
    void shouldRenderEscapedRetainedHtmlBlock() throws Exception {
        String retained = "<正文>&内容";
        StructuredBlock block = new StructuredBlock(BlockType.PARAGRAPH, retained,
                new SourceLocator(SourceFormat.STATIC_HTML, 0, 0, null, 0, 0, null, 1),
                new BoundingBox(0, 0, 1, 1), ExtractionConfidence.HIGH);
        DocumentBuildState state = new DocumentBuildState(
                new CorpusDocumentDefinition("html", "title", "manual", "1", "zh", "STATIC_HTML", "manual.html", "b".repeat(64), null),
                new SourceDocument(root.resolve("manual.html"), SourceFormat.STATIC_HTML, new com.hirain.aiagent.rag.indexer.model.DocumentMetadata("html", "title", "zh")),
                new ParseResult(List.of(block), List.of()), null, null);
        Path preview = root.resolve("parser-review.html");

        new ParserReviewHtmlWriter().write(preview, List.of(state));

        String html = Files.readString(preview);
        assertTrue(html.contains("&lt;正文&gt;&amp;内容"));
        assertTrue(html.contains("实际保留并送入后续 Chunk 的正文"));
    }
}
