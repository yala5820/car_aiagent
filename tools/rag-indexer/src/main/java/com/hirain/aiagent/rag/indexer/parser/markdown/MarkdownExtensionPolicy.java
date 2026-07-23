package com.hirain.aiagent.rag.indexer.parser.markdown;

/** V1 只启用 CommonMark 与 GFM 表格；原始 HTML 保留为诊断，不委托 HTML Parser 隐式处理。 */
final class MarkdownExtensionPolicy {
    boolean containsRawHtml(String source) {
        return source.matches("(?s).*<[/!]?[A-Za-z][^>]*>.*");
    }
}
