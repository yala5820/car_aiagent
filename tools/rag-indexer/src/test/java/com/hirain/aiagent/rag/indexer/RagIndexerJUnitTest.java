package com.hirain.aiagent.rag.indexer;

import org.junit.jupiter.api.Test;

/**
 * 让 Gradle 标准 `test` 任务真实发现并执行测试，避免将自定义 Smoke Test 的成功
 * 误认为测试框架已正常工作。完整断言仍保留在无依赖入口测试中，以验证命令行发布行为。
 */
final class RagIndexerJUnitTest {

    @Test
    void shouldRunCommandLineSmokeChecks() {
        RagIndexerMainTest.main(new String[0]);
    }
}
