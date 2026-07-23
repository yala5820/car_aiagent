package com.hirain.aiagent.rag.indexer.pipeline;

import com.hirain.aiagent.rag.indexer.cli.CliCommandException;
import com.hirain.aiagent.rag.indexer.cli.CliExitCode;
import com.hirain.aiagent.rag.indexer.corpus.CorpusDefinition;
import com.hirain.aiagent.rag.indexer.corpus.CorpusPathResolver;
import com.hirain.aiagent.rag.indexer.corpus.CorpusResourceBudget;
import com.hirain.aiagent.rag.indexer.corpus.CorpusSecurityValidator;
import com.hirain.aiagent.rag.indexer.corpus.SourceFormatDetector;
import com.hirain.aiagent.rag.indexer.model.DocumentMetadata;
import com.hirain.aiagent.rag.indexer.model.SourceDocument;
import com.hirain.aiagent.rag.indexer.model.SourceFormat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 将 Corpus 中的人工声明转换为 Parser 可接受的输入快照。
 *
 * <p>此类是文件系统边界：后续 Parser 只能接收这里产出的 {@link SourceDocument}，从而保证
 * 路径逃逸、文件数量、单文件大小、声明哈希和扩展名冲突均在任何正文读取或第三方库加载之前失败。</p>
 */
public final class CorpusBuildInputPreparer {
    private final CorpusPathResolver pathResolver;
    private final CorpusSecurityValidator securityValidator;
    private final SourceFormatDetector formatDetector;

    public CorpusBuildInputPreparer() {
        this(new CorpusPathResolver(), new CorpusSecurityValidator(), new SourceFormatDetector());
    }

    CorpusBuildInputPreparer(CorpusPathResolver pathResolver, CorpusSecurityValidator securityValidator,
                             SourceFormatDetector formatDetector) {
        this.pathResolver = pathResolver;
        this.securityValidator = securityValidator;
        this.formatDetector = formatDetector;
    }

    public List<DocumentBuildState> prepare(Path corpusRoot, CorpusDefinition corpus, CorpusResourceBudget budget) {
        return prepare(corpusRoot, corpus, budget, corpusRoot.resolve(".rag-derived-sources"));
    }

    public List<DocumentBuildState> prepare(Path corpusRoot, CorpusDefinition corpus, CorpusResourceBudget budget,
                                            Path generatedSourcesDirectory) {
        if (corpus.documents().size() > budget.maxDocuments()) {
            throw new CliCommandException(CliExitCode.INPUT_OR_PARSE_ERROR, "SOURCE_DOCUMENT_COUNT_LIMIT_EXCEEDED");
        }
        List<DocumentBuildState> prepared = new ArrayList<>();
        for (var definition : corpus.documents()) {
            Path sourceFile;
            if ("STATIC_HTML_DIRECTORY".equals(definition.sourceLayout())) {
                sourceFile = new com.hirain.aiagent.rag.indexer.corpus.StaticHtmlDirectorySource().prepare(corpusRoot,
                        definition.relativePath(), definition.expectedSha256(), budget, generatedSourcesDirectory).generatedHtml();
            } else {
                sourceFile = pathResolver.resolve(corpusRoot, definition.relativePath());
                securityValidator.validateFile(sourceFile, budget);
                securityValidator.validateHash(sourceFile, definition.expectedSha256());
                formatDetector.verifyDeclaredExtension(sourceFile, definition.sourceFormat());
            }
            SourceFormat sourceFormat = SourceFormat.valueOf(definition.sourceFormat());
            SourceDocument source = new SourceDocument(sourceFile, sourceFormat,
                    new DocumentMetadata(definition.documentId(), definition.title(), definition.language()));
            prepared.add(new DocumentBuildState(definition, source, null, null, null));
        }
        return List.copyOf(prepared);
    }
}
