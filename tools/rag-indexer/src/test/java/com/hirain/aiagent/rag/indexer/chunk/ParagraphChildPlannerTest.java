package com.hirain.aiagent.rag.indexer.chunk;

import com.hirain.aiagent.rag.contract.RagTokenEstimator;
import com.hirain.aiagent.rag.indexer.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ParagraphChildPlannerTest {
    @Test
    void shouldMergeShortParagraphsOnlyWhenBothCosinesAreStrictlyAboveThreshold() {
        ParentChunk parent = parent(List.of("甲".repeat(120), "乙".repeat(120)));
        Map<String, float[]> vectors = new HashMap<>();
        vectors.put(ParagraphChildPlanner.id(parent, 0), new float[]{1, 0});
        vectors.put(ParagraphChildPlanner.id(parent, 1), new float[]{1, 0});

        var result = new ParagraphChildPlanner().plan(parent, policy(), vectors, 0.7d);

        assertEquals("PARAGRAPH_SEMANTIC_MERGE", result.children().get(0).splitReason());
        assertTrue(result.children().get(0).text().contains("甲".repeat(120)));
        assertTrue(result.children().get(0).text().contains("乙".repeat(120)));
    }

    @Test
    void shouldKeepCosineEqualToThresholdSeparate() {
        ParentChunk parent = parent(List.of("甲".repeat(120), "乙".repeat(120)));
        Map<String, float[]> vectors = Map.of(
                ParagraphChildPlanner.id(parent, 0), new float[]{1, 0},
                ParagraphChildPlanner.id(parent, 1), new float[]{0.7f, (float) Math.sqrt(0.51)});

        var result = new ParagraphChildPlanner().plan(parent, policy(), vectors, 0.7d);

        assertEquals(2, result.children().stream().limit(2).count());
        assertEquals("PARAGRAPH_SEMANTIC_MERGE", result.children().get(0).splitReason());
        assertEquals("PARAGRAPH_SEMANTIC_MERGE", result.children().get(1).splitReason());
    }

    @Test
    void shouldForceVeryShortParagraphIntoPreviousChild() {
        ParentChunk parent = parent(List.of("甲".repeat(170), "。".repeat(10)));

        var result = new ParagraphChildPlanner().plan(parent, policy(), Map.of(), 0.7d);

        assertTrue(result.children().get(0).tokenEstimate() <= 512);
        assertEquals("FORCED_SMALL_PARAGRAPH_MERGE", result.children().get(0).splitReason());
    }

    @Test
    void shouldDirectlyMergeMediumShortParagraphWithoutEmbedding() {
        ParentChunk parent = parent(List.of("甲".repeat(120), "乙".repeat(80)));

        var result = new ParagraphChildPlanner().plan(parent, policy(), Map.of(), 0.7d);

        assertEquals("DIRECT_SMALL_PARAGRAPH_MERGE", result.children().get(0).splitReason());
        assertTrue(result.children().get(0).text().contains("乙".repeat(80)));
    }

    @Test
    void shouldUseNextChildWhenVeryShortParagraphCannotFitPrevious() {
        ParentChunk parent = parent(List.of("甲".repeat(500), "。".repeat(20), "乙".repeat(120)));

        var result = new ParagraphChildPlanner().plan(parent, policy(), Map.of(), 0.7d);

        assertTrue(result.children().size() >= 2);
        assertTrue(result.children().stream().anyMatch(child -> child.text().contains("。".repeat(20))));
        assertTrue(result.children().stream().anyMatch(child -> "FORCED_SMALL_PARAGRAPH_MERGE".equals(child.splitReason())));
    }

    private ChunkBoundaryPolicy policy() { return new ChunkBoundaryPolicy(256, 0, 20, 384, 512, 160, 1200, 2000, 0.1d, 0.7d); }

    private ParentChunk parent(List<String> texts) {
        List<ReconstructedParagraph> paragraphs = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            var locator = new SourceLocator(SourceFormat.PDF, 1, 1, null, 0, 0, "A", i + 1);
            var structure = new BlockStructure(0, "A", null, SequenceType.NONE, 0, false, i + 1, 0, false);
            var block = new StructuredBlock(BlockType.PARAGRAPH, texts.get(i), locator,
                    new BoundingBox(50, i * 20, 250, i * 20 + 10), ExtractionConfidence.HIGH, structure,
                    new PdfLineMetadata(1, 0, i, false));
            paragraphs.add(new ReconstructedParagraph(texts.get(i), List.of(block), 0, 1, 1,
                    new RagTokenEstimator().estimate(texts.get(i)).totalTokens(), block.boundingBox(), false, "TEXT", locator));
        }
        // 追加一个明显超过 Child 目标区间的 Paragraph，确保测试走语义合并路径，避免命中“短 Parent = Child”。
        String filler = "长段落".repeat(400);
        var fillerLocator = new SourceLocator(SourceFormat.PDF, 1, 1, null, 0, 0, "A", paragraphs.size() + 1);
        var fillerStructure = new BlockStructure(0, "A", null, SequenceType.NONE, 0, false, paragraphs.size() + 1, 0, false);
        var fillerBlock = new StructuredBlock(BlockType.PARAGRAPH, filler, fillerLocator,
                new BoundingBox(50, paragraphs.size() * 20, 250, paragraphs.size() * 20 + 10), ExtractionConfidence.HIGH,
                fillerStructure, new PdfLineMetadata(1, 0, paragraphs.size(), false));
        paragraphs.add(new ReconstructedParagraph(filler, List.of(fillerBlock), 0, 1, 1,
                new RagTokenEstimator().estimate(filler).totalTokens(), fillerBlock.boundingBox(), false, "TEXT", fillerLocator));
        var first = paragraphs.get(0);
        return new ParentChunk(1, "doc", "title", "A", String.join("\n\n", paragraphs.stream().map(ReconstructedParagraph::text).toList()), first.locator(),
                first.blocks(), List.of(), paragraphs, 0, false);
    }
}
