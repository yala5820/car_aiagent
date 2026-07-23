package com.hirain.aiagent.rag.indexer.chunk;

import com.hirain.aiagent.rag.indexer.model.TableBlock;
import com.hirain.aiagent.rag.indexer.parser.table.TableTextRenderer;

import java.util.ArrayList;
import java.util.List;

/** 长表按行拆分，每个 Child 调用 TableTextRenderer 重复表名和表头语义。 */
final class TableChunkSplitter {
    List<String> split(TableBlock table, int rowsPerChild) {
        List<String> rendered = new TableTextRenderer().renderRows(table);
        List<String> output = new ArrayList<>();
        for (int index = 0; index < rendered.size(); index += rowsPerChild) {
            output.add(String.join("\n", rendered.subList(index, Math.min(rendered.size(), index + rowsPerChild))));
        }
        return List.copyOf(output);
    }
}
