package com.hirain.aiagent.rag.policy;
import java.util.*;
/** 当前 Request 内按 toolCallId 保存完整知识 ToolResult；对象由 RequestState 持有而非跨请求缓存。 */
public final class KnowledgeTurnBuffer {
 private final Map<String,String> completeByToolCallId=new LinkedHashMap<>();
 public synchronized void put(String toolCallId,String result){if(toolCallId==null||toolCallId.isBlank())throw new IllegalArgumentException("toolCallId required");completeByToolCallId.put(toolCallId,result==null?"{}":result);}
 public synchronized String complete(String toolCallId){return completeByToolCallId.get(toolCallId);}
 public synchronized boolean contains(String toolCallId){return completeByToolCallId.containsKey(toolCallId);}
}
