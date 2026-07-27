package com.hirain.aiagent.rag.indexer.chunk;

import com.hirain.aiagent.rag.contract.RagTokenEstimator;
import com.hirain.aiagent.rag.indexer.model.BlockType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** 按 Paragraph 结构、尺寸和 Embedding 相似度生成 Child；不跨 Parent 或结构原子组。 */
final class ParagraphChildPlanner {
    private final RagTokenEstimator tokens = new RagTokenEstimator();

    Result plan(ParentChunk parent, ChunkBoundaryPolicy policy, Map<String, float[]> embeddings, double threshold) {
        List<ReconstructedParagraph> values = parent.paragraphs();
        if (values.isEmpty()) return new Result(List.of(), List.of());
        boolean hasAtomic = values.stream().anyMatch(ReconstructedParagraph::atomic);
        int total = tokens.estimate(parent.text()).totalTokens();
        if (!hasAtomic && total <= policy.softMaxChildTokens()) {
            return new Result(List.of(child(parent, values, "PARENT_AS_CHILD")), List.of());
        }
        List<ChunkDiagnostic> diagnostics = new ArrayList<>();
        List<Group> groups = new ArrayList<>();
        List<ReconstructedParagraph> current = new ArrayList<>();
        String currentReason = "PARAGRAPH_SEMANTIC_MERGE";
        List<ReconstructedParagraph> pendingTiny = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            ReconstructedParagraph paragraph = values.get(index);
            int size = paragraph.tokenCount();
            if (paragraph.atomic() || size > policy.hardMaxChildTokens()) {
                flushCurrent(groups, current, currentReason);
                current = new ArrayList<>();
                if (!pendingTiny.isEmpty()) {
                    if (!appendToLast(groups, pendingTiny, policy.hardMaxChildTokens(), false)) {
                        groups.add(new Group(new ArrayList<>(pendingTiny), "SMALL_PARAGRAPH_UNMERGED", false));
                        diagnostics.add(new ChunkDiagnostic("SMALL_PARAGRAPH_UNMERGED",
                                "Parent #" + parent.ordinal() + " 的极短 Paragraph 前后均无法在硬上限内吸附"));
                    }
                    pendingTiny.clear();
                }
                if (size > policy.hardMaxChildTokens()) {
                    List<String> fallback = fallback(paragraph.text(), policy.hardMaxChildTokens());
                    for (String text : fallback) groups.add(new Group(List.of(new ReconstructedParagraph(text,
                            paragraph.blocks(), paragraph.columnIndex(), paragraph.pageStart(), paragraph.pageEnd(),
                            tokens.estimate(text).totalTokens(), paragraph.boundingBox(), false, paragraph.structureKind(), paragraph.locator())), "LENGTH_FALLBACK", false));
                    diagnostics.add(new ChunkDiagnostic("CHILD_LENGTH_FALLBACK_WITHOUT_PARAGRAPH_BOUNDARY",
                            "Parent #" + parent.ordinal() + " 的 Paragraph 超过 Child 硬上限，已按句子/次级标点兜底"));
                } else {
                    groups.add(new Group(List.of(paragraph), paragraph.atomic() ? "STRUCTURAL_ATOMIC" : "PARAGRAPH_SINGLETON", paragraph.atomic()));
                }
                continue;
            }

            if (size < policy.forceMergeMaxTokens()) {
                if (!current.isEmpty() && !currentHasAtomic(current)
                        && tokens.estimate(render(current, paragraph)).totalTokens() <= policy.hardMaxChildTokens()) {
                    current.add(paragraph);
                    currentReason = "FORCED_SMALL_PARAGRAPH_MERGE";
                } else if (!appendToLast(groups, List.of(paragraph), policy.hardMaxChildTokens(), false)) {
                    pendingTiny.add(paragraph);
                } else {
                    markLastReason(groups, "FORCED_SMALL_PARAGRAPH_MERGE");
                }
                continue;
            }

            if (!pendingTiny.isEmpty()) {
                int pendingTokens = tokens.estimate(render(pendingTiny)).totalTokens();
                if (!paragraph.atomic() && pendingTokens + size <= policy.hardMaxChildTokens()) {
                    current = new ArrayList<>(pendingTiny);
                    current.add(paragraph);
                    currentReason = "FORCED_SMALL_PARAGRAPH_MERGE";
                    pendingTiny.clear();
                    continue;
                }
                groups.add(new Group(new ArrayList<>(pendingTiny), "SMALL_PARAGRAPH_UNMERGED", false));
                diagnostics.add(new ChunkDiagnostic("SMALL_PARAGRAPH_UNMERGED",
                        "Parent #" + parent.ordinal() + " 的极短 Paragraph 无可用相邻 Child"));
                pendingTiny.clear();
            }

            if (size >= policy.idealMinChildTokens()) {
                flushCurrent(groups, current, currentReason);
                current = new ArrayList<>(List.of(paragraph));
                currentReason = "PARAGRAPH_SINGLETON";
                continue;
            }

            if (current.isEmpty()) {
                current.add(paragraph);
                currentReason = size <= policy.directMergeMaxTokens()
                        ? "DIRECT_SMALL_PARAGRAPH_MERGE" : "PARAGRAPH_SEMANTIC_MERGE";
                continue;
            }
            ReconstructedParagraph previous = current.get(current.size() - 1);
            int candidateTokens = tokens.estimate(render(current, paragraph)).totalTokens();
            if (size <= policy.directMergeMaxTokens()) {
                if (candidateTokens <= policy.softMaxChildTokens()) {
                    current.add(paragraph);
                    currentReason = "DIRECT_SMALL_PARAGRAPH_MERGE";
                } else {
                    flushCurrent(groups, current, currentReason);
                    current = new ArrayList<>(List.of(paragraph));
                    currentReason = "DIRECT_SMALL_PARAGRAPH_MERGE";
                }
                continue;
            }
            float[] currentCenter = centroid(parent, current, embeddings);
            float[] previousVector = vector(parent, values, previous, embeddings);
            float[] nextVector = vector(parent, values, paragraph, embeddings);
            boolean merge = candidateTokens <= policy.softMaxChildTokens()
                    && cosine(currentCenter, nextVector) > threshold
                    && cosine(previousVector, nextVector) > threshold;
            if (merge) current.add(paragraph);
            else {
                flushCurrent(groups, current, currentReason);
                current = new ArrayList<>(List.of(paragraph));
                currentReason = "PARAGRAPH_SEMANTIC_MERGE";
            }
        }
        flushCurrent(groups, current, currentReason);
        if (!pendingTiny.isEmpty()) {
            if (!appendToLast(groups, pendingTiny, policy.hardMaxChildTokens(), false)) {
                groups.add(new Group(new ArrayList<>(pendingTiny), "SMALL_PARAGRAPH_UNMERGED", false));
                diagnostics.add(new ChunkDiagnostic("SMALL_PARAGRAPH_UNMERGED",
                        "Parent #" + parent.ordinal() + " 的极短 Paragraph 位于末尾且无法吸附"));
            } else {
                markLastReason(groups, "FORCED_SMALL_PARAGRAPH_MERGE");
            }
        }
        List<ChildChunk> output = new ArrayList<>();
        int ordinal = 0;
        for (Group group : groups) output.add(child(parent, group.paragraphs, group.reason, ++ordinal));
        return new Result(withTables(parent, output, policy), List.copyOf(diagnostics));
    }

    private void flushCurrent(List<Group> groups, List<ReconstructedParagraph> current, String reason) {
        if (!current.isEmpty()) groups.add(new Group(new ArrayList<>(current), reason, currentHasAtomic(current)));
    }

    private boolean appendToLast(List<Group> groups, List<ReconstructedParagraph> values, int hardLimit, boolean allowAtomic) {
        if (groups.isEmpty() || values.isEmpty()) return false;
        Group last = groups.get(groups.size() - 1);
        if ((!allowAtomic && last.atomic) || currentHasAtomic(last.paragraphs)) return false;
        int candidate = tokens.estimate(render(last.paragraphs, values)).totalTokens();
        if (candidate > hardLimit) return false;
        last.paragraphs.addAll(values);
        return true;
    }

    private void markLastReason(List<Group> groups, String reason) {
        if (!groups.isEmpty()) groups.get(groups.size() - 1).reason = reason;
    }

    private boolean currentHasAtomic(List<ReconstructedParagraph> values) {
        return values.stream().anyMatch(ReconstructedParagraph::atomic);
    }

    private static final class Group {
        private final List<ReconstructedParagraph> paragraphs;
        private String reason;
        private final boolean atomic;
        private Group(List<ReconstructedParagraph> paragraphs, String reason, boolean atomic) {
            this.paragraphs = new ArrayList<>(paragraphs);
            this.reason = reason;
            this.atomic = atomic;
        }
    }

    private ChildChunk child(ParentChunk parent, List<ReconstructedParagraph> values, String reason) {
        return child(parent, values, reason, 1);
    }

    private ChildChunk child(ParentChunk parent, List<ReconstructedParagraph> values, String reason, int ordinal) {
        ReconstructedParagraph first = values.get(0);
        String text = render(values);
        String type = values.stream().anyMatch(value -> "WARNING".equals(value.structureKind())) ? "WARNING" :
                (values.stream().anyMatch(ReconstructedParagraph::atomic) ? "STRUCTURAL_ATOMIC" : "TEXT");
        return new ChildChunk(parent.ordinal(), ordinal, text, first.locator(), type,
                tokens.estimate(text).totalTokens(), reason, 0);
    }

    private List<ChildChunk> withTables(ParentChunk parent, List<ChildChunk> values, ChunkBoundaryPolicy policy) {
        List<ChildChunk> output = new ArrayList<>(values);
        int ordinal = output.size();
        for (var table : parent.tables()) {
            for (String text : new TableChunkSplitter().split(table, policy.tableRowsPerChild())) {
                output.add(new ChildChunk(parent.ordinal(), ++ordinal, text, table.locator(), "TABLE",
                        tokens.estimate(text).totalTokens(), "STRUCTURAL_ATOMIC", 0));
            }
        }
        return List.copyOf(output);
    }

    private String render(List<ReconstructedParagraph> values) { return String.join("\n\n", values.stream().map(ReconstructedParagraph::text).toList()); }
    private String render(List<ReconstructedParagraph> left, List<ReconstructedParagraph> right) {
        List<ReconstructedParagraph> values = new ArrayList<>(left);
        values.addAll(right);
        return render(values);
    }
    private String render(List<ReconstructedParagraph> current, ReconstructedParagraph next) {
        List<ReconstructedParagraph> values = new ArrayList<>(current); values.add(next); return render(values);
    }

    private float[] vector(ParentChunk parent, List<ReconstructedParagraph> values, ReconstructedParagraph value, Map<String, float[]> embeddings) {
        int index = values.indexOf(value);
        return embeddings.getOrDefault(id(parent, index), new float[0]);
    }

    private float[] centroid(ParentChunk parent, List<ReconstructedParagraph> values, Map<String, float[]> embeddings) {
        List<float[]> vectors = new ArrayList<>();
        for (ReconstructedParagraph value : values) {
            float[] vector = embeddings.getOrDefault(id(parent, parent.paragraphs().indexOf(value)), new float[0]);
            if (vector.length > 0) vectors.add(vector);
        }
        if (vectors.isEmpty()) return new float[0];
        float[] center = new float[vectors.get(0).length];
        for (float[] vector : vectors) for (int i = 0; i < center.length; i++) center[i] += vector[i];
        for (int i = 0; i < center.length; i++) center[i] /= vectors.size();
        return center;
    }

    static String id(ParentChunk parent, int index) { return parent.documentId() + ":parent-" + parent.ordinal() + ":paragraph-" + index; }

    private float cosine(float[] left, float[] right) {
        if (left.length == 0 || right.length == 0 || left.length != right.length) return -1.0f;
        double dot = 0, leftNorm = 0, rightNorm = 0;
        for (int i = 0; i < left.length; i++) { dot += left[i] * right[i]; leftNorm += left[i] * left[i]; rightNorm += right[i] * right[i]; }
        return leftNorm == 0 || rightNorm == 0 ? -1.0f : (float) (dot / Math.sqrt(leftNorm * rightNorm));
    }

    private List<String> fallback(String text, int hard) {
        List<String> sentences = Arrays.stream(text.split("(?<=[。！？.!?])\\s*"))
                .map(String::trim).filter(value -> !value.isBlank()).toList();
        if (sentences.size() < 2) return splitLength(text, hard);
        List<String> result = new ArrayList<>(); StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            String candidate = current.length() == 0 ? sentence : current + "\n" + sentence;
            if (current.length() > 0 && tokens.estimate(candidate).totalTokens() > hard) { result.add(current.toString()); current.setLength(0); }
            if (current.length() > 0) current.append('\n'); current.append(sentence);
        }
        if (current.length() > 0) result.add(current.toString()); return List.copyOf(result);
    }
    private List<String> splitLength(String text, int hard) {
        List<String> result = new ArrayList<>(); StringBuilder current = new StringBuilder();
        text.codePoints().forEach(codePoint -> { String candidate = current + new String(Character.toChars(codePoint));
            if (current.length() > 0 && tokens.estimate(candidate).totalTokens() > hard) { result.add(current.toString()); current.setLength(0); }
            current.appendCodePoint(codePoint); });
        if (current.length() > 0) result.add(current.toString()); return List.copyOf(result);
    }

    record Result(List<ChildChunk> children, List<ChunkDiagnostic> diagnostics) {}
}
