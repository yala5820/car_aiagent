package com.hirain.aiagent.rag.document;

import com.hirain.aiagent.rag.model.SourceFormat;
import com.hirain.aiagent.rag.model.SourceLocator;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 三格式引用必须采用实际 Locator 语义，不得为网页或 Markdown 伪造页码。 */
public class SourceCitationRendererTest {
    private final SourceCitationRenderer renderer = new SourceCitationRenderer();
    @Test public void rendersPdfPrintedAndPhysicalPage() {
        String text = renderer.render("E1", "车辆手册", new SourceLocator(SourceFormat.PDF, List.of("空调"), 86, 86, "82", "82", null, 0, 0, 1));
        assertTrue(text.contains("印刷页 82（PDF 第 86 页）"));
    }
    @Test public void rendersHtmlAnchorWithoutPage() {
        String text = renderer.render("E2", "功能说明", new SourceLocator(SourceFormat.STATIC_HTML, List.of("系统升级"), 0, 0, null, null, "upgrade-condition", 0, 0, 1));
        assertTrue(text.contains("锚点：upgrade-condition")); assertFalse(text.contains("PDF 第"));
    }
    @Test public void rendersMarkdownLinesWithoutPage() {
        String text = renderer.render("E3", "故障说明", new SourceLocator(SourceFormat.MARKDOWN, List.of("P001"), 0, 0, null, null, null, 120, 128, 1));
        assertTrue(text.contains("源码第 120—128 行")); assertFalse(text.contains("PDF 第"));
    }
}
