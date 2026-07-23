package com.hirain.aiagent.rag.model;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** 验证模型 JSON 是显式白名单，而不是对内部 RagResult 的字段排除。 */
public class RagJsonCodecTest {
    @Test
    public void encodesOnlyToolResultContractFields() {
        SourceLocator locator = new SourceLocator(SourceFormat.PDF, List.of("驾驶辅助", "AUTO HOLD"), 86, 86,
                "82", "82", null, 0, 0, 12);
        VehicleKnowledgeToolResult result = new VehicleKnowledgeToolResult(RagJsonCodec.TOOL_RESULT_SCHEMA_VERSION,
                RagStatus.SUCCESS, true, "AUTO HOLD 条件", List.of(new VehicleKnowledgeEvidence("E1", "可靠内容", "车辆手册", "1.0", locator, Applicability.EXACT)),
                List.of(), null, null);
        String json = new RagJsonCodec().encodeToolResult(result);

        assertTrue(json.contains("\"evidenceId\":\"E1\""));
        assertTrue(json.contains("\"pdfPageStart\":86"));
        assertFalse(json.contains("normalizedQuery"));
        assertFalse(json.contains("denseDistance"));
        assertFalse(json.contains("chunkId"));
        assertFalse(json.contains("elapsedMs"));
    }

    @Test
    public void rejectsEvidenceWhenNotAnswerable() {
        try {
            new VehicleKnowledgeToolResult(1, RagStatus.NO_EVIDENCE, false, "问题",
                    List.of(new VehicleKnowledgeEvidence("E1", "内容", "手册", "1", new SourceLocator(SourceFormat.MARKDOWN, List.of("章节"), 0, 0, null, null, null, 1, 1, 1), Applicability.EXACT)),
                    List.of(), null, "资料不足");
            fail("不可回答结果不应携带正文");
        } catch (IllegalArgumentException expected) { }
    }
}
