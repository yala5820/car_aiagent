package com.hirain.aiagent.rag.indexer.evaluation;
import com.fasterxml.jackson.databind.ObjectMapper;import com.fasterxml.jackson.databind.SerializationFeature;import java.nio.file.*;import java.util.*;
/** 写出不含 Query/正文的 Rerank 可审计链路，供定位候选裁剪和 index 映射问题。 */
public final class RerankDiagnosticReportWriter { public void write(Path file,List<OfflineHybridEvaluator.RerankDiagnostic> values)throws Exception{if(Files.exists(file))throw new IllegalArgumentException("RERANK_DIAGNOSTIC_ALREADY_EXISTS");Files.createDirectories(file.getParent());Map<String,Object> root=new LinkedHashMap<>();root.put("schemaVersion",1);root.put("caseCount",values.size());root.put("cases",values);new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValue(file.toFile(),root);} }
