package com.hirain.aiagent.rag.indexer.cli;

import com.hirain.aiagent.rag.indexer.pipeline.BuildCancellationToken;
import com.hirain.aiagent.rag.indexer.pipeline.BuildExecutionContext;
import com.hirain.aiagent.rag.indexer.pipeline.BuildComponentFactory;
import com.hirain.aiagent.rag.indexer.pipeline.BuildWorkspace;
import com.hirain.aiagent.rag.indexer.pipeline.CorpusBuildInputPreparer;
import com.hirain.aiagent.rag.indexer.corpus.CorpusLoader;
import com.hirain.aiagent.rag.indexer.config.ConfigLoader;
import com.hirain.aiagent.rag.indexer.pipeline.DocumentBuildState;
import com.hirain.aiagent.rag.indexer.report.ParserReviewReportWriter;
import com.hirain.aiagent.rag.indexer.report.ParserReviewHtmlWriter;
import com.hirain.aiagent.rag.indexer.report.ChunkReviewHtmlWriter;
import com.hirain.aiagent.rag.indexer.report.ChunkQualityAnalyzer;
import com.hirain.aiagent.rag.indexer.report.ChunkQualityReportWriter;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 输入/Parser 校验命令：不调用 Embedding、不写 Store，只生成不含正文的人工审核定位报告。 */
public final class ValidateCommand extends AbstractPipelineCommand {
    public ValidateCommand(BuildCancellationToken cancellationToken) { super(cancellationToken); }
    @Override protected String commandName() { return "validate"; }
    @Override protected Set<String> requiredOptions() { return Set.of("--corpus", "--config", "--work-dir"); }
    @Override protected int executePipeline(BuildExecutionContext context, PrintStream output) {
        try {
            context.cancellationToken().throwIfCancelled();
            var corpus = new CorpusLoader().load(context.arguments().corpus());
            var config = new ConfigLoader().load(context.arguments().config());
            Path corpusRoot = context.arguments().corpus().getParent();
            if (corpusRoot == null) throw new CliCommandException(CliExitCode.INPUT_OR_PARSE_ERROR, "CORPUS_ROOT_INVALID");
            Path run = new BuildWorkspace().create(context.arguments().workDirectory(), context.runId());
            var parser = new BuildComponentFactory().createParsingPipeline(config);
            var chunker = new BuildComponentFactory().createChunker(config);
            List<DocumentBuildState> reviewed = new ArrayList<>();
            for (DocumentBuildState state : new CorpusBuildInputPreparer().prepare(corpusRoot, corpus,
                    new com.hirain.aiagent.rag.indexer.corpus.CorpusResourceBudget(6, 50L * 1024L * 1024L))) {
                context.cancellationToken().throwIfCancelled();
                var parsed = parser.parse(state.sourceDocument());
                reviewed.add(state.withParse(parsed).withChunks(chunker.chunk(state.sourceDocument(), parsed)));
            }
            new ParserReviewReportWriter().write(run.resolve("parser-review.json"), context.runId(), corpus.bundle().knowledgeScopeId(), reviewed);
            new ParserReviewHtmlWriter().write(run.resolve("parser-review.html"), reviewed);
            new ChunkReviewHtmlWriter().write(run.resolve("chunk-review.html"), reviewed);
            new ChunkQualityReportWriter().write(run.resolve("chunk-quality.json"),new ChunkQualityAnalyzer().analyze(reviewed));
            output.println("VALIDATION_PASSED runId=" + context.runId() + " reviewReport=parser-review.json reviewPreview=parser-review.html chunkQuality=chunk-quality.json chunkPreview=chunk-review.html");
            return CliExitCode.SUCCESS.value();
        } catch (CliCommandException exception) {
            throw exception;
        } catch (java.io.IOException | IllegalArgumentException exception) {
            throw new CliCommandException(CliExitCode.INPUT_OR_PARSE_ERROR, "VALIDATION_REVIEW_REPORT_FAILED");
        }
    }
}
