package com.hirain.aiagent.rag.indexer.chunk;

import com.hirain.aiagent.rag.indexer.model.ParseResult;
import com.hirain.aiagent.rag.indexer.model.SourceDocument;
import com.hirain.aiagent.rag.indexer.embedding.ParagraphEmbeddingRequest;
import com.hirain.aiagent.rag.indexer.pipeline.BuildCancellationToken;
import java.util.Map;

import java.util.ArrayList;
import java.util.List;

/** G301 文档切分总入口；质量 Gate 必须已在上游通过。 */
public final class DocumentChunker {
    private final ChunkBoundaryPolicy policy;
    private final ParagraphEmbeddingProvider paragraphEmbeddingProvider;

    public DocumentChunker(ChunkBoundaryPolicy policy) {
        this(policy, null);
    }

    public DocumentChunker(ChunkBoundaryPolicy policy, ParagraphEmbeddingProvider paragraphEmbeddingProvider) {
        this.policy = policy;
        this.paragraphEmbeddingProvider = paragraphEmbeddingProvider;
    }

    public ChunkResult chunk(SourceDocument source, ParseResult parsed) {
        return chunk(source, parsed, new BuildCancellationToken());
    }

    public ChunkResult chunk(SourceDocument source, ParseResult parsed, BuildCancellationToken cancellationToken) {
        boolean v2Structure = parsed.blocks().stream().anyMatch(block -> block.structure().headingLevel() > 0);
        SectionParentChunker.ParentChunkingResult v2 = v2Structure ? new SectionParentChunker().chunk(source, parsed, policy) : null;
        List<ParentChunk> parents = v2 != null ? v2.parents() : new HeadingAwareParentChunker().chunk(source, parsed);
        List<ChildChunk> children = new ArrayList<>(); List<ChunkDiagnostic> diagnostics=new ArrayList<>(v2 == null ? List.of() : v2.diagnostics());
        Map<String, float[]> paragraphEmbeddings = paragraphEmbeddings(source, parents, diagnostics, cancellationToken);
        for (ParentChunk parent : parents) {
            if(v2Structure){
                SemanticChildSplitter.Result split = parent.locator().sourceFormat() == com.hirain.aiagent.rag.indexer.model.SourceFormat.PDF
                        ? new SemanticChildSplitter().split(parent,policy,paragraphEmbeddings,policy.paragraphCosineThreshold())
                        : new SemanticChildSplitter().split(parent,policy);
                children.addAll(split.children());diagnostics.addAll(split.diagnostics());
            }
            else children.addAll(new ChildChunkSplitter().split(parent, policy));
        }
        return new ChunkResult(parents, children, diagnostics);
    }

    private Map<String, float[]> paragraphEmbeddings(SourceDocument source, List<ParentChunk> parents,
                                                      List<ChunkDiagnostic> diagnostics, BuildCancellationToken cancellationToken) {
        List<ParagraphEmbeddingRequest> requests = new ArrayList<>();
        for (ParentChunk parent : parents) {
            for (int index = 0; index < parent.paragraphs().size(); index++) {
                var paragraph = parent.paragraphs().get(index);
                if (!paragraph.atomic() && paragraph.tokenCount() < policy.idealMinChildTokens()) {
                    requests.add(new ParagraphEmbeddingRequest(ParagraphChildPlanner.id(parent, index), parent.headingPath(),
                            parent.headingPath() + "\n" + paragraph.text()));
                }
            }
        }
        if (requests.isEmpty()) return Map.of();
        if (paragraphEmbeddingProvider == null) {
            // validate 是显式预览命令，不联网；正式 Build 通过 provider，缺失 provider 不可伪装成正式语义结果。
            diagnostics.add(new ChunkDiagnostic("SEMANTIC_EMBEDDING_FALLBACK", "预览模式未调用 Paragraph Embedding，短段落不执行语义合并"));
            return Map.of();
        }
        return Map.copyOf(paragraphEmbeddingProvider.embed(List.copyOf(requests), cancellationToken));
    }
}
