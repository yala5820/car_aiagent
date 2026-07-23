package com.hirain.aiagent.rag.indexer.embedding;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 统一读取本机 DashScope 凭证：显式环境变量优先，其次向上查找不提交的根 local.properties。
 * 凭证绝不进入配置对象、日志、异常、报告或缓存；读取失败与缺失均以空值交给调用方映射稳定错误码。
 */
public final class DashScopeApiKeyProvider {
    private static final String ENV_NAME = "DASHSCOPE_API_KEY";
    private static final String PROPERTY_NAME = "dashscope.api_key";
    private DashScopeApiKeyProvider() { }

    public static String load() { return load(Path.of(System.getProperty("user.dir", "."))); }

    static String load(Path start) {
        String environment = System.getenv(ENV_NAME);
        if (environment != null && !environment.isBlank()) return environment.trim();
        Path current = start == null ? null : start.toAbsolutePath().normalize();
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getParent()) {
            Path properties = current.resolve("local.properties");
            if (!Files.isRegularFile(properties)) continue;
            try (InputStream input = Files.newInputStream(properties)) {
                Properties values = new Properties(); values.load(input);
                String key = values.getProperty(PROPERTY_NAME);
                if (key != null && !key.isBlank()) return key.trim();
            } catch (Exception ignored) { /* 本地配置不可读时继续上溯，禁止输出路径和内容。 */ }
        }
        return null;
    }
}
