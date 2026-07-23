package com.hirain.aiagent.rag.indexer.fixture;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 审计 Fixture 的最小存在性与生成来源，防止后续测试悄然退回内联、不透明样本。 */
class FixtureAuditTest {
    @TempDir Path temporary;
    @Test void resourcesDeclareSyntheticOriginAndSecurityBoundary() throws Exception {
        String readme = new String(getClass().getResourceAsStream("/fixtures/README.md").readAllBytes());
        String dangerous = new String(getClass().getResourceAsStream("/fixtures/security/dangerous_static.html").readAllBytes());
        assertTrue(readme.contains("合成测试资料"));
        assertTrue(dangerous.contains("<script>") && dangerous.contains("file:///"));
    }
    @Test void generatedPdfIsARealPdfWithoutRepositoryBinary() throws Exception {
        Path pdf = PdfFixtureFactory.writeBrakeFluidFixture(temporary.resolve("fixture.pdf"));
        assertTrue(Files.size(pdf) > 0);
        assertTrue(new String(Files.readAllBytes(pdf), 0, 4).equals("%PDF"));
    }
}
