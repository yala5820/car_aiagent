package com.hirain.aiagent.rag.indexer.parser.pdf;

import com.hirain.aiagent.rag.indexer.model.SourceLocator;
import com.hirain.aiagent.rag.indexer.model.TableBlock;

import java.util.ArrayList;
import java.util.List;

/**
 * 只在物理页连续、表头完全一致且列数一致时合并跨页表。任何不确定性均保留为两个表，由调用方诊断。
 */
final class PdfCrossPageTableMerger {
    List<TableBlock> merge(List<TableBlock> tables) {
        List<TableBlock> merged = new ArrayList<>();
        for (TableBlock current : tables) {
            if (!merged.isEmpty() && canMerge(merged.get(merged.size() - 1), current)) {
                TableBlock previous = merged.remove(merged.size() - 1);
                List<List<String>> rows = new ArrayList<>(previous.rows());
                rows.addAll(current.rows());
                SourceLocator locator = new SourceLocator(previous.locator().sourceFormat(), previous.locator().pdfPageStart(),
                        current.locator().pdfPageEnd(), null, 0, 0, previous.locator().headingPath(), previous.locator().sectionOrdinal());
                merged.add(new TableBlock(previous.title(), previous.headers(), rows, locator,
                        previous.extractionMode(), previous.confidence(), previous.boundingBox()));
            } else {
                merged.add(current);
            }
        }
        return List.copyOf(merged);
    }

    private boolean canMerge(TableBlock previous, TableBlock current) {
        return previous.locator().pdfPageEnd() + 1 == current.locator().pdfPageStart()
                && previous.headers().equals(current.headers())
                && previous.extractionMode() == current.extractionMode()
                && positionsAreContinuous(previous.boundingBox(), current.boundingBox());
    }

    private boolean positionsAreContinuous(com.hirain.aiagent.rag.indexer.model.BoundingBox previous,
                                           com.hirain.aiagent.rag.indexer.model.BoundingBox current) {
        return previous != null && current != null
                && Math.abs(previous.left() - current.left()) <= 20.0f
                && Math.abs(previous.right() - current.right()) <= 20.0f;
    }
}
