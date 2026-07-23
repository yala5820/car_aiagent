package com.hirain.aiagent.rag.policy;
import com.hirain.aiagent.rag.model.RetrievalEvidence;
import java.nio.charset.StandardCharsets;import java.security.MessageDigest;import java.util.*;
/** 按内部证据 ID、Parent 与内容 Hash 去重，避免同一说明以多个 Child 重复占用预算。 */
public final class EvidenceDeduplicator { public List<RetrievalEvidence> deduplicate(List<RetrievalEvidence> values){Set<String> seen=new HashSet<>();List<RetrievalEvidence> out=new ArrayList<>();for(RetrievalEvidence value:values){String key=value.retrievalEvidenceId()+"|"+value.parentChunkId()+"|"+hash(value.content());if(seen.add(key))out.add(value);}return List.copyOf(out);}private static String hash(String v){try{byte[] b=MessageDigest.getInstance("SHA-256").digest((v==null?"":v).getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte x:b)out.append(String.format("%02x",x));return out.toString();}catch(Exception e){throw new IllegalStateException(e);}} }
