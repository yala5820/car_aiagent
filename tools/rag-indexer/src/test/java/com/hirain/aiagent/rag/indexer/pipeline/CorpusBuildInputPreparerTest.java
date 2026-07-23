package com.hirain.aiagent.rag.indexer.pipeline;

import com.hirain.aiagent.rag.indexer.cli.CliCommandException;
import com.hirain.aiagent.rag.indexer.corpus.BundleDefinition;
import com.hirain.aiagent.rag.indexer.corpus.CorpusDefinition;
import com.hirain.aiagent.rag.indexer.corpus.CorpusDocumentDefinition;
import com.hirain.aiagent.rag.indexer.corpus.CorpusResourceBudget;
import com.hirain.aiagent.rag.indexer.corpus.KnowledgeScopeDefinition;
import com.hirain.aiagent.rag.indexer.util.Sha256;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CorpusBuildInputPreparerTest {
    @TempDir Path root;

    @Test
    void shouldPrepareOnlyFilesThatPassAllPreParserChecks() throws Exception {
        Path source = root.resolve("manual.md");
        Files.writeString(source, "# 车辆说明");
        CorpusDefinition corpus = corpus("manual.md", Sha256.file(source));

        List<DocumentBuildState> states = new CorpusBuildInputPreparer().prepare(root, corpus,
                new CorpusResourceBudget(6, 1024));

        assertEquals(1, states.size());
        assertEquals(source.toRealPath(), states.get(0).sourceDocument().path());
        assertEquals("doc-1", states.get(0).sourceDocument().metadata().documentId());
    }

    @Test
    void shouldRejectCorpusOverConfiguredDocumentBudget() throws Exception {
        Path source = root.resolve("manual.md");
        Files.writeString(source, "# 车辆说明");
        KnowledgeScopeDefinition scope = new KnowledgeScopeDefinition("M1", "2026", "CN", "1.0", "BASE");
        String sha256 = Sha256.file(source);
        CorpusDefinition corpus = new CorpusDefinition(1, new BundleDefinition("bundle", "1.0", "scope", scope), List.of(
                document("doc-1", "manual.md", sha256, scope), document("doc-2", "manual.md", sha256, scope)));

        CliCommandException exception = assertThrows(CliCommandException.class,
                () -> new CorpusBuildInputPreparer().prepare(root, corpus, new CorpusResourceBudget(1, 1024)));

        assertEquals("SOURCE_DOCUMENT_COUNT_LIMIT_EXCEEDED", exception.reasonCode());
    }

    private static CorpusDefinition corpus(String relativePath, String sha256) {
        KnowledgeScopeDefinition scope = new KnowledgeScopeDefinition("M1", "2026", "CN", "1.0", "BASE");
        CorpusDocumentDefinition document = document("doc-1", relativePath, sha256, scope);
        return new CorpusDefinition(1, new BundleDefinition("bundle", "1.0", "scope", scope), List.of(document));
    }

    private static CorpusDocumentDefinition document(String documentId, String relativePath, String sha256,
                                                      KnowledgeScopeDefinition scope) {
        return new CorpusDocumentDefinition(documentId, "车辆说明", "manual", "1.0", "zh",
                "MARKDOWN", relativePath, sha256, scope);
    }
}
