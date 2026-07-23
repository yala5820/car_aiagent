package com.hirain.aiagent.rag.indexer.corpus;

import com.hirain.aiagent.rag.indexer.cli.CliCommandException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证目录资料不会把 index 导航页纳入正文，且任一页面变更均会使目录指纹失效。 */
final class StaticHtmlDirectorySourceTest {
    @TempDir Path temporaryDirectory;

    @Test
    void shouldAggregateOnlyContentPagesUsingDeterministicDirectoryFingerprint() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("guide"));
        Files.writeString(source.resolve("index.html"), "<html><body>目录噪声</body></html>");
        Files.writeString(source.resolve("page-a.html"), "<html><body><h1 id='a'>页面 A</h1><p>正文 A</p></body></html>");
        Files.writeString(source.resolve("page-b.html"), "<html><body><h1 id='b'>页面 B</h1><p>正文 B</p></body></html>");
        String expected = directoryFingerprint(source, "page-a.html", "page-b.html");

        var prepared = new StaticHtmlDirectorySource().prepare(temporaryDirectory, "guide", expected,
                new CorpusResourceBudget(6, 1024), temporaryDirectory.resolve("derived"));
        String html = Files.readString(prepared.generatedHtml());

        assertEquals(2, prepared.pageCount());
        assertFalse(html.contains("目录噪声"));
        assertThrows(CliCommandException.class, () -> new StaticHtmlDirectorySource().prepare(temporaryDirectory, "guide",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", new CorpusResourceBudget(6, 1024),
                temporaryDirectory.resolve("derived-2")));
    }

    private static String directoryFingerprint(Path root, String... names) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String name : names) {
            digest.update(name.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(com.hirain.aiagent.rag.indexer.util.Sha256.file(root.resolve(name)).getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) '\n');
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
