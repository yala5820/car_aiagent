package com.hirain.aiagent.rag.indexer.parser.pdf;

import com.hirain.aiagent.rag.indexer.model.BlockType;
import com.hirain.aiagent.rag.indexer.model.StructuredBlock;
import com.hirain.aiagent.rag.indexer.model.BlockStructure;
import com.hirain.aiagent.rag.indexer.model.SequenceType;

import java.util.ArrayList;
import java.util.List;

/** 仅通过标题性警示词识别候选 Warning，不把颜色当作唯一特征，也不虚构缺失的条件或后果。 */
final class PdfWarningDetector {
    List<StructuredBlock> markWarnings(List<StructuredBlock> blocks) {
        List<StructuredBlock> output = new ArrayList<>();
        String warningGroup = null;
        int warningPage = -1;
        int warningColumn = -2;
        boolean continuationExpected = false;
        for (StructuredBlock block : blocks) {
            boolean startsWarning = isWarning(block.text());
            boolean sameWarning = warningGroup != null && continuationExpected && canJoin(warningPage, warningColumn, block);
            if (!startsWarning && !sameWarning) {
                warningGroup = null;
                continuationExpected = false;
                output.add(block);
                continue;
            }
            if (startsWarning) {
                warningGroup = "warning-" + block.structure().documentOrdinal();
                warningPage = block.locator().pdfPageStart();
                warningColumn = block.structure().columnIndex();
            }
            BlockStructure source = block.structure();
            output.add(new StructuredBlock(BlockType.WARNING, block.text(), block.locator(), block.boundingBox(), block.confidence(),
                    new BlockStructure(source.headingLevel(), source.sectionPath(), warningGroup, SequenceType.WARNING, 0,
                            true, source.documentOrdinal(), source.columnIndex(), source.fullWidth()), block.pdfLineMetadata()));
            continuationExpected = needsContinuation(block.text());
        }
        return List.copyOf(output);
    }

    private boolean canJoin(int page, int column, StructuredBlock candidate) {
        return candidate.type() != BlockType.HEADING
                && candidate.locator().pdfPageStart() == page
                && candidate.structure().columnIndex() == column
                && !candidate.structure().fullWidth();
    }

    private boolean isWarning(String text) {
        // 仅把行首的显式警示标签视作 Warning；目录条目中的“限制和警告”等普通词不能吞并整页正文。
        return text.matches("(?s)^\\s*(警告|注意|禁止|危险)(?:[：:]|\\s|$).*?");
    }

    private boolean needsContinuation(String text) {
        String value=text.stripTrailing();
        return value.isEmpty() || !"。！？.!?".contains(value.substring(value.length()-1));
    }
}
