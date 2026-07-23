package com.hirain.aiagent.rag.indexer.corpus;

import java.util.Locale;

/** 与 Android Resolver 共享的 V1 Scope ID 规则：五个字段归一化后以短横线连接。 */
public final class KnowledgeScopeIdGenerator {
    private KnowledgeScopeIdGenerator() { }

    public static String generate(KnowledgeScopeDefinition scope) {
        return String.join("-", normalize(scope.vehicleModel()), normalize(scope.modelYear()), normalize(scope.region()),
                normalize(scope.softwareVersion()), normalize(scope.configurationCode()));
    }

    private static String normalize(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        normalized = normalized.replaceAll("^-+|-+$", "");
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Scope 字段归一化后为空");
        }
        return normalized;
    }
}
