package com.hirain.aiagent.rag.indexer.artifact;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 仅在 ObjectBox Store 已关闭后调用；流式 SHA-256 不把整库读入内存。 */
public final class BundleFileHasher {
    public FileHash hash(Path file) throws IOException {
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("Bundle 文件不存在");
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) >= 0;) digest.update(buffer, 0, count);
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) hex.append(String.format("%02x", value));
            return new FileHash(Files.size(file), hex.toString());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }

    public record FileHash(long sizeBytes, String sha256) { }
}
