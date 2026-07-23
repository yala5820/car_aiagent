package com.hirain.aiagent.rag.indexer.parser.markdown;

/** V1 不解释 Front Matter；仅识别并在 Parser 中跳过头部 YAML，避免其污染知识正文。 */
final class MarkdownFrontMatterDetector {
    int bodyStartOffset(String source) {
        if (!source.startsWith("---\n") && !source.startsWith("---\r\n")) {
            return 0;
        }
        int closing = source.indexOf("\n---", 4);
        return closing < 0 ? 0 : closing + 5;
    }
}
