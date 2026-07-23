package com.hirain.aiagent.rag.indexer.parser.markdown;

/** 按 Markdown ATX/Setext 标题层级维护路径，不补造跳级标题。 */
final class MarkdownHeadingPathTracker {
    private final String[] headings = new String[6];

    void accept(int level, String text) {
        headings[level - 1] = text;
        for (int index = level; index < headings.length; index++) {
            headings[index] = null;
        }
    }

    String path() {
        return java.util.Arrays.stream(headings).filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.joining(" > "));
    }
}
