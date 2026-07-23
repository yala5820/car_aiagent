package com.hirain.aiagent.rag.policy;
/** 知识 Tool 历史仅保留受控短投影，避免完整正文、检索诊断或可引用 Evidence 跨请求泄漏。 */
public final class KnowledgeMemoryPolicy {
 public String compact(String complete){if(complete==null||complete.isBlank())return "{\"kind\":\"vehicle_knowledge\",\"status\":\"UNKNOWN\"}";String normalized=complete.replaceAll("[\\r\\n]+"," ");int limit=Math.min(normalized.length(),320);return "{\"kind\":\"vehicle_knowledge\",\"projection\":\""+escape(normalized.substring(0,limit))+"\"}";}
 private static String escape(String value){return value.replace("\\","\\\\").replace("\"","\\\"");}
}
