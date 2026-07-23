package com.hirain.aiagent.rag.indexer.parser.html;

import com.hirain.aiagent.rag.indexer.model.ParseDiagnostic;
import com.hirain.aiagent.rag.indexer.model.TableBlock;

import java.util.List;

/** HTML 表格与诊断必须一起返回，防止不支持结构被静默忽略。 */
record HtmlTableExtraction(List<TableBlock> tables, List<ParseDiagnostic> diagnostics) {
    HtmlTableExtraction {
        tables = List.copyOf(tables);
        diagnostics = List.copyOf(diagnostics);
    }
}
