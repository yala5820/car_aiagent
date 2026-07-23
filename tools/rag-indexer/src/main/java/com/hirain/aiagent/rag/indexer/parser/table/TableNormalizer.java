package com.hirain.aiagent.rag.indexer.parser.table;

import java.util.ArrayList;
import java.util.List;

/** 对表格单元格进行确定性空白归一化，不猜测缺失列或合并单元格语义。 */
public final class TableNormalizer {
    public List<List<String>> normalize(List<List<String>> rows) {
        List<List<String>> normalized = new ArrayList<>();
        for (List<String> row : rows) {
            List<String> cells = new ArrayList<>();
            for (String cell : row) {
                cells.add(cell == null ? "" : cell.replaceAll("\\s+", " ").trim());
            }
            normalized.add(List.copyOf(cells));
        }
        return List.copyOf(normalized);
    }
}
