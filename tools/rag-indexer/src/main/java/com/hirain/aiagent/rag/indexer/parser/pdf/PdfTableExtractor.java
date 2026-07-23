package com.hirain.aiagent.rag.indexer.parser.pdf;

import com.hirain.aiagent.rag.indexer.model.TableBlock;

import java.nio.file.Path;
import java.util.List;
import java.util.function.IntFunction;

/** PDF 表格提取边界；失败由调用方转成诊断，不得回填普通段落。 */
interface PdfTableExtractor {
    List<TableBlock> extract(Path file, IntFunction<PdfTableStrategy> strategyForPage) throws Exception;

    default List<TableBlock> extract(Path file, PdfTableStrategy strategy) throws Exception {
        return extract(file, ignoredPage -> strategy);
    }
}
