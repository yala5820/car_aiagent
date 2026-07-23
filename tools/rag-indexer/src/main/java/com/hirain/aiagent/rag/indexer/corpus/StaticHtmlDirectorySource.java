package com.hirain.aiagent.rag.indexer.corpus;

import com.hirain.aiagent.rag.indexer.cli.CliCommandException;
import com.hirain.aiagent.rag.indexer.cli.CliExitCode;
import com.hirain.aiagent.rag.indexer.util.Sha256;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * 将已审核的静态 HTML 页面目录收敛为一个逻辑资料输入。
 *
 * <p>该类不执行脚本、不访问网络，也不修改原始资料。目录指纹由相对路径与每个文件的 SHA-256
 * 按稳定顺序计算；构建工作目录中的聚合 HTML 只是 Parser 输入副本，不能作为人工资料源或发布资产。</p>
 */
public final class StaticHtmlDirectorySource {
    static final int MAX_PAGE_COUNT = 96;

    public Prepared prepare(Path corpusRoot, String relativePath, String expectedSha256, CorpusResourceBudget budget,
                            Path generatedDirectory) {
        Path root = resolveDirectory(corpusRoot, relativePath);
        List<Path> pages = collectPages(root, budget);
        String actual = fingerprint(root, pages);
        if (!actual.equals(expectedSha256)) {
            throw new CliCommandException(CliExitCode.INPUT_OR_PARSE_ERROR, "SOURCE_HASH_MISMATCH");
        }
        try {
            Files.createDirectories(generatedDirectory);
            Path output = generatedDirectory.resolve("static-html-directory-" + actual + ".html");
            if (!Files.exists(output)) {
                Files.writeString(output, aggregate(root, pages), StandardCharsets.UTF_8);
            }
            return new Prepared(output, pages.size());
        } catch (IOException exception) {
            throw new CliCommandException(CliExitCode.INPUT_OR_PARSE_ERROR, "SOURCE_DIRECTORY_PREPARE_FAILED");
        }
    }

    private static Path resolveDirectory(Path corpusRoot, String relativePath) {
        try {
            Path root = corpusRoot.toRealPath();
            Path candidate = root.resolve(relativePath).normalize();
            if (!Files.isDirectory(candidate) || !candidate.toRealPath().startsWith(root)) {
                throw invalid();
            }
            return candidate.toRealPath();
        } catch (IOException exception) {
            throw invalid();
        }
    }

    private static List<Path> collectPages(Path root, CorpusResourceBudget budget) {
        try (var paths = Files.walk(root)) {
            List<Path> pages = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".html"))
                    // 索引页通常只承载重复导航；正文页仍可通过目录内任意非 index HTML 保留。
                    .filter(path -> !path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).matches("index(-[0-9]+)?\\.html"))
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString().replace('\\', '/')))
                    .toList();
            if (pages.isEmpty() || pages.size() > MAX_PAGE_COUNT) {
                throw invalid();
            }
            for (Path page : pages) {
                if (Files.size(page) > budget.maxSingleFileBytes() || !page.toRealPath().startsWith(root)) {
                    throw invalid();
                }
            }
            return pages;
        } catch (IOException exception) {
            throw invalid();
        }
    }

    private static String fingerprint(Path root, List<Path> pages) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path page : pages) {
                digest.update(root.relativize(page).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Sha256.file(page).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private static String aggregate(Path root, List<Path> pages) throws IOException {
        Document output = Document.createShell("");
        Element main = output.body().appendElement("main").attr("id", "rag-static-html-directory");
        for (Path page : pages) {
            Document input = Jsoup.parse(Files.readString(page, StandardCharsets.UTF_8));
            Element source = input.selectFirst("main");
            if (source == null) source = input.selectFirst("article");
            if (source == null) source = input.body();
            Element section = main.appendElement("section").attr("id", "source-" + root.relativize(page).toString()
                    .replace('\\', '-').replace('.', '-'));
            section.attr("data-rag-source-path", root.relativize(page).toString().replace('\\', '/'));
            section.appendChildren(new ArrayList<>(source.children()));
        }
        return output.outerHtml();
    }

    private static CliCommandException invalid() {
        return new CliCommandException(CliExitCode.INPUT_OR_PARSE_ERROR, "SOURCE_DIRECTORY_INVALID");
    }

    public record Prepared(Path generatedHtml, int pageCount) { }
}
