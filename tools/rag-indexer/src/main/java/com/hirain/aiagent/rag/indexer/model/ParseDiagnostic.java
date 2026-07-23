package com.hirain.aiagent.rag.indexer.model;
/** 诊断只保存稳定原因码和简述，禁止泄露底层堆栈。 */
public record ParseDiagnostic(String reasonCode, DiagnosticSeverity severity, String documentId, SourceLocator locator, String summary) { }
