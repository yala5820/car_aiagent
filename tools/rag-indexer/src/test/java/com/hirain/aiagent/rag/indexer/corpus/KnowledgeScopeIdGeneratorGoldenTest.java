package com.hirain.aiagent.rag.indexer.corpus;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 与共享 Golden 的 exact-demo-scope 保持一致，防止 Android/离线 Scope ID 漂移。 */
final class KnowledgeScopeIdGeneratorGoldenTest {
    @Test void shouldGenerateSharedGoldenScopeId() {
        assertEquals("demo-model-2026-cn-demo-version-default", KnowledgeScopeIdGenerator.generate(
                new KnowledgeScopeDefinition("DEMO_MODEL", "2026", "CN", "DEMO_VERSION", "DEFAULT")));
    }
}
