package com.hirain.aiagent.rag.indexer.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RetrievalEvaluationLoaderTest {
    @TempDir Path temporaryDirectory;

    @Test
    void shouldLoadStrictVersionedDataset() throws Exception {
        Path file = write("{\"schemaVersion\":1,\"datasetVersion\":\"v1\",\"knowledgeScopeId\":\"scope\",\"cases\":[{\"caseId\":\"case-1\",\"query\":\"制动液\",\"expectedChunkIds\":[\"child-1\"]}]}");
        RetrievalEvaluationDataset dataset = new RetrievalEvaluationLoader().load(file);
        assertEquals("v1", dataset.datasetVersion());
        assertEquals("child-1", dataset.cases().get(0).expectedChunkIds().get(0));
    }

    @Test
    void shouldRejectUnknownFieldAndDuplicateQuery() throws Exception {
        Path unknown = write("{\"schemaVersion\":1,\"datasetVersion\":\"v1\",\"knowledgeScopeId\":\"scope\",\"extra\":true,\"cases\":[{\"caseId\":\"case-1\",\"query\":\"q\",\"expectedChunkIds\":[\"child-1\"]}]}");
        assertThrows(IllegalArgumentException.class, () -> new RetrievalEvaluationLoader().load(unknown));
        Path duplicate = write("{\"schemaVersion\":1,\"datasetVersion\":\"v1\",\"knowledgeScopeId\":\"scope\",\"cases\":[{\"caseId\":\"case-1\",\"query\":\"q\",\"expectedChunkIds\":[\"child-1\"]},{\"caseId\":\"case-2\",\"query\":\"q\",\"expectedChunkIds\":[\"child-2\"]}]}");
        assertThrows(IllegalArgumentException.class, () -> new RetrievalEvaluationLoader().load(duplicate));
    }

    private Path write(String value) throws Exception { Path file = temporaryDirectory.resolve(java.util.UUID.randomUUID() + ".json"); Files.writeString(file, value); return file; }
}
