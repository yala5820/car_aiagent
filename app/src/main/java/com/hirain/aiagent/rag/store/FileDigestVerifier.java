package com.hirain.aiagent.rag.store;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/** 以流式方式复算 Bundle DB 摘要，避免把整个预构建数据库加载进内存。 */
public final class FileDigestVerifier {
    public KnowledgeStoreValidationResult verify(Path file, KnowledgeBundleManifest.DataFile expected) {
        try {
            if (file == null || expected == null || !Files.isRegularFile(file)) return KnowledgeStoreValidationResult.failure("BUNDLE_DATA_FILE_MISSING");
            if (Files.size(file) != expected.sizeBytes()) return KnowledgeStoreValidationResult.failure("BUNDLE_DATA_FILE_SIZE_MISMATCH");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) { byte[] buffer = new byte[8192]; for (int count; (count = input.read(buffer)) >= 0;) digest.update(buffer, 0, count); }
            StringBuilder hash = new StringBuilder(); for (byte value : digest.digest()) hash.append(String.format("%02x", value));
            return expected.sha256().equals(hash.toString()) ? KnowledgeStoreValidationResult.success() : KnowledgeStoreValidationResult.failure("BUNDLE_DATA_FILE_HASH_MISMATCH");
        } catch (Exception error) { return KnowledgeStoreValidationResult.failure("BUNDLE_DATA_FILE_UNREADABLE"); }
    }
}
