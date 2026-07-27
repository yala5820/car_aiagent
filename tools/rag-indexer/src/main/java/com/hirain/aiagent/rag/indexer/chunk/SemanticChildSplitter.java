package com.hirain.aiagent.rag.indexer.chunk;

import com.hirain.aiagent.rag.contract.RagTokenEstimator;
import com.hirain.aiagent.rag.indexer.model.*;

import java.util.*;

/** V2 Child：先保留完整 Parent/语义组，只有超过预算才在同一 Parent 内递归拆分。 */
final class SemanticChildSplitter {
    private final RagTokenEstimator tokens = new RagTokenEstimator();

    Result split(ParentChunk parent, ChunkBoundaryPolicy policy) {
        return split(parent, policy, Map.of(), 0.7d);
    }

    Result split(ParentChunk parent, ChunkBoundaryPolicy policy, Map<String, float[]> paragraphEmbeddings, double threshold) {
        if (!parent.paragraphs().isEmpty() && parent.locator().sourceFormat() == com.hirain.aiagent.rag.indexer.model.SourceFormat.PDF) {
            ParagraphChildPlanner.Result planned = new ParagraphChildPlanner().plan(parent, policy, paragraphEmbeddings, threshold);
            return new Result(planned.children(), planned.diagnostics());
        }
        int target = policy.maxChildTokens();
        int soft = policy.softMaxChildTokens();
        int hard = policy.hardMaxChildTokens();
        String full = parent.text();
        // 只有真正短小的 Parent 直接作为 Child；超过 soft 后仍需在完整 Block 边界重新聚合。
        if (tokens.estimate(full).totalTokens() <= soft) {
            return new Result(withTables(parent, List.of(new ChildChunk(
                    parent.ordinal(), 1, full, parent.locator(), evidenceType(parent.blocks()),
                    tokens.estimate(full).totalTokens(), "PARENT_AS_CHILD", 0)), policy), List.of());
        }

        List<List<StructuredBlock>> units = units(parent.blocks());
        List<ChildChunk> out = new ArrayList<>();
        List<ChunkDiagnostic> diagnostics = new ArrayList<>();
        List<StructuredBlock> current = new ArrayList<>();
        int ordinal = 0;

        for (List<StructuredBlock> unit : units) {
            int size = tokenCount(unit);
            if (size > hard && unit.size() == 1 && !unit.get(0).structure().atomicSemanticGroup()) {
                if (!current.isEmpty()) {
                    out.add(child(parent, ++ordinal, current));
                    current = new ArrayList<>();
                }
                FallbackResult fallback = fallbackSentences(unit.get(0).text(), hard);
                String previous = "";
                for (String text : fallback.chunks()) {
                    int overlap = fallback.canReuseCompleteSentence() && !previous.isBlank()
                            ? tokens.estimate(previous).totalTokens() : 0;
                    out.add(new ChildChunk(parent.ordinal(), ++ordinal, text, unit.get(0).locator(),
                            evidenceType(unit), tokens.estimate(text).totalTokens(), "LENGTH_FALLBACK", overlap));
                    previous = lastSentence(text);
                }
                if (!fallback.canReuseCompleteSentence()) {
                    diagnostics.add(new ChunkDiagnostic("CHILD_LENGTH_FALLBACK_WITHOUT_SENTENCE_BOUNDARY",
                            "Parent #" + parent.ordinal() + " 的超长自然段没有可复用的完整句子，已按长度安全拆分且不使用 overlap"));
                }
                continue;
            }
            if (size > hard) {
                if (!current.isEmpty()) {
                    out.add(child(parent, ++ordinal, current));
                    current = new ArrayList<>();
                }
                AtomicSplitResult atomic = splitAtomicUnit(unit, hard);
                for (String text : atomic.chunks()) {
                    out.add(new ChildChunk(parent.ordinal(), ++ordinal, text, unit.get(0).locator(),
                            evidenceType(unit), tokens.estimate(text).totalTokens(), "ATOMIC_GROUP_FALLBACK", 0));
                }
                diagnostics.add(new ChunkDiagnostic("CHILD_ATOMIC_GROUP_SPLIT_FALLBACK",
                        "Parent #" + parent.ordinal() + " 的列表、步骤或 Warning 原子组超过 Child 硬上限，已在安全语义边界拆分"));
                continue;
            }

            List<StructuredBlock> candidate = new ArrayList<>(current);
            candidate.addAll(unit);
            int candidateTokens = tokenCount(candidate);
            // 优先在 target 附近结束；只有当前 Child 过短且合并后仍在 soft 内，才继续吸收下一个完整语义单元。
            if (!current.isEmpty() && shouldFlush(current, candidateTokens, policy)) {
                out.add(child(parent, ++ordinal, current));
                current = new ArrayList<>();
            }
            current.addAll(unit);
        }
        if (!current.isEmpty()) {
            out.add(child(parent, ++ordinal, current));
        }
        return new Result(withTables(parent, out, policy), List.copyOf(diagnostics));
    }

    private List<List<StructuredBlock>> units(List<StructuredBlock> blocks) {
        List<List<StructuredBlock>> out = new ArrayList<>();
        List<StructuredBlock> group = new ArrayList<>();
        String key = null;
        for (StructuredBlock block : blocks) {
            String next = block.structure().sequenceGroupId();
            boolean atomic = block.structure().atomicSemanticGroup() && next != null;
            if (atomic && Objects.equals(key, next)) {
                group.add(block);
                continue;
            }
            if (!group.isEmpty()) {
                out.add(List.copyOf(group));
            }
            group = new ArrayList<>();
            group.add(block);
            key = atomic ? next : null;
            if (!atomic) {
                out.add(List.copyOf(group));
                group = new ArrayList<>();
            }
        }
        if (!group.isEmpty()) {
            out.add(List.copyOf(group));
        }
        return out;
    }

    /** 仅长度兜底：按完整句子切分，下一段复用最后一句以保留边界关系，且绝不跨 Parent。 */
    private FallbackResult fallbackSentences(String text, int hard) {
        List<String> sentences = Arrays.stream(text.split("(?<=[。！？.!?])\\s*"))
                .filter(value -> !value.isBlank()).toList();
        if (sentences.size() < 2 || sentences.stream().anyMatch(sentence -> tokens.estimate(sentence).totalTokens() > hard)) {
            return new FallbackResult(splitByLength(text, hard), false);
        }
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String previous = "";
        for (String sentence : sentences) {
            String candidate = append(current, sentence);
            if (current.length() > 0 && tokens.estimate(candidate).totalTokens() > hard) {
                out.add(current.toString());
                current = new StringBuilder();
                if (!previous.isBlank() && tokens.estimate(previous).totalTokens() <= Math.max(1, hard / 10)) {
                    current.append(previous);
                }
                candidate = append(current, sentence);
                if (tokens.estimate(candidate).totalTokens() > hard) {
                    current.setLength(0);
                }
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(sentence);
            previous = sentence;
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return new FallbackResult(List.copyOf(out), true);
    }

    /** 原子组优先按句子/次级标点边界拆分，只有没有可用边界时才按长度兜底。 */
    private AtomicSplitResult splitAtomicUnit(List<StructuredBlock> unit, int hard) {
        List<String> chunks = new ArrayList<>();
        for (StructuredBlock block : unit) {
            String text = block.text();
            if (tokens.estimate(text).totalTokens() <= hard) {
                appendBounded(chunks, text, hard);
            } else {
                chunks.addAll(splitAtomicText(text, hard));
            }
        }
        return new AtomicSplitResult(List.copyOf(chunks));
    }

    private void appendBounded(List<String> chunks, String text, int hard) {
        if (chunks.isEmpty()) {
            chunks.add(text);
            return;
        }
        String candidate = chunks.get(chunks.size() - 1) + "\n" + text;
        if (tokens.estimate(candidate).totalTokens() <= hard) {
            chunks.set(chunks.size() - 1, candidate);
        } else {
            chunks.add(text);
        }
    }

    private List<String> splitAtomicText(String text, int hard) {
        List<String> primary = splitAtBoundaries(text, "(?<=[。！？.!?])\\s*");
        if (primary.size() > 1 && primary.stream().allMatch(value -> tokens.estimate(value).totalTokens() <= hard)) {
            return pack(primary, hard);
        }
        List<String> secondary = splitAtBoundaries(text, "(?<=[。！？.!?；;：:、，,])\\s*");
        if (secondary.size() > 1 && secondary.stream().allMatch(value -> tokens.estimate(value).totalTokens() <= hard)) {
            return pack(secondary, hard);
        }
        return splitByLength(text, hard);
    }

    private List<String> splitAtBoundaries(String text, String regex) {
        return Arrays.stream(text.split(regex)).map(String::trim).filter(value -> !value.isBlank()).toList();
    }

    private List<String> pack(List<String> units, int hard) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String unit : units) {
            boolean empty = current.length() == 0;
            String candidate = empty ? unit : current + "\n" + unit;
            if (current.length() > 0 && tokens.estimate(candidate).totalTokens() > hard) {
                out.add(current.toString());
                current.setLength(0);
                empty = true;
            }
            if (!empty) {
                current.append('\n');
            }
            current.append(unit);
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return List.copyOf(out);
    }

    /** 无可用句界时才按 Unicode 码点递归兜底；此时不得复制半句，因此 overlap 固定为零。 */
    private List<String> splitByLength(String text, int hard) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        text.codePoints().forEach(codePoint -> {
            String candidate = current + new String(Character.toChars(codePoint));
            if (current.length() > 0 && tokens.estimate(candidate).totalTokens() > hard) {
                out.add(current.toString());
                current.setLength(0);
            }
            current.appendCodePoint(codePoint);
        });
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return List.copyOf(out);
    }

    private String append(StringBuilder current, String value) {
        return current.length() == 0 ? value : current + "\n" + value;
    }

    private String lastSentence(String text) {
        String[] values = text.split("(?<=[。！？.!?])\\s*");
        return values.length == 0 ? "" : values[values.length - 1];
    }

    private ChildChunk child(ParentChunk parent, int ordinal, List<StructuredBlock> blocks) {
        String text = String.join("\n", blocks.stream().map(StructuredBlock::text).toList());
        return new ChildChunk(parent.ordinal(), ordinal, text, blocks.get(0).locator(), evidenceType(blocks),
                tokens.estimate(text).totalTokens(), "SEMANTIC_GROUP", 0);
    }

    private String evidenceType(List<StructuredBlock> blocks) {
        return blocks.stream().anyMatch(b -> b.type() == BlockType.WARNING) ? "WARNING" : "TEXT";
    }

    private int tokenCount(List<StructuredBlock> blocks) {
        return tokens.estimate(String.join("\n", blocks.stream().map(StructuredBlock::text).toList())).totalTokens();
    }

    private boolean shouldFlush(List<StructuredBlock> current, int candidateTokens, ChunkBoundaryPolicy policy) {
        if (candidateTokens > policy.softMaxChildTokens()) {
            return true;
        }
        return candidateTokens > policy.maxChildTokens()
                && tokenCount(current) >= policy.idealMinChildTokens();
    }

    private record FallbackResult(List<String> chunks, boolean canReuseCompleteSentence) {}

    private record AtomicSplitResult(List<String> chunks) {}

    private List<ChildChunk> withTables(ParentChunk parent, List<ChildChunk> values, ChunkBoundaryPolicy policy) {
        List<ChildChunk> out = new ArrayList<>(values);
        int ordinal = out.size();
        for (TableBlock table : parent.tables()) {
            for (String text : new TableChunkSplitter().split(table, policy.tableRowsPerChild())) {
                out.add(new ChildChunk(parent.ordinal(), ++ordinal, text, table.locator(), "TABLE",
                        tokens.estimate(text).totalTokens(), "TABLE_ROWS", 0));
            }
        }
        return List.copyOf(out);
    }

    record Result(List<ChildChunk> children, List<ChunkDiagnostic> diagnostics) {}
}
