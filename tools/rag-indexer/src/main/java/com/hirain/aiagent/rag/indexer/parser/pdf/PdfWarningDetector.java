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
        for (int index = 0; index < blocks.size(); index++) {
            StructuredBlock block = blocks.get(index);
            if (!isWarning(block.text())) {
                output.add(block);
                continue;
            }
            StringBuilder text = new StringBuilder(block.text());
            int consumed = index;
            // 仅在显式 Warning 还没有形成完整句子时补入紧随其后的续行，避免把同页后续章节吞入 Warning。
            while (needsContinuation(text) && consumed + 1 < blocks.size() && canJoin(block, blocks.get(consumed + 1))) {
                text.append("\n").append(blocks.get(++consumed).text());
            }
            BlockStructure source=block.structure();
            output.add(new StructuredBlock(BlockType.WARNING, text.toString(), block.locator(), block.boundingBox(), block.confidence(),
                    new BlockStructure(source.headingLevel(),source.sectionPath(),"warning-"+source.documentOrdinal(),SequenceType.WARNING,0,true,source.documentOrdinal())));
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
        // 仅把行首的显式警示标签视作 Warning；目录条目中的“限制和警告”等普通词不能吞并整页正文。
        return text.matches("(?s)^\\s*(警告|注意|禁止|危险)(?:[：:]|\\s|$).*?");
    }

    private boolean needsContinuation(StringBuilder text) {
        String value=text.toString().stripTrailing();
        return value.isEmpty() || !"。！？.!?".contains(value.substring(value.length()-1));
    }
}
