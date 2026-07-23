package com.hirain.aiagent.rag.indexer.chunk;

import com.hirain.aiagent.rag.contract.RagTokenEstimator;
import com.hirain.aiagent.rag.indexer.model.*;
import java.util.*;

/** 按最小自然小节生成 Parent；只有超过硬上限时才在同一小节内按 Block 语义边界拆分。 */
final class SectionParentChunker {
    private static final int HARD_MAX=2000;
    private final RagTokenEstimator tokens=new RagTokenEstimator();
    ParentChunkingResult chunk(SourceDocument source,ParseResult parsed){List<ParentChunk> out=new ArrayList<>();List<ChunkDiagnostic> diagnostics=new ArrayList<>();append(source,new DocumentSectionTreeBuilder().build(parsed.blocks()),parsed.tables(),out,diagnostics);return new ParentChunkingResult(List.copyOf(out),List.copyOf(diagnostics));}
    private void append(SourceDocument source,DocumentSection section,List<TableBlock> tables,List<ParentChunk> out,List<ChunkDiagnostic> diagnostics){
        if(!section.directBlocks.isEmpty()){
            List<List<StructuredBlock>> parts=split(section.directBlocks,diagnostics,section.path);
            for(List<StructuredBlock> part:parts){String text=String.join("\n",part.stream().map(StructuredBlock::text).toList());SourceLocator locator=part.get(0).locator();List<TableBlock> related=tables.stream().filter(t->Objects.equals(t.locator().headingPath(),section.path)).toList();out.add(new ParentChunk(out.size()+1,source.metadata().documentId(),source.metadata().title(),section.path,text,locator,part,related));}
        } for(DocumentSection child:section.children)append(source,child,tables,out,diagnostics);
    }
    private List<List<StructuredBlock>> split(List<StructuredBlock> blocks,List<ChunkDiagnostic> diagnostics,String path){List<List<StructuredBlock>> out=new ArrayList<>();List<StructuredBlock> current=new ArrayList<>();for(StructuredBlock block:blocks){
        List<StructuredBlock> candidate=new ArrayList<>(current);candidate.add(block);int candidateTokens=tokens.estimate(render(candidate)).totalTokens();
        if(!current.isEmpty()&&candidateTokens>HARD_MAX){out.add(List.copyOf(current));current=new ArrayList<>();}
        current.add(block);if(tokens.estimate(render(current)).totalTokens()>HARD_MAX)diagnostics.add(new ChunkDiagnostic("PARENT_ATOMIC_UNIT_OVER_HARD_LIMIT","小节 "+path+" 包含不可再分的超长语义单元，必须人工审核"));
    }if(!current.isEmpty())out.add(List.copyOf(current));return out;}
    private String render(List<StructuredBlock> blocks){return String.join("\n",blocks.stream().map(StructuredBlock::text).toList());}
    record ParentChunkingResult(List<ParentChunk> parents,List<ChunkDiagnostic> diagnostics){}
}
