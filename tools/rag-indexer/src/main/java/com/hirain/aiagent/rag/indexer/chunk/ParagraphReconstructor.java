package com.hirain.aiagent.rag.indexer.chunk;

import com.hirain.aiagent.rag.contract.RagTokenEstimator;
import com.hirain.aiagent.rag.indexer.model.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 在最小自然 Parent 内把 PDF 视觉行恢复为段落；HTML/Markdown 块天然已经是段落。 */
public final class ParagraphReconstructor {
    private final RagTokenEstimator tokens = new RagTokenEstimator();
    private final ParagraphReconstructionPolicy policy;

    public ParagraphReconstructor() { this(ParagraphReconstructionPolicy.defaults()); }
    public ParagraphReconstructor(ParagraphReconstructionPolicy policy) { this.policy = policy; }

    public List<ReconstructedParagraph> reconstruct(List<StructuredBlock> blocks) {
        List<ReconstructedParagraph> result = new ArrayList<>();
        List<StructuredBlock> current = new ArrayList<>();
        for (StructuredBlock block : blocks) {
            if (current.isEmpty() || canAppend(current.get(current.size() - 1), block, current)) {
                current.add(block);
            } else {
                result.add(build(current));
                current = new ArrayList<>();
                current.add(block);
            }
        }
        if (!current.isEmpty()) result.add(build(current));
        return List.copyOf(result);
    }

    private boolean canAppend(StructuredBlock previous, StructuredBlock next, List<StructuredBlock> current) {
        if (previous.locator().sourceFormat() != SourceFormat.PDF || next.locator().sourceFormat() != SourceFormat.PDF) {
            return false;
        }
        if (next.type() == BlockType.HEADING || previous.type() == BlockType.HEADING) return false;
        if (next.structure().fullWidth() || previous.structure().fullWidth()) return false;
        if (next.structure().columnIndex() != previous.structure().columnIndex()) return false;
        boolean sameAtomic = next.structure().atomicSemanticGroup()
                && previous.structure().atomicSemanticGroup()
                && Objects.equals(next.structure().sequenceGroupId(), previous.structure().sequenceGroupId());
        if (sameAtomic) return true;
        if (next.structure().atomicSemanticGroup() || previous.structure().atomicSemanticGroup()) return false;
        if (next.type() == BlockType.WARNING || previous.type() == BlockType.WARNING
                || next.type() == BlockType.LIST_ITEM || previous.type() == BlockType.LIST_ITEM) return false;
        if (previous.boundingBox() == null || next.boundingBox() == null) return false;
        float gap = next.boundingBox().top() - previous.boundingBox().bottom();
        float previousHeight = Math.max(1.0f, previous.boundingBox().bottom() - previous.boundingBox().top());
        if (gap < -policy.maxLineGap() || gap > Math.min(policy.maxLineGap(), previousHeight * policy.lineGapMultiplier())) return false;
        float leftDelta = Math.abs(next.boundingBox().left() - previous.boundingBox().left());
        float overlap = Math.min(previous.boundingBox().right(), next.boundingBox().right())
                - Math.max(previous.boundingBox().left(), next.boundingBox().left());
        float minWidth = Math.max(1.0f, Math.min(previous.boundingBox().right() - previous.boundingBox().left(),
                next.boundingBox().right() - next.boundingBox().left()));
        if (leftDelta > policy.horizontalTolerance() && overlap / minWidth < 0.15f) return false;
        // 跨页只恢复未结束的视觉段落，避免把同列的两个独立小节强行连接。
        return previous.locator().pdfPageEnd() == next.locator().pdfPageStart()
                ? needsContinuation(previous.text()) || gap <= policy.maxLineGap()
                : previous.locator().pdfPageEnd() + 1 == next.locator().pdfPageStart() && needsContinuation(previous.text());
    }

    private ReconstructedParagraph build(List<StructuredBlock> blocks) {
        String text = join(blocks);
        BoundingBox box = union(blocks);
        StructuredBlock first = blocks.get(0);
        StructuredBlock last = blocks.get(blocks.size() - 1);
        int column = first.structure().columnIndex();
        boolean atomic = blocks.stream().anyMatch(b -> b.structure().atomicSemanticGroup()
                || b.type() == BlockType.WARNING || b.type() == BlockType.LIST_ITEM || b.type() == BlockType.TABLE);
        String kind = atomic ? (blocks.stream().anyMatch(b -> b.type() == BlockType.WARNING) ? "WARNING" : "STRUCTURAL_ATOMIC") : "TEXT";
        return new ReconstructedParagraph(text, blocks, column, first.locator().pdfPageStart(), last.locator().pdfPageEnd(),
                tokens.estimate(text).totalTokens(), box, atomic, kind, first.locator());
    }

    private String join(List<StructuredBlock> blocks) {
        StringBuilder value = new StringBuilder();
        for (StructuredBlock block : blocks) {
            if (value.length() > 0 && needsSpace(value.charAt(value.length() - 1), block.text().charAt(0))) value.append(' ');
            value.append(block.text());
        }
        return value.toString().trim();
    }

    private boolean needsSpace(char previous, char next) {
        return (Character.isLetterOrDigit(previous) && Character.isLetterOrDigit(next))
                && previous < 128 && next < 128;
    }

    private BoundingBox union(List<StructuredBlock> blocks) {
        List<BoundingBox> boxes = blocks.stream().map(StructuredBlock::boundingBox).filter(Objects::nonNull).toList();
        if (boxes.isEmpty()) return null;
        return new BoundingBox(boxes.stream().map(BoundingBox::left).min(Float::compare).orElse(0.0f),
                boxes.stream().map(BoundingBox::top).min(Float::compare).orElse(0.0f),
                boxes.stream().map(BoundingBox::right).max(Float::compare).orElse(0.0f),
                boxes.stream().map(BoundingBox::bottom).max(Float::compare).orElse(0.0f));
    }

    private boolean needsContinuation(String text) {
        String value = text.stripTrailing();
        return value.isEmpty() || !"。！？.!?；;".contains(value.substring(value.length() - 1));
    }
}
