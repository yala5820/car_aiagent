package com.hirain.aiagent.rag.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 共享 Schema 的 Fingerprint 算法。输入统一按 UTF-8 读取、移除 BOM 并把全部换行
 * 规范为 LF，再计算 SHA-256；两端必须使用此算法，不能分别对平台默认编码求 Hash。
 */
public final class SchemaFingerprint {

    private SchemaFingerprint() {
    }

    public static String sha256OfNormalizedUtf8(String source) {
        if (source == null) {
            throw new IllegalArgumentException("source 不能为空");
        }
        String normalized = normalizeUtf8Text(source);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder("sha256:");
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }

    static String normalizeUtf8Text(String source) {
        String withoutBom = source.startsWith("\uFEFF") ? source.substring(1) : source;
        return withoutBom.replace("\r\n", "\n").replace('\r', '\n');
    }
}
