package com.hirain.aiagent.rag.indexer.report;

import com.hirain.aiagent.rag.indexer.pipeline.ChunkStableIds;
import com.hirain.aiagent.rag.indexer.pipeline.DocumentBuildState;
import com.hirain.aiagent.rag.indexer.pipeline.StableIdStageRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 仅本机展示 PDF 页到最终 Child Chunk ID 的映射，便于人工建立可追溯评测集。 */
public final class ChunkReviewHtmlWriter {
    public void write(Path file, java.util.List<DocumentBuildState> states) throws IOException {
        if (Files.exists(file)) throw new IllegalArgumentException("Chunk 审核预览已存在，禁止覆盖");
        StringBuilder html = new StringBuilder("<!doctype html><meta charset=\"UTF-8\"><title>RAG Chunk 审核</title><style>body{font-family:system-ui;margin:24px}details{margin:7px;padding:7px;border:1px solid #ddd;border-radius:6px}summary{cursor:pointer;font-weight:600}pre{white-space:pre-wrap}.meta{color:#555}</style><h1>RAG Chunk 本地审核</h1><p>仅本地 work 文件：每项显示最终稳定 Child Chunk ID、SourceLocator 与实际 Chunk 正文；不得提交、上传或写入 Bundle。</p>");
        for (DocumentBuildState state : states) {
            ChunkStableIds ids = new StableIdStageRunner().assign(state);
            html.append("<h2>").append(escape(state.corpusDocument().documentId())).append("</h2>");
            for (var child : state.chunkResult().children()) {
                var locator = child.locator();
                html.append("<details><summary>Child ").append(escape(ids.childIds().get(child))).append(" | ")
                        .append(escape(child.evidenceType())).append(" | PDF 页 ").append(locator.pdfPageStart()).append("-").append(locator.pdfPageEnd())
                        .append(" | 段 ").append(locator.sectionOrdinal()).append("</summary><pre>").append(escape(child.text())).append("</pre></details>");
            }
        }
        html.append("</html>"); Files.writeString(file, html.toString(), StandardCharsets.UTF_8);
    }
    private static String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
}
