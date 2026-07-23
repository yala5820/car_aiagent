package com.hirain.aiagent.rag.indexer.model;

import java.util.List;

/** 单文档解析结果；失败通过 ERROR 诊断显式表达，不能静默产出部分正文。 */
public record ParseResult(List<StructuredBlock> blocks, List<TableBlock> tables, List<ParseDiagnostic> diagnostics) {
    public ParseResult {
        blocks = List.copyOf(blocks);
        tables = List.copyOf(tables);
        diagnostics = List.copyOf(diagnostics);
    }

    public ParseResult(List<StructuredBlock> blocks, List<ParseDiagnostic> diagnostics) {
        this(blocks, List.of(), diagnostics);
    }

    public boolean isSuccessful() {
        return diagnostics.stream().noneMatch(diagnostic -> diagnostic.severity() == DiagnosticSeverity.ERROR);
    }
}
