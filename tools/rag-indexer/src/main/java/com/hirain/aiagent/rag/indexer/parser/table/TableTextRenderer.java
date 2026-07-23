package com.hirain.aiagent.rag.indexer.parser.table;

import com.hirain.aiagent.rag.indexer.model.TableBlock;

import java.util.List;

/** 每行重复表名和表头语义，使单个 Child Chunk 脱离原表后仍可被正确理解。 */
public final class TableTextRenderer {
    public List<String> renderRows(TableBlock table) {
        String prefix = (table.title().isBlank() ? "表格" : table.title()) + "：";
        return table.rows().stream().map(row -> {
            StringBuilder line = new StringBuilder(prefix);
            for (int index = 0; index < table.headers().size(); index++) {
                if (index > 0) {
                    line.append("；");
                }
                line.append(table.headers().get(index)).append("=").append(row.get(index));
            }
            return line.toString();
        }).toList();
    }
}
