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
        return assemble(layouts, headersAndFooters, new PdfTocReference(List.of(), Set.of(), 0));
    }

    List<StructuredBlock> assemble(List<PdfPageLayout> layouts, Set<String> headersAndFooters, PdfTocReference tocReference) {
        List<StructuredBlock> blocks = new ArrayList<>();
        PdfHeadingDetector headingDetector = new PdfHeadingDetector();
        PdfHeadingLevelResolver levelResolver = new PdfHeadingLevelResolver();
        PdfHeadingHierarchyNormalizer hierarchy = new PdfHeadingHierarchyNormalizer();
        PdfHeadingPathTracker paths = new PdfHeadingPathTracker();
        int ordinal=0;
        for (PdfPageLayout page : layouts) {
            if (tocReference.isTocPage(page.physicalPage())) continue;
            float averageFontSize = page.lines().stream().map(PdfTextLine::averageFontSize)
                    .reduce(0.0f, Float::sum) / Math.max(1, page.lines().size());
            for (int lineIndex = 0; lineIndex < page.lines().size(); lineIndex++) {
                PdfTextLine line = page.lines().get(lineIndex);
                if (headersAndFooters.contains(normalize(line.text()))) {
                    continue;
                }
                PdfTextLine previous = lineIndex == 0 ? null : page.lines().get(lineIndex - 1);
                PdfTextLine next = lineIndex + 1 >= page.lines().size() ? null : page.lines().get(lineIndex + 1);
                PdfTocEntry tocEntry = tocReference.match(line, page.physicalPage());
                boolean fallbackCandidate = hierarchy.likelyHeading(line, previous, next, averageFontSize, tocReference, page.physicalPage());
                int resolvedLevel = levelResolver.resolve(line, averageFontSize, tocEntry);
                boolean candidate = headingDetector.isHeading(line, averageFontSize) || fallbackCandidate;
                int level = candidate && (tocEntry != null || resolvedLevel > 0 || fallbackCandidate)
                        ? hierarchy.normalizeLevel(resolvedLevel, tocEntry) : 0;
                boolean heading=level > 0;
                if(level>0) paths.accept(level, tocEntry == null ? line.text() : tocEntry.title());
                int current=++ordinal; String path=paths.path();
                blocks.add(new StructuredBlock(heading ? BlockType.HEADING : BlockType.PARAGRAPH, line.text(),
                        new SourceLocator(SourceFormat.PDF, page.physicalPage(), page.physicalPage(), null, 0, 0, path.isBlank()?null:path, current),
                        line.boundingBox(), ExtractionConfidence.MEDIUM,
                        new BlockStructure(level,path.isBlank()?null:path,null,SequenceType.NONE,0,false,current,
                                line.columnIndex(),line.fullWidth()),
                        new com.hirain.aiagent.rag.indexer.model.PdfLineMetadata(page.physicalPage(), line.columnIndex(), line.lineIndex(), line.fullWidth())));
            }
        }
        return List.copyOf(blocks);
    }

    static String normalize(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }
}
