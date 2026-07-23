package com.hirain.aiagent.rag.indexer.parser.html;

import com.hirain.aiagent.rag.indexer.config.HtmlParserConfig;
import com.hirain.aiagent.rag.indexer.model.DocumentMetadata;
import com.hirain.aiagent.rag.indexer.model.SourceDocument;
import com.hirain.aiagent.rag.indexer.model.SourceFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 确保服务中心资料只读取静态 JSON，不因脚本文本或页面菜单污染正文。 */
final class TeslaServiceCentersStaticDataExtractorTest {
    @TempDir Path temporaryDirectory;

    @Test
    void shouldExtractServiceCenterRecordsWithoutExecutingScript() throws Exception {
        Path page = temporaryDirectory.resolve("service.html");
        Files.writeString(page, "<html><body><nav>导航噪声</nav><script id='__NEXT_DATA__' type='application/json'>"
                + "{\"props\":{\"locations\":[{\"uuid\":\"101\",\"location_type\":[\"service\"],\"_source\":{"
                + "\"marketing\":{\"display_name\":\"北京测试特斯拉中心\",\"service_center_phone\":\"010-12345678\"},"
                + "\"key_data\":{\"address\":{\"city\":\"北京\",\"address_1\":\"测试路 1 号\"}}}}]}}</script></body></html>");

        var parser = new StaticHtmlDocumentParser(new HtmlParserConfig("STATIC_DOM_ONLY", false, false, "", List.of(),
                "TESLA_SERVICE_CENTERS_V1"));
        var result = parser.parse(new SourceDocument(page, SourceFormat.STATIC_HTML,
                new DocumentMetadata("service", "服务中心", "zh-CN")));
        String text = result.blocks().stream().map(block -> block.text()).reduce("", (left, right) -> left + "\n" + right);

        assertTrue(text.contains("北京测试特斯拉中心"));
        assertTrue(text.contains("010-12345678"));
        assertTrue(text.contains("测试路 1 号"));
        assertFalse(text.contains("导航噪声"));
    }
}
