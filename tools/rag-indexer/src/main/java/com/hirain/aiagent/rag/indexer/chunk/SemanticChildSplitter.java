package com.hirain.aiagent.rag.indexer.chunk;

import com.hirain.aiagent.rag.contract.RagTokenEstimator;
import com.hirain.aiagent.rag.indexer.model.*;
import java.util.*;

/** V2 Child：先保留完整 Parent/语义组，只有超过预算才在同一 Parent 内递归拆分。 */
final class SemanticChildSplitter {
    private final RagTokenEstimator tokens=new RagTokenEstimator();
    Result split(ParentChunk parent,ChunkBoundaryPolicy policy){
        int target=policy.maxChildTokens(),soft=policy.softMaxChildTokens(),hard=policy.hardMaxChildTokens(); String full=parent.text();
        if(tokens.estimate(full).totalTokens()<=hard)return new Result(withTables(parent,List.of(new ChildChunk(parent.ordinal(),1,full,parent.locator(),"TEXT",tokens.estimate(full).totalTokens(),"PARENT_AS_CHILD",0)),policy),List.of());
        List<List<StructuredBlock>> units=units(parent.blocks());List<ChildChunk> out=new ArrayList<>();List<ChunkDiagnostic> diagnostics=new ArrayList<>();List<StructuredBlock> current=new ArrayList<>();int ordinal=0;
        for(List<StructuredBlock> unit:units){int size=tokenCount(unit);
            if(size>hard&&unit.size()==1&&!unit.get(0).structure().atomicSemanticGroup()){
                if(!current.isEmpty()){out.add(child(parent,++ordinal,current));current=new ArrayList<>();}
                FallbackResult fallback=fallbackSentences(unit.get(0).text(),hard);String previous="";for(String text:fallback.chunks()){int overlap=fallback.canReuseCompleteSentence()&&!previous.isBlank()?tokens.estimate(previous).totalTokens():0;out.add(new ChildChunk(parent.ordinal(),++ordinal,text,unit.get(0).locator(),"TEXT",tokens.estimate(text).totalTokens(),"LENGTH_FALLBACK",overlap));previous=lastSentence(text);}if(!fallback.canReuseCompleteSentence())diagnostics.add(new ChunkDiagnostic("CHILD_LENGTH_FALLBACK_WITHOUT_SENTENCE_BOUNDARY","Parent #"+parent.ordinal()+" 的超长自然段没有可复用的完整句子，已按长度安全拆分且不使用 overlap"));
                continue;
            }
            if(size>hard)diagnostics.add(new ChunkDiagnostic("CHILD_ATOMIC_GROUP_OVER_HARD_LIMIT","Parent #"+parent.ordinal()+" 的列表、步骤或 Warning 原子组超过 Child 硬上限，未被静默拆分"));
            List<StructuredBlock> candidate=new ArrayList<>(current);candidate.addAll(unit);int candidateTokens=tokenCount(candidate);
            // 优先在 target 附近结束；只有当前 Child 过短且合并后仍在 soft 内，才继续吸收下一个完整语义单元。
            if(!current.isEmpty()&&shouldFlush(current,candidateTokens,policy)){out.add(child(parent,++ordinal,current));current=new ArrayList<>();}
            current.addAll(unit);}
        if(!current.isEmpty())out.add(child(parent,++ordinal,current));return new Result(withTables(parent,out,policy),List.copyOf(diagnostics));
    }
    private List<List<StructuredBlock>> units(List<StructuredBlock> blocks){List<List<StructuredBlock>> out=new ArrayList<>();List<StructuredBlock> group=new ArrayList<>();String key=null;for(StructuredBlock block:blocks){String next=block.structure().sequenceGroupId();boolean atomic=block.structure().atomicSemanticGroup()&&next!=null;if(atomic&&Objects.equals(key,next)){group.add(block);continue;}if(!group.isEmpty())out.add(List.copyOf(group));group=new ArrayList<>();group.add(block);key=atomic?next:null;if(!atomic){out.add(List.copyOf(group));group=new ArrayList<>();}}if(!group.isEmpty())out.add(List.copyOf(group));return out;}
    /** 仅长度兜底：按完整句子切分，下一段复用最后一句以保留边界关系，且绝不跨 Parent。 */
    private FallbackResult fallbackSentences(String text,int hard){List<String> sentences=Arrays.stream(text.split("(?<=[。！？.!?])\\s*")).filter(value->!value.isBlank()).toList();if(sentences.size()<2||sentences.stream().anyMatch(sentence->tokens.estimate(sentence).totalTokens()>hard))return new FallbackResult(splitByLength(text,hard),false);List<String> out=new ArrayList<>();StringBuilder current=new StringBuilder();String previous="";for(String sentence:sentences){String candidate=append(current,sentence);if(current.length()>0&&tokens.estimate(candidate).totalTokens()>hard){out.add(current.toString());current=new StringBuilder();if(!previous.isBlank()&&tokens.estimate(previous).totalTokens()<=Math.max(1,hard/10))current.append(previous);candidate=append(current,sentence);if(tokens.estimate(candidate).totalTokens()>hard)current.setLength(0);}
            if(current.length()>0)current.append('\n');current.append(sentence);previous=sentence;}
        if(current.length()>0)out.add(current.toString());return new FallbackResult(List.copyOf(out),true);}
    /** 无可用句界时才按 Unicode 码点递归兜底；此时不得复制半句，因此 overlap 固定为零。 */
    private List<String> splitByLength(String text,int hard){List<String> out=new ArrayList<>();StringBuilder current=new StringBuilder();text.codePoints().forEach(codePoint->{String candidate=current.toString()+new String(Character.toChars(codePoint));if(current.length()>0&&tokens.estimate(candidate).totalTokens()>hard){out.add(current.toString());current.setLength(0);}current.appendCodePoint(codePoint);});if(current.length()>0)out.add(current.toString());return List.copyOf(out);}
    private String append(StringBuilder current,String value){return current.length()==0?value:current+"\n"+value;}
    private String lastSentence(String text){String[] values=text.split("(?<=[。！？.!?])\\s*");return values.length==0?"":values[values.length-1];}
    private ChildChunk child(ParentChunk parent,int ordinal,List<StructuredBlock> blocks){String text=String.join("\n",blocks.stream().map(StructuredBlock::text).toList());return new ChildChunk(parent.ordinal(),ordinal,text,blocks.get(0).locator(),blocks.stream().anyMatch(b->b.type()==BlockType.WARNING)?"WARNING":"TEXT",tokens.estimate(text).totalTokens(),"SEMANTIC_GROUP",0);}
    private int tokenCount(List<StructuredBlock> blocks){return tokens.estimate(String.join("\n",blocks.stream().map(StructuredBlock::text).toList())).totalTokens();}
    private boolean shouldFlush(List<StructuredBlock> current,int candidateTokens,ChunkBoundaryPolicy policy){if(candidateTokens>policy.softMaxChildTokens())return true;return candidateTokens>policy.maxChildTokens()&&tokenCount(current)>=policy.idealMinChildTokens();}
    private record FallbackResult(List<String> chunks,boolean canReuseCompleteSentence){}
    private List<ChildChunk> withTables(ParentChunk parent,List<ChildChunk> values,ChunkBoundaryPolicy policy){List<ChildChunk> out=new ArrayList<>(values);int ordinal=out.size();for(TableBlock table:parent.tables())for(String text:new TableChunkSplitter().split(table,policy.tableRowsPerChild()))out.add(new ChildChunk(parent.ordinal(),++ordinal,text,table.locator(),"TABLE",tokens.estimate(text).totalTokens(),"TABLE_ROWS",0));return List.copyOf(out);}
    record Result(List<ChildChunk> children,List<ChunkDiagnostic> diagnostics){}
}
