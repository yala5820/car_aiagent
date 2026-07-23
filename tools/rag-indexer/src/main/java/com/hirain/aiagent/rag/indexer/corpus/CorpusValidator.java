package com.hirain.aiagent.rag.indexer.corpus;

import com.hirain.aiagent.rag.indexer.cli.CliCommandException;
import com.hirain.aiagent.rag.indexer.cli.CliExitCode;
import java.util.HashSet;
import java.util.Set;

/** 只验证声明层语义；路径、Hash 和实际文件格式不能在本层伪装为已验证。 */
public final class CorpusValidator {
    public void validate(CorpusDefinition corpus) {
        if (corpus.schemaVersion() != 1 || !KnowledgeScopeIdGenerator.generate(corpus.bundle().scope())
                .equals(corpus.bundle().knowledgeScopeId())) {
            fail("CORPUS_SCOPE_ID_INVALID");
        }
        Set<String> ids = new HashSet<>();
        for (CorpusDocumentDefinition document : corpus.documents()) {
            if (document.documentId() == null || document.documentId().isBlank() || !ids.add(document.documentId())) fail("CORPUS_DOCUMENT_ID_INVALID");
            if (!Set.of("PDF", "STATIC_HTML", "MARKDOWN").contains(document.sourceFormat())) fail("CORPUS_SOURCE_FORMAT_INVALID");
            if (document.relativePath() == null || document.relativePath().isBlank() || document.expectedSha256() == null
                    || !document.expectedSha256().matches("[0-9a-f]{64}")) fail("CORPUS_DOCUMENT_METADATA_INVALID");
            if (!Set.of("SINGLE_FILE", "STATIC_HTML_DIRECTORY").contains(document.sourceLayout())
                    || ("STATIC_HTML_DIRECTORY".equals(document.sourceLayout()) && !"STATIC_HTML".equals(document.sourceFormat()))) {
                fail("CORPUS_SOURCE_LAYOUT_INVALID");
            }
            validateApplicability(corpus.bundle().scope(), document.applicability());
        }
    }

    private static void validateApplicability(KnowledgeScopeDefinition bundle, KnowledgeScopeDefinition document) {
        if (!matches(bundle.vehicleModel(), document.vehicleModel()) || !matches(bundle.modelYear(), document.modelYear())
                || !matches(bundle.region(), document.region()) || !matches(bundle.softwareVersion(), document.softwareVersion())
                || !matches(bundle.configurationCode(), document.configurationCode())) fail("CORPUS_DOCUMENT_SCOPE_CONFLICT");
    }
    private static boolean matches(String bundle, String document) { return "*".equals(document) || bundle.equals(document); }
    private static void fail(String reason) { throw new CliCommandException(CliExitCode.ARGUMENT_OR_CONFIG_ERROR, reason); }
}
