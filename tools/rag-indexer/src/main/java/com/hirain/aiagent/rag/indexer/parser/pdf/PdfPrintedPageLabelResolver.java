package com.hirain.aiagent.rag.indexer.parser.pdf;

import java.util.Optional;

/**
 * V1 只接受可无歧义识别的数字印刷页号；无法可靠解析时返回空，调用方仍保留物理页号。
 */
final class PdfPrintedPageLabelResolver {
    Optional<String> resolve(String pageEdgeText) {
        String candidate = pageEdgeText == null ? "" : pageEdgeText.trim();
        return candidate.matches("[1-9][0-9]*") ? Optional.of(candidate) : Optional.empty();
    }
}
