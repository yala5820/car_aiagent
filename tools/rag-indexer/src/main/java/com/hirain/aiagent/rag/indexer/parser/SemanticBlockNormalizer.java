package com.hirain.aiagent.rag.indexer.parser;

import com.hirain.aiagent.rag.indexer.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 将 Parser 的物理/段落级输出归一化为语义完整 Block。
 *
 * <p>普通文本先恢复明确的 PDF 续行，再按完整句子切分；标题、列表、Warning、代码等结构组
 * 不走普通句子切分，避免 Chunk 阶段再去猜测 Parser 已经确认的结构。</p>
 */
public final class SemanticBlockNormalizer {
    public ParseResult normalize(ParseResult parsed) {
        List<StructuredBlock> output = new ArrayList<>();
        List<StructuredBlock> pendingPdf = new ArrayList<>();
        for (StructuredBlock block : parsed.blocks()) {
            if (isAtomic(block)) {
                flushPdf(pendingPdf, output);
                addSplitSentences(block, output);
                continue;
            }
            if (block.type() == BlockType.PARAGRAPH && block.locator().sourceFormat() == SourceFormat.PDF) {
                if (!pendingPdf.isEmpty() && !canContinuePdf(pendingPdf.get(pendingPdf.size() - 1), block)) {
                    flushPdf(pendingPdf, output);
                }
                pendingPdf.add(block);
            } else {
                flushPdf(pendingPdf, output);
                addSplitSentences(block, output);
            }
        }
        flushPdf(pendingPdf, output);
        return new ParseResult(List.copyOf(output), parsed.tables(), parsed.diagnostics());
    }

    private boolean isAtomic(StructuredBlock block) {
        return block.type() != BlockType.PARAGRAPH || block.structure().atomicSemanticGroup();
    }

    private void flushPdf(List<StructuredBlock> lines, List<StructuredBlock> output) {
        if (lines.isEmpty()) {
            return;
        }
        StructuredBlock first = lines.get(0);
        if (lines.size() == 1) {
            addSplitSentences(first, output);
            lines.clear();
            return;
        }
        StringBuilder text = new StringBuilder();
        for (StructuredBlock line : lines) {
            if (text.length() > 0) {
                appendPdfContinuation(text, line.text());
            } else {
                text.append(line.text().strip());
            }
        }
        StructuredBlock merged = new StructuredBlock(
                BlockType.PARAGRAPH,
                text.toString(),
                mergeLocator(lines),
                mergeBounds(lines),
                first.confidence(),
                new BlockStructure(0, first.structure().sectionPath(), null, SequenceType.NONE, 0, false,
                        first.structure().documentOrdinal()));
        addSplitSentences(merged, output);
        lines.clear();
    }

    private void addSplitSentences(StructuredBlock source, List<StructuredBlock> output) {
        List<String> pieces = isAtomic(source) ? List.of(source.text()) : splitSentences(source.text());
        for (String sentence : pieces) {
            BlockStructure structure = source.structure();
            output.add(new StructuredBlock(
                    source.type(), sentence, source.locator(), source.boundingBox(), source.confidence(),
                    new BlockStructure(structure.headingLevel(), structure.sectionPath(), structure.sequenceGroupId(),
                            structure.sequenceType(), structure.listDepth(), structure.atomicSemanticGroup(),
                            output.size() + 1)));
        }
    }

    private List<String> splitSentences(String value) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int[] codePoints = value.strip().codePoints().toArray();
        for (int index = 0; index < codePoints.length; index++) {
            int codePoint = codePoints[index];
            current.appendCodePoint(codePoint);
            if (!isSentenceEnd(codePoints, index)) {
                continue;
            }
            while (index + 1 < codePoints.length && isClosingMark(codePoints[index + 1])) {
                current.appendCodePoint(codePoints[++index]);
            }
            result.add(current.toString().strip());
            current.setLength(0);
        }
        if (!current.toString().isBlank()) {
            result.add(current.toString().strip());
        }
        return result.isEmpty() ? List.of(value.strip()) : List.copyOf(result);
    }

    private boolean isSentenceEnd(int[] codePoints, int index) {
        int codePoint = codePoints[index];
        if (codePoint != '。' && codePoint != '！' && codePoint != '？'
                && codePoint != '!' && codePoint != '?' && codePoint != '.') {
            return false;
        }
        if (codePoint == '.' && index > 0 && index + 1 < codePoints.length
                && Character.isDigit(codePoints[index - 1]) && Character.isDigit(codePoints[index + 1])) {
            return false;
        }
        return true;
    }

    private boolean isClosingMark(int codePoint) {
        return "\"'”’」』）》)]}］}".indexOf(codePoint) >= 0;
    }

    private boolean canContinuePdf(StructuredBlock previous, StructuredBlock next) {
        if (!Objects.equals(previous.structure().sectionPath(), next.structure().sectionPath())
                || endsSentence(previous.text())) {
            return false;
        }
        int previousPage = previous.locator().pdfPageEnd();
        int nextPage = next.locator().pdfPageStart();
        if (nextPage != previousPage && nextPage != previousPage + 1) {
            return false;
        }
        if (nextPage != previousPage && !sameColumn(previous.boundingBox(), next.boundingBox())) {
            return false;
        }
        return sameColumn(previous.boundingBox(), next.boundingBox());
    }

    private boolean endsSentence(String value) {
        int[] codePoints = value.stripTrailing().codePoints().toArray();
        if (codePoints.length == 0) {
            return false;
        }
        int index = codePoints.length - 1;
        while (index >= 0 && isClosingMark(codePoints[index])) {
            index--;
        }
        return index >= 0 && (codePoints[index] == '。' || codePoints[index] == '！' || codePoints[index] == '？'
                || codePoints[index] == '!' || codePoints[index] == '?');
    }

    private boolean sameColumn(BoundingBox first, BoundingBox second) {
        if (first == null || second == null) {
            return false;
        }
        float overlap = Math.max(0, Math.min(first.right(), second.right()) - Math.max(first.left(), second.left()));
        float shortest = Math.min(first.right() - first.left(), second.right() - second.left());
        return shortest > 0 && overlap / shortest >= 0.25f;
    }

    private void appendPdfContinuation(StringBuilder target, String next) {
        String value = next.strip();
        if (value.isEmpty()) {
            return;
        }
        if (target.length() > 0 && needsSpace(target.charAt(target.length() - 1), value.charAt(0))) {
            target.append(' ');
        }
        target.append(value);
    }

    private boolean needsSpace(char previous, char next) {
        return Character.isLetterOrDigit(previous) && Character.isLetterOrDigit(next)
                && (previous < 128 || next < 128);
    }

    private SourceLocator mergeLocator(List<StructuredBlock> blocks) {
        SourceLocator first = blocks.get(0).locator();
        SourceLocator last = blocks.get(blocks.size() - 1).locator();
        return new SourceLocator(first.sourceFormat(), first.pdfPageStart(), last.pdfPageEnd(), first.htmlElementId(),
                first.sourceLineStart(), Math.max(first.sourceLineEnd(), last.sourceLineEnd()), first.headingPath(), first.sectionOrdinal());
    }

    private BoundingBox mergeBounds(List<StructuredBlock> blocks) {
        BoundingBox first = blocks.get(0).boundingBox();
        if (first == null) {
            return null;
        }
        float left = first.left(), top = first.top(), right = first.right(), bottom = first.bottom();
        for (StructuredBlock block : blocks) {
            if (block.boundingBox() == null) {
                continue;
            }
            left = Math.min(left, block.boundingBox().left());
            top = Math.min(top, block.boundingBox().top());
            right = Math.max(right, block.boundingBox().right());
            bottom = Math.max(bottom, block.boundingBox().bottom());
        }
        return new BoundingBox(left, top, right, bottom);
    }
}
