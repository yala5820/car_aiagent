package com.hirain.aiagent.rag.indexer.parser.pdf;

import com.hirain.aiagent.rag.indexer.model.BlockType;
import com.hirain.aiagent.rag.indexer.model.ExtractionConfidence;
import com.hirain.aiagent.rag.indexer.model.SourceFormat;
import com.hirain.aiagent.rag.indexer.model.SourceLocator;
import com.hirain.aiagent.rag.indexer.model.StructuredBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 将已恢复的行转为统一 Block；重复页眉页脚在这里被排除，确保不会流入后续 Chunk。 */
final class PdfBlockAssembler {
    List<StructuredBlock> assemble(List<PdfPageLayout> layouts, Set<String> headersAndFooters) {
        List<StructuredBlock> blocks = new ArrayList<>();
        PdfHeadingDetector headingDetector = new PdfHeadingDetector();
        for (PdfPageLayout page : layouts) {
            float averageFontSize = page.lines().stream().map(PdfTextLine::averageFontSize)
                    .reduce(0.0f, Float::sum) / Math.max(1, page.lines().size());
            for (PdfTextLine line : page.lines()) {
                if (headersAndFooters.contains(normalize(line.text()))) {
                    continue;
                }
                blocks.add(new StructuredBlock(
                        headingDetector.isHeading(line, averageFontSize) ? BlockType.HEADING : BlockType.PARAGRAPH,
                        line.text(),
                        new SourceLocator(SourceFormat.PDF, page.physicalPage(), page.physicalPage(), null, 0, 0, null, page.physicalPage()),
                        line.boundingBox(), ExtractionConfidence.MEDIUM));
            }
        }
        return List.copyOf(blocks);
    }

    static String normalize(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }
}
