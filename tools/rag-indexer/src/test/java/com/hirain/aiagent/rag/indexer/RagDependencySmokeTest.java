package com.hirain.aiagent.rag.indexer;

import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.jsoup.Jsoup;

import technology.tabula.ObjectExtractor;
import technology.tabula.Page;
import technology.tabula.extractors.BasicExtractionAlgorithm;

/**
 * G002 只验证候选依赖能在同一 Java 17 进程加载，不执行 Parser、网络或数据库写入。
 * 正式解析和 ObjectBox Schema 行为必须分别在后续 Task 的测试中验证，避免把类加载成功
 * 误写成跨端数据库或语料处理已经通过。
 */
final class RagDependencySmokeTest {

    private RagDependencySmokeTest() {
    }

    static void verifyCandidateClasses() {
        requireLoadable("io.objectbox.BoxStore");
        requireLoadable("org.apache.pdfbox.pdmodel.PDDocument");
        requireLoadable("technology.tabula.ObjectExtractor");
        requireLoadable("org.jsoup.Jsoup");
        requireLoadable("org.commonmark.parser.Parser");
        requireLoadable("org.commonmark.ext.gfm.tables.TablesExtension");
        requireLoadable("com.fasterxml.jackson.databind.ObjectMapper");
        requireLoadable("picocli.CommandLine");
        requireLoadable("okhttp3.OkHttpClient");
        verifyPdfBoxAndTabulaCoexistence();
        verifyStaticHtmlParsing();
        verifyCommonMarkGfmTableParsing();
    }

    private static void requireLoadable(String className) {
        try {
            Class.forName(className);
        } catch (ClassNotFoundException exception) {
            throw new AssertionError("候选依赖无法在同一 JVM 加载：" + className, exception);
        }
    }

    private static void verifyPdfBoxAndTabulaCoexistence() {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            try (ObjectExtractor extractor = new ObjectExtractor(document)) {
                Page firstPage = extractor.extract(1);
                new BasicExtractionAlgorithm().extract(firstPage);
            }
        } catch (Exception exception) {
            throw new AssertionError("PDFBox 与 Tabula 无法在同一 Java 17 进程完成最小文本页提取", exception);
        }
    }

    private static void verifyStaticHtmlParsing() {
        var document = Jsoup.parse("<html><body><main><h1>维护</h1>"
                + "<table><tr><th>项目</th></tr><tr><td>制动液</td></tr></table>"
                + "</main></body></html>");
        if (document.selectFirst("main > table") == null) {
            throw new AssertionError("静态 HTML DOM/表格最小样本未被识别");
        }
    }

    private static void verifyCommonMarkGfmTableParsing() {
        Node root = Parser.builder()
                .extensions(List.of(TablesExtension.create()))
                .build()
                .parse("| 项目 | 周期 |\n| --- | --- |\n| 制动液 | 24 月 |\n");
        Node firstChild = root.getFirstChild();
        if (firstChild == null || !"TableBlock".equals(firstChild.getClass().getSimpleName())) {
            throw new AssertionError("CommonMark + GFM Table 最小样本未生成表格节点");
        }
    }
}
