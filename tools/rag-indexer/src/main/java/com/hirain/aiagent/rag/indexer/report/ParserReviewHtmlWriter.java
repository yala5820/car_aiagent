package com.hirain.aiagent.rag.indexer.report;

import com.hirain.aiagent.rag.indexer.model.StructuredBlock;
import com.hirain.aiagent.rag.indexer.parser.pdf.PdfRepeatedEdgeInspector;
import com.hirain.aiagent.rag.indexer.pipeline.DocumentBuildState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 写入本机可视化审核页，展示 Parser 实际保留和排除的内容。
 *
 * <p>预览是受保护 work 目录中的临时人工审核材料，可包含获授权原文；严禁提交 Git、复制到 Bundle 或发送到云端。</p>
 */
public final class ParserReviewHtmlWriter {
    public void write(Path file, List<DocumentBuildState> states) throws IOException {
        if (Files.exists(file)) throw new IllegalArgumentException("解析审核预览已存在，禁止覆盖");
        StringBuilder html = new StringBuilder("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">")
                .append("<title>RAG Parser 本地审核预览</title><style>")
                .append("body{font-family:system-ui,sans-serif;line-height:1.5;margin:24px;color:#18212f} details{margin:8px 0;border:1px solid #d7dee8;border-radius:6px;padding:8px} summary{cursor:pointer;font-weight:600} .meta{color:#52606d}.warning{border-left:4px solid #d97706;padding-left:8px}.removed{border-left:4px solid #dc2626;padding-left:8px;white-space:pre-wrap}.block{white-space:pre-wrap;margin:8px 0}.note{background:#eff6ff;padding:10px;border-radius:6px}</style></head><body>")
                .append("<h1>RAG Parser 本地审核预览</h1><p class=\"note\">本页只保存在 work/runs 目录：不会上传、不会调用模型、不会写入 Bundle。PDF 预览展示实际保留正文和被排除的重复页眉/页脚候选；HTML 预览展示实际进入 Parser 输出的正文块。</p>");
        for (DocumentBuildState state : states.stream().sorted(Comparator.comparing(value -> value.corpusDocument().documentId())).toList()) {
            writeDocument(html, state);
        }
        html.append("</body></html>");
        Files.writeString(file, html.toString(), StandardCharsets.UTF_8);
    }

    private void writeDocument(StringBuilder html, DocumentBuildState state) throws IOException {
        var document = state.corpusDocument();
        html.append("<hr><h2>").append(escape(document.documentId())).append("</h2><p class=\"meta\">来源：")
                .append(escape(document.relativePath())).append("；格式：").append(escape(document.sourceFormat()))
                .append("；保留 Block：").append(state.parseResult().blocks().size()).append("；表格：")
                .append(state.parseResult().tables().size()).append("</p>");
        if ("PDF".equals(document.sourceFormat())) writeRemovedEdges(html, state);
        writeRetainedBlocks(html, state);
    }

    private void writeRemovedEdges(StringBuilder html, DocumentBuildState state) throws IOException {
        List<PdfRepeatedEdgeInspector.RepeatedEdgeLine> edges = new PdfRepeatedEdgeInspector().inspect(state.sourceDocument().path());
        html.append("<details><summary>被 Parser 排除的重复页眉/页脚候选（").append(edges.size()).append(" 组）</summary>");
        if (edges.isEmpty()) html.append("<p>没有候选。</p>");
        for (PdfRepeatedEdgeInspector.RepeatedEdgeLine edge : edges) {
            html.append("<details><summary>出现 ").append(edge.physicalPages().size()).append(" 页：")
                    .append(escape(edge.physicalPages().toString())).append("</summary><div class=\"removed\">")
                    .append(escape(edge.text())).append("</div></details>");
        }
        html.append("</details>");
    }

    private void writeRetainedBlocks(StringBuilder html, DocumentBuildState state) {
        Map<Integer, List<StructuredBlock>> byLocator = new TreeMap<>();
        for (StructuredBlock block : state.parseResult().blocks()) {
            int locator = "PDF".equals(state.corpusDocument().sourceFormat())
                    ? block.locator().pdfPageStart() : block.locator().sectionOrdinal();
            byLocator.computeIfAbsent(locator, ignored -> new ArrayList<>()).add(block);
        }
        html.append("<details><summary>实际保留并送入后续 Chunk 的正文（按")
                .append("PDF 页 / HTML 段序号").append("折叠）</summary>");
        byLocator.forEach((locator, blocks) -> {
            html.append("<details><summary>定位 ").append(locator).append("：").append(blocks.size()).append(" 个 Block</summary>");
            for (StructuredBlock block : blocks) {
                html.append("<div class=\"").append(block.type().name().equals("WARNING") ? "block warning" : "block")
                        .append("\"><span class=\"meta\">").append(block.type()).append(" / ")
                        .append(block.confidence()).append("</span><br>").append(escape(block.text())).append("</div>");
            }
            html.append("</details>");
        });
        html.append("</details>");
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
