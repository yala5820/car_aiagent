package com.hirain.aiagent.rag.indexer.report;

import com.hirain.aiagent.rag.indexer.chunk.ChildChunk;
import com.hirain.aiagent.rag.indexer.chunk.ChunkResult;
import com.hirain.aiagent.rag.indexer.chunk.ParentChunk;
import com.hirain.aiagent.rag.indexer.corpus.CorpusDocumentDefinition;
import com.hirain.aiagent.rag.indexer.model.DocumentMetadata;
import com.hirain.aiagent.rag.indexer.model.SourceDocument;
import com.hirain.aiagent.rag.indexer.model.SourceFormat;
import com.hirain.aiagent.rag.indexer.model.SourceLocator;
import com.hirain.aiagent.rag.indexer.pipeline.DocumentBuildState;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 超硬上限不能只保留汇总数字，必须能回溯到具体 Child 和来源位置。 */
final class ChunkQualityAnalyzerTest {
    @Test
    void shouldExposeSourceLocationForOverHardChild() {
        SourceLocator locator=new SourceLocator(SourceFormat.STATIC_HTML,0,0,"section",0,0,"Maintenance",7);
        ParentChunk parent=new ParentChunk(1,"manual","Manual","Maintenance","甲".repeat(600),locator,List.of(),List.of());
        ChildChunk child=new ChildChunk(1,2,"甲".repeat(600),locator,"TEXT",600,"SEMANTIC_GROUP",0);
        DocumentBuildState state=new DocumentBuildState(
                new CorpusDocumentDefinition("manual","Manual","manual","1","zh","STATIC_HTML","manual.html","a".repeat(64),null),
                new SourceDocument(Path.of("manual.html"),SourceFormat.STATIC_HTML,new DocumentMetadata("manual","Manual","zh")),
                null,new ChunkResult(List.of(parent),List.of(child),List.of()),null);

        ChunkQualityReport report=new ChunkQualityAnalyzer().analyze(List.of(state));

        assertEquals(1,report.childOverHard());
        assertEquals(1,report.findings().size());
        assertEquals("manual",report.findings().get(0).documentId());
        assertEquals(2,report.findings().get(0).childOrdinal());
        assertEquals(7,report.findings().get(0).locator().sectionOrdinal());
    }
}
