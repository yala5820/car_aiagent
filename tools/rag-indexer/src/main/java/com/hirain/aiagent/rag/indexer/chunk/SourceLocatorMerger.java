package com.hirain.aiagent.rag.indexer.chunk;

import com.hirain.aiagent.rag.indexer.model.SourceFormat;
import com.hirain.aiagent.rag.indexer.model.SourceLocator;

/** 只合并真实连续的相同格式定位；不连续时调用方必须拆 Child。 */
public final class SourceLocatorMerger {
    public SourceLocator merge(SourceLocator first, SourceLocator second) {
        if (first == null || second == null || first.sourceFormat() != second.sourceFormat()) {
            return null;
        }
        if (first.sourceFormat() == SourceFormat.PDF
                && second.pdfPageStart() >= first.pdfPageEnd() && second.pdfPageStart() <= first.pdfPageEnd() + 1) {
            return new SourceLocator(SourceFormat.PDF, first.pdfPageStart(), second.pdfPageEnd(), null, 0, 0,
                    first.headingPath(), first.sectionOrdinal());
        }
        if (first.sourceFormat() == SourceFormat.MARKDOWN
                && second.sourceLineStart() >= first.sourceLineEnd() && second.sourceLineStart() <= first.sourceLineEnd() + 1) {
            return new SourceLocator(SourceFormat.MARKDOWN, 0, 0, null, first.sourceLineStart(), second.sourceLineEnd(),
                    first.headingPath(), first.sectionOrdinal());
        }
        if (first.sourceFormat() == SourceFormat.STATIC_HTML && second.sectionOrdinal() == first.sectionOrdinal() + 1) {
            return new SourceLocator(SourceFormat.STATIC_HTML, 0, 0, null, 0, 0, first.headingPath(), first.sectionOrdinal());
        }
        return null;
    }
}
