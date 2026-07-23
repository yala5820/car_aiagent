package com.hirain.aiagent.rag.policy;

import com.hirain.aiagent.rag.model.Applicability;
import com.hirain.aiagent.rag.model.SourceFormat;
import com.hirain.aiagent.rag.model.SourceLocator;
import com.hirain.aiagent.rag.model.VehicleKnowledgeEvidence;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class CitationGuardTest {
    @Test public void rejectsMissingOrUnknownEvidenceAndDeduplicatesInOrder() {
        CitationGuard guard = new CitationGuard();
        assertFalse(guard.validate("结论", Map.of("E1", new Object()), true).valid());
        assertFalse(guard.validate("结论[E2]", Map.of("E1", new Object()), true).valid());
        CitationValidationResult valid = guard.validate("结论[E1] 再次[E1]", Map.of("E1", new Object()), true);
        assertTrue(valid.valid());
        assertEquals(List.of("E1"), valid.evidenceIds());
    }

    @Test public void appendsLocatorDerivedSourceInsteadOfModelProvidedLocationText() {
        VehicleKnowledgeEvidence evidence = new VehicleKnowledgeEvidence("E1", "内容", "车辆手册", "1.0",
                new SourceLocator(SourceFormat.PDF, List.of("空调"), 86, 86, "82", "82", null, 0, 0, 1),
                Applicability.EXACT);
        CitationValidationResult result = new CitationGuard().validate("请检查空调设置[E1]", Map.of("E1", evidence), true);
        assertTrue(result.valid());
        assertTrue(result.output().contains("来源：[E1]《车辆手册》"));
        assertTrue(result.output().contains("印刷页 82（PDF 第 86 页）"));
    }
}
