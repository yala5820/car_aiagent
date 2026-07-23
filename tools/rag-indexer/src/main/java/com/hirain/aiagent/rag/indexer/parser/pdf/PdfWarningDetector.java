package com.hirain.aiagent.rag.indexer.parser.pdf;

import com.hirain.aiagent.rag.indexer.model.BlockType;
import com.hirain.aiagent.rag.indexer.model.StructuredBlock;

import java.util.ArrayList;
import java.util.List;

/** 仅通过标题性警示词识别候选 Warning，不把颜色当作唯一特征，也不虚构缺失的条件或后果。 */
final class PdfWarningDetector {
    List<StructuredBlock> markWarnings(List<StructuredBlock> blocks) {
        List<StructuredBlock> output = new ArrayList<>();
        for (int index = 0; index < blocks.size(); index++) {
            StructuredBlock block = blocks.get(index);
            if (!isWarning(block.text())) {
                output.add(block);
                continue;
            }
            StringBuilder text = new StringBuilder(block.text());
            int consumed = index;
            while (consumed + 1 < blocks.size() && canJoin(block, blocks.get(consumed + 1))) {
                text.append("\n").append(blocks.get(++consumed).text());
            }
            output.add(new StructuredBlock(BlockType.WARNING, text.toString(), block.locator(), block.boundingBox(), block.confidence()));
            index = consumed;
        }
        return List.copyOf(output);
    }

    private boolean canJoin(StructuredBlock warningStart, StructuredBlock candidate) {
        return candidate.type() != BlockType.HEADING
                && candidate.locator().pdfPageStart() == warningStart.locator().pdfPageStart()
                && !isWarning(candidate.text());
    }

    private boolean isWarning(String text) {
        return text.matches("(?s).*?(警告|注意|禁止|危险).*?");
    }
}
