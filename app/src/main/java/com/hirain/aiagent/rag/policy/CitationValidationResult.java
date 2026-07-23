package com.hirain.aiagent.rag.policy;
import java.util.List;
/** 引用校验结果；失败时不返回模型原文，避免未知来源随回答泄露。 */
public record CitationValidationResult(boolean valid,String output,String reasonCode,List<String> evidenceIds){public CitationValidationResult{evidenceIds=List.copyOf(evidenceIds==null?List.of():evidenceIds);}public static CitationValidationResult fail(String reason){return new CitationValidationResult(false,null,reason,List.of());}}
