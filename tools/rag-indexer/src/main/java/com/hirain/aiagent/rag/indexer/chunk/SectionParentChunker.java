package com.hirain.aiagent.rag.indexer.chunk;

import com.hirain.aiagent.rag.contract.RagTokenEstimator;
import com.hirain.aiagent.rag.indexer.model.*;
import java.util.*;

/** 按最小自然小节建立 Parent，再在 Paragraph 边界处理超长小节与边界 overlap。 */
final class SectionParentChunker {
    private final RagTokenEstimator tokens = new RagTokenEstimator();
    private final ParagraphReconstructor paragraphs = new ParagraphReconstructor();

    ParentChunkingResult chunk(SourceDocument source, ParseResult parsed, ChunkBoundaryPolicy policy) {
        List<ParentChunk> out = new ArrayList<>();
        List<ChunkDiagnostic> diagnostics = new ArrayList<>();
        append(source, new DocumentSectionTreeBuilder().build(parsed.blocks()), parsed.tables(), policy, out, diagnostics);
        return new ParentChunkingResult(List.copyOf(out), List.copyOf(diagnostics));
    }

    private void append(SourceDocument source, DocumentSection section, List<TableBlock> tables,
                        ChunkBoundaryPolicy policy, List<ParentChunk> out, List<ChunkDiagnostic> diagnostics) {
        if (!section.directBlocks.isEmpty()) {
            List<ReconstructedParagraph> reconstructed = paragraphs.reconstruct(section.directBlocks);
            List<List<ReconstructedParagraph>> rawSegments = split(reconstructed, policy.parentHardMaxTokens(), policy.parentSplitOverlapRatio(), diagnostics, section.path);
            List<TableBlock> related = tables.stream().filter(t -> Objects.equals(t.locator().headingPath(), section.path)).toList();
            for (int index = 0; index < rawSegments.size(); index++) {
                List<ReconstructedParagraph> segment = rawSegments.get(index);
                boolean overlap = index > 0;
                if (overlap) segment = withPreviousOverlap(segment, rawSegments.get(index - 1), policy.parentSplitOverlapRatio(), policy.parentHardMaxTokens());
                out.add(parent(source, section.path, segment, related, out.size() + 1, index, overlap));
            }
        }
        for (DocumentSection child : section.children) append(source, child, tables, policy, out, diagnostics);
    }

    private List<List<ReconstructedParagraph>> split(List<ReconstructedParagraph> values, int hard, double overlapRatio,
                                                      List<ChunkDiagnostic> diagnostics, String path) {
        if (values.isEmpty()) return List.of();
        List<List<ReconstructedParagraph>> result = new ArrayList<>();
        List<ReconstructedParagraph> current = new ArrayList<>();
        int segmentLimit = hard;
        for (ReconstructedParagraph paragraph : values) {
            int candidate = tokens.estimate(render(current, paragraph)).totalTokens();
            if (!current.isEmpty() && candidate > segmentLimit) {
                int previousTokens = tokens.estimate(render(current)).totalTokens();
                result.add(List.copyOf(current));
                current = new ArrayList<>();
                // 为下一个 Parent 的完整 Paragraph overlap 预留空间，避免 overlap 使 Parent 超过硬上限。
                segmentLimit = Math.max(1, hard - (int) Math.ceil(previousTokens * overlapRatio));
            }
            current.add(paragraph);
            if (paragraph.tokenCount() > hard) {
                diagnostics.add(new ChunkDiagnostic("PARENT_PARAGRAPH_OVER_HARD_LIMIT",
                        "小节 " + path + " 包含超过 Parent 硬上限的完整 Paragraph，不能在段落中间切断"));
            }
        }
        if (!current.isEmpty()) result.add(List.copyOf(current));
        return List.copyOf(result);
    }

    private List<ReconstructedParagraph> withPreviousOverlap(List<ReconstructedParagraph> segment,
                                                              List<ReconstructedParagraph> previous, double ratio, int hardLimit) {
        if (ratio <= 0 || segment.isEmpty() || previous.isEmpty()) return segment;
        int target = Math.max(1, (int) Math.ceil(tokens.estimate(render(previous)).totalTokens() * ratio));
        List<ReconstructedParagraph> tail = new ArrayList<>();
        int total = 0;
        int baseTokens = tokens.estimate(render(segment)).totalTokens();
        int targetColumn = segment.get(0).columnIndex();
        for (int i = previous.size() - 1; i >= 0; i--) {
            ReconstructedParagraph candidate = previous.get(i);
            if (candidate.columnIndex() != targetColumn) break;
            if (baseTokens + total + candidate.tokenCount() > hardLimit) break;
            tail.add(0, candidate);
            total += candidate.tokenCount();
            if (total >= target) break;
        }
        if (tail.isEmpty() || total < target) return segment;
        List<ReconstructedParagraph> combined = new ArrayList<>(tail);
        combined.addAll(segment);
        return List.copyOf(combined);
    }

    private ParentChunk parent(SourceDocument source, String path, List<ReconstructedParagraph> values,
                               List<TableBlock> tables, int ordinal, int segmentIndex, boolean overlap) {
        List<StructuredBlock> blocks = values.stream().flatMap(value -> value.blocks().stream()).toList();
        String text = render(values);
        return new ParentChunk(ordinal, source.metadata().documentId(), source.metadata().title(), path, text,
                blocks.get(0).locator(), blocks, tables, values, segmentIndex, overlap);
    }

    private String render(List<ReconstructedParagraph> values) {
        return String.join("\n\n", values.stream().map(ReconstructedParagraph::text).toList());
    }

    private String render(List<ReconstructedParagraph> current, ReconstructedParagraph next) {
        List<ReconstructedParagraph> values = new ArrayList<>(current);
        values.add(next);
        return render(values);
    }

    record ParentChunkingResult(List<ParentChunk> parents, List<ChunkDiagnostic> diagnostics) {}
}
