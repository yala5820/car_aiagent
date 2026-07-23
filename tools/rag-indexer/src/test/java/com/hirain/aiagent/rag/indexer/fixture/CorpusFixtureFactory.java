package com.hirain.aiagent.rag.indexer.fixture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 为端到端测试创建显式声明的临时语料根，不依赖机器路径或目录自动扫描。 */
public final class CorpusFixtureFactory {
    private CorpusFixtureFactory() { }
    public static Path writeUtf8(Path root, String relativePath, String content) throws IOException {
        Path output = root.resolve(relativePath).normalize();
        if (!output.startsWith(root.normalize())) throw new IllegalArgumentException("fixture path escapes root");
        Files.createDirectories(output.getParent()); Files.writeString(output, content, StandardCharsets.UTF_8);
        return output;
    }
}
