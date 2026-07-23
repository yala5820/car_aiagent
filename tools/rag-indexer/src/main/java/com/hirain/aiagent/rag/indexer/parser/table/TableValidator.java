package com.hirain.aiagent.rag.indexer.parser.table;

import java.util.List;

/** 表格结构不可靠时返回稳定失败原因，调用方必须产生诊断而非将错列内容并入正文。 */
public final class TableValidator {
    public String validate(List<String> headers, List<List<String>> rows) {
        if (headers == null || headers.isEmpty() || headers.stream().anyMatch(header -> header == null || header.isBlank())) {
            return "TABLE_EMPTY_HEADER";
        }
        for (List<String> row : rows) {
            if (row.size() != headers.size()) {
                return "TABLE_COLUMN_COUNT_MISMATCH";
            }
            if (row.stream().anyMatch(cell -> cell == null)) {
                return "TABLE_NULL_CELL";
            }
        }
        return null;
    }
}
