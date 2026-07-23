package com.hirain.aiagent.rag.indexer.parser.pdf;

import com.hirain.aiagent.rag.indexer.model.BlockType;
import com.hirain.aiagent.rag.indexer.model.BlockStructure;
import com.hirain.aiagent.rag.indexer.model.ExtractionConfidence;
import com.hirain.aiagent.rag.indexer.model.SourceFormat;
import com.hirain.aiagent.rag.indexer.model.SourceLocator;
import com.hirain.aiagent.rag.indexer.model.StructuredBlock;
import com.hirain.aiagent.rag.indexer.model.SequenceType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 将已恢复的行转为统一 Block；重复页眉页脚在这里被排除，确保不会流入后续 Chunk。 */
final class PdfBlockAssembler {
    List<StructuredBlock> assemble(List<PdfPageLayout> layouts, Set<String> headersAndFooters) {
        List<StructuredBlock> blocks = new ArrayList<>();
        PdfHeadingDetector headingDetector = new PdfHeadingDetector();
        PdfHeadingLevelResolver levelResolver = new PdfHeadingLevelResolver();
        PdfHeadingPathTracker paths = new PdfHeadingPathTracker();
        int ordinal=0;
        for (PdfPageLayout page : layouts) {
            float averageFontSize = page.lines().stream().map(PdfTextLine::averageFontSize)
                    .reduce(0.0f, Float::sum) / Math.max(1, page.lines().size());
            for (PdfTextLine line : page.lines()) {
                if (headersAndFooters.contains(normalize(line.text()))) {
                    continue;
                }
                boolean heading=headingDetector.isHeading(line, averageFontSize);
                int level=heading?levelResolver.resolve(line,averageFontSize):0;
                if(level>0) paths.accept(level,line.text());
                int current=++ordinal; String path=paths.path();
                blocks.add(new StructuredBlock(heading ? BlockType.HEADING : BlockType.PARAGRAPH, line.text(),
                        new SourceLocator(SourceFormat.PDF, page.physicalPage(), page.physicalPage(), null, 0, 0, path.isBlank()?null:path, current),
                        line.boundingBox(), ExtractionConfidence.MEDIUM,
                        new BlockStructure(level,path.isBlank()?null:path,null,SequenceType.NONE,0,false,current)));
            }
        }
        return List.copyOf(blocks);
    }

    static String normalize(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }
}
