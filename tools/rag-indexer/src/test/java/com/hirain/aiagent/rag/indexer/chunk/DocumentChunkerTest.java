package com.hirain.aiagent.rag.indexer.chunk;

import com.hirain.aiagent.rag.indexer.model.BlockType;
import com.hirain.aiagent.rag.indexer.model.DocumentMetadata;
import com.hirain.aiagent.rag.indexer.model.ExtractionConfidence;
import com.hirain.aiagent.rag.indexer.model.ParseResult;
import com.hirain.aiagent.rag.indexer.model.SourceDocument;
import com.hirain.aiagent.rag.indexer.model.SourceFormat;
import com.hirain.aiagent.rag.indexer.model.SourceLocator;
import com.hirain.aiagent.rag.indexer.model.StructuredBlock;
import com.hirain.aiagent.rag.indexer.model.BlockStructure;
import com.hirain.aiagent.rag.indexer.model.SequenceType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DocumentChunkerTest {
    @Test
    void shouldCreateParentsByHeadingAndKeepWarningAtomic() {
        ParseResult parsed = new ParseResult(List.of(
                block(BlockType.HEADING, "Brakes", 1), block(BlockType.PARAGRAPH, "Maintain fluid level", 2),
                block(BlockType.WARNING, "Warning: do not drive with low fluid", 3),
                block(BlockType.HEADING, "Tires", 4), block(BlockType.PARAGRAPH, "Check pressure", 5)), List.of());

        ChunkResult result = new DocumentChunker(new ChunkBoundaryPolicy(32, 0, 2)).chunk(source(), parsed);

        assertEquals(2, result.parents().size());
        assertTrue(result.children().stream().anyMatch(chunk -> chunk.evidenceType().equals("WARNING") && chunk.text().contains("do not drive")));
    }

    @Test
    void shouldCreateSmallestNaturalSectionParentsWithoutCombiningSiblings() {
        ParseResult parsed=new ParseResult(List.of(
                v2(BlockType.HEADING,"Maintenance",1,"Maintenance",1),v2(BlockType.HEADING,"Brakes",2,"Maintenance > Brakes",2),v2(BlockType.PARAGRAPH,"Check brake fluid",3,"Maintenance > Brakes",0),
                v2(BlockType.HEADING,"Tires",4,"Maintenance > Tires",2),v2(BlockType.PARAGRAPH,"Check tire pressure",5,"Maintenance > Tires",0)),List.of());
        ChunkResult result=new DocumentChunker(new ChunkBoundaryPolicy(32,0,2)).chunk(source(),parsed);
        assertEquals(2,result.parents().size());assertEquals("Maintenance > Brakes",result.parents().get(0).headingPath());assertEquals("Maintenance > Tires",result.parents().get(1).headingPath());
    }

    @Test
    void shouldApplyV2SoftAndHardBoundariesUsingRenderedTextTokens() {
        ParseResult parsed=new ParseResult(List.of(
                v2(BlockType.HEADING,"Maintenance",1,"Maintenance",1),
                v2(BlockType.PARAGRAPH,"甲".repeat(220),2,"Maintenance",0),
                v2(BlockType.PARAGRAPH,"乙".repeat(220),3,"Maintenance",0),
                v2(BlockType.PARAGRAPH,"丙".repeat(220),4,"Maintenance",0)),List.of());

        ChunkResult result=new DocumentChunker(new ChunkBoundaryPolicy(256,0,2,384,512)).chunk(source(),parsed);

        assertTrue(result.children().size() >= 2);
        assertTrue(result.children().stream().allMatch(chunk -> chunk.tokenEstimate() <= 512));
    }

    @Test
    void shouldUseSentenceFallbackOnlyForAnOverlongNonAtomicBlock() {
        ParseResult parsed=new ParseResult(List.of(
                v2(BlockType.HEADING,"Maintenance",1,"Maintenance",1),
                v2(BlockType.PARAGRAPH,"保养提示。".repeat(150),2,"Maintenance",0)),List.of());

        ChunkResult result=new DocumentChunker(new ChunkBoundaryPolicy(256,0,2,384,512)).chunk(source(),parsed);

        assertTrue(result.children().size() >= 2);
        assertTrue(result.children().stream().allMatch(chunk -> chunk.tokenEstimate() <= 512));
        assertTrue(result.children().stream().allMatch(chunk -> "LENGTH_FALLBACK".equals(chunk.splitReason())));
        assertTrue(result.children().stream().skip(1).allMatch(chunk -> chunk.overlapTokenCount() > 0));
    }

    @Test
    void shouldPreferChildTargetBeforeSoftLimitWhenCurrentChildIsAlreadyLargeEnough() {
        ParseResult parsed=new ParseResult(List.of(
                v2(BlockType.HEADING,"Maintenance",1,"Maintenance",1),
                v2(BlockType.PARAGRAPH,"甲".repeat(210),2,"Maintenance",0),
                v2(BlockType.PARAGRAPH,"乙".repeat(100),3,"Maintenance",0),
                v2(BlockType.PARAGRAPH,"丙".repeat(300),4,"Maintenance",0)),List.of());

        ChunkResult result=new DocumentChunker(new ChunkBoundaryPolicy(256,0,2,320,512)).chunk(source(),parsed);

        assertEquals(List.of(210,100,300), result.children().stream().map(ChildChunk::tokenEstimate).toList());
    }

    @Test
    void shouldSafelySplitAnOverlongUnpunctuatedBlockWithoutOverlap() {
        ParseResult parsed=new ParseResult(List.of(
                v2(BlockType.HEADING,"Maintenance",1,"Maintenance",1),
                v2(BlockType.PARAGRAPH,"甲".repeat(1200),2,"Maintenance",0)),List.of());

        ChunkResult result=new DocumentChunker(new ChunkBoundaryPolicy(256,0,2,384,512)).chunk(source(),parsed);

        assertTrue(result.children().size() >= 2);
        assertTrue(result.children().stream().allMatch(chunk -> chunk.tokenEstimate() <= 512));
        assertTrue(result.children().stream().allMatch(chunk -> chunk.overlapTokenCount() == 0));
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> "CHILD_LENGTH_FALLBACK_WITHOUT_SENTENCE_BOUNDARY".equals(diagnostic.reasonCode())));
    }

    private StructuredBlock block(BlockType type, String text, int line) {
        return new StructuredBlock(type, text, new SourceLocator(SourceFormat.MARKDOWN, 0, 0, null, line, line, "Brakes", line), null, ExtractionConfidence.HIGH);
    }
    private StructuredBlock v2(BlockType type,String text,int line,String path,int level){SourceLocator locator=new SourceLocator(SourceFormat.MARKDOWN,0,0,null,line,line,path,line);return new StructuredBlock(type,text,locator,null,ExtractionConfidence.HIGH,new BlockStructure(level,path,null,SequenceType.NONE,0,false,line));}

    private SourceDocument source() {
        return new SourceDocument(Path.of("manual.md"), SourceFormat.MARKDOWN, new DocumentMetadata("manual", "Manual", "en"));
    }
}
