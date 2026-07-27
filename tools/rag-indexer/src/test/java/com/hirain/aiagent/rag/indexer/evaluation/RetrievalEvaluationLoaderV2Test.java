package com.hirain.aiagent.rag.indexer.evaluation;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Eval V2 Schema 的正向、AND/OR 语义和未知字段拒绝测试。 */
public class RetrievalEvaluationLoaderV2Test {
    @Test public void loadsParentEvidenceSets() throws Exception {
        Path file = Files.createTempFile("eval-v2-", ".json");
        Files.writeString(file, """
                {"schemaVersion":2,"datasetVersion":"v2","knowledgeScopeId":"scope","cases":[
                {"caseId":"c1","query":"如何操作？","category":"DIY_OPERATION","answerability":"ANSWERABLE","answerCriteria":["步骤"],"acceptableEvidenceSets":[{"requiredParentIds":["p1","p2"],"optionalLocatorChildIds":["c1"],"rationale":"两个小节共同覆盖"},{"requiredParentIds":["p3"],"optionalLocatorChildIds":[],"rationale":"单一完整小节"}],"tags":["HTML"],"split":"TEST"}]}
                """);
        RetrievalEvaluationDatasetV2 dataset = new RetrievalEvaluationLoaderV2().load(file);
        assertEquals("p1", dataset.cases().get(0).acceptableEvidenceSets().get(0).requiredParentIds().get(0));
        assertEquals(2, dataset.cases().get(0).acceptableEvidenceSets().size());
        Files.deleteIfExists(file);
    }

    @Test public void rejectsUnknownField() throws Exception {
        Path file = Files.createTempFile("eval-v2-invalid-", ".json");
        Files.writeString(file, "{\"schemaVersion\":2,\"datasetVersion\":\"v2\",\"knowledgeScopeId\":\"scope\",\"unknown\":1,\"cases\":[]}");
        try { new RetrievalEvaluationLoaderV2().load(file); fail("应拒绝未知字段"); }
        catch (IllegalArgumentException expected) { assertEquals("EVALUATION_V2_DATASET_INVALID", expected.getMessage()); }
        finally { Files.deleteIfExists(file); }
    }
}
